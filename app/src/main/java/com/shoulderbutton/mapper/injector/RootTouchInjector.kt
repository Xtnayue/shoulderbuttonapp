package com.shoulderbutton.mapper.injector

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface
import com.shoulderbutton.mapper.model.PrivilegeTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream

class RootTouchInjector(private val context: Context) : ITouchInjector {

    override val tier: PrivilegeTier = PrivilegeTier.ROOT

    private var daemonProcess: Process? = null
    private var outputStream: OutputStream? = null
    private val daemonLock = Any()

    init {
        ensureDaemonInstalled()
        initDaemonProcess()
    }

    private fun ensureDaemonInstalled() {
        try {
            val targetPath = "/data/local/tmp/evdev_injector"
            val tmpFile = File(context.cacheDir, "evdev_injector")
            context.assets.open("evdev_injector").use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Runtime.getRuntime().exec(
                arrayOf("su", "-c", "cp ${tmpFile.absolutePath} $targetPath && chmod 755 $targetPath")
            ).waitFor()
            tmpFile.delete()
            Log.i("RootTouchInjector", "evdev_injector native daemon verified at $targetPath")
        } catch (e: Exception) {
            Log.w("RootTouchInjector", "Failed to deploy evdev_injector from assets, fallback to existing", e)
        }
    }

    private fun initDaemonProcess() {
        synchronized(daemonLock) {
            try {
                outputStream?.close()
                daemonProcess?.destroy()
            } catch (_: Exception) {}

            try {
                // 启动常驻 Root 原生驱动注入守护进程，直接写入物理触摸屏 /dev/input/event4
                val cmd = arrayOf("su", "-c", "/data/local/tmp/evdev_injector /dev/input/event4")
                daemonProcess = Runtime.getRuntime().exec(cmd)
                outputStream = daemonProcess?.outputStream
                Log.i("RootTouchInjector", "Native evdev touch daemon started on /dev/input/event4")
            } catch (e: Exception) {
                Log.e("RootTouchInjector", "Failed to start native evdev touch daemon", e)
                daemonProcess = null
                outputStream = null
            }
        }
    }

    private fun sendCommand(cmd: String) {
        synchronized(daemonLock) {
            try {
                if (outputStream == null) {
                    initDaemonProcess()
                }
                outputStream?.let { os ->
                    os.write(cmd.toByteArray())
                    os.flush()
                }
            } catch (e: Exception) {
                Log.e("RootTouchInjector", "Error writing command to evdev daemon: $cmd", e)
                initDaemonProcess()
            }
        }
    }

    private fun getDisplayRotation(): Int {
        return try {
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
            display?.rotation ?: Surface.ROTATION_90
        } catch (_: Exception) {
            Surface.ROTATION_90
        }
    }

    /**
     * 将屏幕显示像素坐标 (例如横屏 2400x1080) 映射为底层物理传感器坐标 (rawX 0..17279, rawY 0..38399)
     */
    private fun mapCoordinates(x: Float, y: Float): Pair<Int, Int> {
        val rotation = getDisplayRotation()
        return when (rotation) {
            Surface.ROTATION_90 -> { // ROT_270
                val rawX = ((1080f - y) * 16f).toInt().coerceIn(0, 17279)
                val rawY = (x * 16f).toInt().coerceIn(0, 38399)
                Pair(rawX, rawY)
            }
            Surface.ROTATION_270 -> { // ROT_90
                val rawX = (y * 16f).toInt().coerceIn(0, 17279)
                val rawY = ((2400f - x) * 16f).toInt().coerceIn(0, 38399)
                Pair(rawX, rawY)
            }
            Surface.ROTATION_180 -> {
                val rawX = ((1080f - x) * 16f).toInt().coerceIn(0, 17279)
                val rawY = ((2400f - y) * 16f).toInt().coerceIn(0, 38399)
                Pair(rawX, rawY)
            }
            else -> { // ROTATION_0 (竖屏)
                val rawX = (x * 16f).toInt().coerceIn(0, 17279)
                val rawY = (y * 16f).toInt().coerceIn(0, 38399)
                Pair(rawX, rawY)
            }
        }
    }

    override fun touchDown(pointerId: Int, x: Float, y: Float) {
        val (rx, ry) = mapCoordinates(x, y)
        sendCommand("D $pointerId $rx $ry\n")
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float) {
        val (rx, ry) = mapCoordinates(x, y)
        sendCommand("M $pointerId $rx $ry\n")
    }

    override fun touchUp(pointerId: Int, x: Float, y: Float) {
        sendCommand("U $pointerId\n")
    }

    override fun performClick(pointerId: Int, x: Float, y: Float, durationMs: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            touchDown(pointerId, x, y)
            delay(durationMs.coerceIn(10L, 50L))
            touchUp(pointerId, x, y)
        }
    }

    override fun cancelAll() {
        sendCommand("C\n")
    }

    fun close() {
        synchronized(daemonLock) {
            try {
                outputStream?.write("Q\n".toByteArray())
                outputStream?.flush()
                outputStream?.close()
                daemonProcess?.destroy()
            } catch (_: Exception) {}
            outputStream = null
            daemonProcess = null
        }
    }
}
