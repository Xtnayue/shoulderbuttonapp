package com.shoulderbutton.mapper.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class ShoulderAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ShoulderAccessibilityService connected")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 如果注册了按键监听器且返回 true，则拦截该按键，不让系统处理
        val consumed = keyListener?.invoke(event) ?: false
        return if (consumed) true else super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (!packageName.isNullOrBlank()) {
                windowChangeListener?.invoke(packageName)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ShoulderAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        keyListener = null
        windowChangeListener = null
        Log.i(TAG, "ShoulderAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "ShoulderAccessibility"

        @Volatile
        var instance: ShoulderAccessibilityService? = null

        var keyListener: ((KeyEvent) -> Boolean)? = null
        var windowChangeListener: ((packageName: String) -> Unit)? = null
    }
}
