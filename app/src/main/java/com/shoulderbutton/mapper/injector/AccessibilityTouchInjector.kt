package com.shoulderbutton.mapper.injector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.service.ShoulderAccessibilityService

class AccessibilityTouchInjector : ITouchInjector {

    override val tier: PrivilegeTier = PrivilegeTier.BASIC

    override fun touchDown(pointerId: Int, x: Float, y: Float) {
        // 无障碍服务基础手势调度单次按压
        performClick(pointerId, x, y, 100L)
    }

    override fun touchMove(pointerId: Int, x: Float, y: Float) {
        // Basic 模式手势以路径为主
    }

    override fun touchUp(pointerId: Int, x: Float, y: Float) {
        // Basic 模式由手势持续时间自然结束
    }

    override fun performClick(pointerId: Int, x: Float, y: Float, durationMs: Long) {
        val service = ShoulderAccessibilityService.instance
        if (service == null) {
            Log.w("AccessibilityInjector", "AccessibilityService not running")
            return
        }

        try {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(10L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                    }
                },
                null
            )
        } catch (e: Exception) {
            Log.e("AccessibilityInjector", "Failed to dispatch gesture", e)
        }
    }
}
