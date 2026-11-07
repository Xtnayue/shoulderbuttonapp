package com.shoulderbutton.mapper.injector

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.shoulderbutton.mapper.model.PrivilegeTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.OutputStream
import java.lang.reflect.Method

class ShizukuTouchInjector : ITouchInjector {

    override val tier: PrivilegeTier = PrivilegeTier.ADB_SHIZUKU

    private var injectInputEventMethod: Method? = null
    private var iInputManagerInstance: Any? = null
    private var isReflectionInitialized = false

    // Shell 管道备用模式
    private var shellProcess: Process? = null
    private var shellOutputStream: OutputStream? = null
    private val shellLock = Any()

    // 多点触控状态管理 (Type B 协议)
    private data class ActivePointer(
        val pointerId: Int,
        var x: Float,
        var y: Float
    )

    private val pointerLock = Any()
    private val activePointers = mutableListOf<ActivePointer>()
    private var gestureDownTime: Long = 0L

    init {
        initBinderInjection()
        initShellProcess()
    }

    private fun initBinderInjection() {
        try {
            val inputBinder = SystemServiceHelper.getSystemService("input")
            if (inputBinder != null) {
                val wrappedBinder = ShizukuBinderWrapper(inputBinder)
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
                iInputManagerInstance = asInterfaceMethod.invoke(null, wrappedBinder)

                val iInputManagerClass = Class.forName("android.hardware.input.IInputManager")
                for (method in iInputManagerClass.methods) {
                    if (method.name == "injectInputEvent" && method.parameterTypes.size == 2) {
                        injectInputEventMethod = method
                        break
                    }
                }
                isReflectionInitialized = (injectInputEventMethod != null && iInputManagerInstance != null)
                Log.i("ShizukuTouchInjector", "IInputManager binder reflection initialized: $isReflectionInitialized")

                // 清理系统 InputDispatcher 中可能残留的卡死虚拟触控点
                clearStuckPointers()
            }
        } catch (e: Exception) {
            Log.w("ShizukuTouchInjector", "Binder reflection init failed, fallback to shell process", e)
        }
    }

    private fun initShellProcess() {
        synchronized(shellLock) {
            try {
                shellProcess?.destroy()
            } catch (_: Exception) {}
            try {
                shellProcess = com.shoulderbutton.mapper.privilege.ShizukuProcessHelper.newProcess(arrayOf("sh"), null, null)
                shellOutputStream = shellProcess?.outputStream
                Log.i("ShizukuTouchInjector", "Shizuku fallback shell initialized")
            } catch (e: Exception) {
                Log.e("ShizukuTouchInjector", "Failed to start Shizuku shell process", e)
                shellProcess = null
                shellOutputStream = null
            }
        }
    }

    /**
     * 清除 Android InputDispatcher 中卡死未释放的指针并重置内部触控状态
     */
    fun clearStuckPointers() {
        synchronized(pointerLock) {
            activePointers.clear()
        }
        if (!isReflectionInitialized || injectInputEventMethod == null || iInputManagerInstance == null) return
        val now = SystemClock.uptimeMillis()
        // 仅重置肩键专用的虚拟指针 (8 与 9)，切勿重置 0..7，避免切断屏幕物理手指触摸
        for (pid in 8..9) {
            try {
                val pointerProperties = arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = pid
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                )
                val pointerCoords = arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = 500f
                        this.y = 500f
                        this.pressure = 0.0f
                    }
                )
                val event = MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_CANCEL,
                    1,
                    pointerProperties,
                    pointerCoords,
                    0,
                    0,
                    1.0f,
                    1.0f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                injectInputEventMethod?.invoke(iInputManagerInstance, event, 0)
                event.recycle()
            } catch (_: Exception) {}
        }
        Log.i("ShizukuTouchInjector", "Sent ACTION_CANCEL for pointerIds 0..10 to clear stuck touch states")
    }

    override fun cancelAll() {
        clearStuckPointers()
    }

    private fun sendShellCommand(cmd: String) {
        synchronized(shellLock) {
            try {
                if (shellOutputStream == null) {
                    initShellProcess()
                }
                shellOutputStream?.let { os ->
                    os.write(cmd.toByteArray())
                    os.flush()
                }
            } catch (e: Exception) {
                Log.e("ShizukuTouchInjector", "Error writing command to Shizuku shell: $cmd", e)
                initShellProcess()
            }
        }
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float) {
        synchronized(pointerLock) {
            val now = SystemClock.uptimeMillis()
            val existingIndex = activePointers.indexOfFirst { it.pointerId == pointerId }
            if (existingIndex >= 0) {
                // 该触控点已处于按下状态，更新坐标并执行 MOVE
                activePointers[existingIndex].x = x
                activePointers[existingIndex].y = y
                dispatchCurrentPointersLocked(MotionEvent.ACTION_MOVE, now)
                return
            }

            val action: Int
            if (activePointers.isEmpty()) {
                // 首个触控点按下: ACTION_DOWN (pointerCount = 1)
                gestureDownTime = now
                activePointers.add(ActivePointer(pointerId, x, y))
                action = MotionEvent.ACTION_DOWN
            } else {
                // 存在其他触控点时的第二个/多个触控点按下: ACTION_POINTER_DOWN or (index shl SHIFT)
                activePointers.add(ActivePointer(pointerId, x, y))
                val pointerIndex = activePointers.size - 1
                action = MotionEvent.ACTION_POINTER_DOWN or (pointerIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            }

            val handled = dispatchCurrentPointersLocked(action, now)
            if (!handled) {
                CoroutineScope(Dispatchers.IO).launch {
                    sendShellCommand("input motionevent DOWN ${x.toInt()} ${y.toInt()}\n")
                }
            }
        }
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float) {
        synchronized(pointerLock) {
            val existingIndex = activePointers.indexOfFirst { it.pointerId == pointerId }
            if (existingIndex < 0) return

            val now = SystemClock.uptimeMillis()
            activePointers[existingIndex].x = x
            activePointers[existingIndex].y = y

            val handled = dispatchCurrentPointersLocked(MotionEvent.ACTION_MOVE, now)
            if (!handled) {
                CoroutineScope(Dispatchers.IO).launch {
                    sendShellCommand("input motionevent MOVE ${x.toInt()} ${y.toInt()}\n")
                }
            }
        }
    }

    override fun touchUp(pointerId: Int, x: Float, y: Float) {
        synchronized(pointerLock) {
            val existingIndex = activePointers.indexOfFirst { it.pointerId == pointerId }
            if (existingIndex < 0) {
                Log.w("ShizukuTouchInjector", "touchUp called for inactive pointerId: $pointerId")
                return
            }

            val now = SystemClock.uptimeMillis()
            activePointers[existingIndex].x = x
            activePointers[existingIndex].y = y

            val handled: Boolean
            if (activePointers.size == 1) {
                // 最后一个触控点抬起: ACTION_UP
                handled = dispatchCurrentPointersLocked(MotionEvent.ACTION_UP, now)
                activePointers.clear()
            } else {
                // 多点触控中的单个触控点抬起: ACTION_POINTER_UP or (index shl SHIFT)
                val action = MotionEvent.ACTION_POINTER_UP or (existingIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                handled = dispatchCurrentPointersLocked(action, now)
                activePointers.removeAt(existingIndex)
            }

            if (!handled) {
                CoroutineScope(Dispatchers.IO).launch {
                    sendShellCommand("input motionevent UP ${x.toInt()} ${y.toInt()}\n")
                }
            }
        }
    }

    override fun performClick(pointerId: Int, x: Float, y: Float, durationMs: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            touchDown(pointerId, x, y)
            delay(durationMs.coerceIn(10L, 50L))
            touchUp(pointerId, x, y)
        }
    }

    private fun dispatchCurrentPointersLocked(action: Int, eventTime: Long): Boolean {
        val count = activePointers.size
        if (count == 0) return false

        if (isReflectionInitialized && injectInputEventMethod != null && iInputManagerInstance != null) {
            try {
                val pointerProperties = Array(count) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = activePointers[i].pointerId
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val pointerCoords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        this.x = activePointers[i].x
                        this.y = activePointers[i].y
                        this.pressure = 1.0f
                        this.size = 1.0f
                    }
                }

                val motionEvent = MotionEvent.obtain(
                    gestureDownTime,
                    eventTime,
                    action,
                    count,
                    pointerProperties,
                    pointerCoords,
                    0,
                    0,
                    1.0f,
                    1.0f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0 // Display.DEFAULT_DISPLAY
                )
                val result = injectInputEventMethod?.invoke(iInputManagerInstance, motionEvent, 0)
                motionEvent.recycle()
                val success = (result as? Boolean) == true
                if (!success) {
                    Log.w("ShizukuTouchInjector", "injectInputEvent returned false for action $action")
                }
                return success
            } catch (e: Exception) {
                Log.e("ShizukuTouchInjector", "Error calling injectInputEvent via binder", e)
            }
        }
        return false
    }
}
