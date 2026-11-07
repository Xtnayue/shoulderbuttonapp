package com.shoulderbutton.mapper.interceptor

import android.view.KeyEvent
import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.service.ShoulderAccessibilityService

class BasicAccessibilityInterceptor : IKeyInterceptor {

    override val tier: PrivilegeTier = PrivilegeTier.BASIC

    private var activeMappings: List<KeyMapping> = emptyList()
    private var callback: ((String, Boolean) -> Unit)? = null

    // 记录按键状态，防止连续派发 ACTION_MULTIPLE 或 重复 DOWN
    private val keyStateMap = HashMap<Int, Boolean>()

    override fun start(
        mappings: List<KeyMapping>,
        onTriggerEvent: (mappingId: String, isDown: Boolean) -> Unit
    ) {
        this.activeMappings = mappings
        this.callback = onTriggerEvent

        // 注册到无障碍服务中接收全局按键
        ShoulderAccessibilityService.keyListener = { event ->
            handleKeyEvent(event)
        }
    }

    override fun updateMappings(mappings: List<KeyMapping>) {
        this.activeMappings = mappings
    }

    override fun stop() {
        ShoulderAccessibilityService.keyListener = null
        callback = null
        keyStateMap.clear()
    }

    /**
     * 处理无障碍服务截获的 KeyEvent
     * @return 返回 true 表示消费掉该按键（例如拦截音量键弹出音量面板），false 则放行给系统
     */
    private fun handleKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val matchedMapping = activeMappings.find { it.isEnabled && it.targetKeyCode == keyCode }

        if (matchedMapping != null) {
            val isDown = (event.action == KeyEvent.ACTION_DOWN)
            val lastState = keyStateMap[keyCode] ?: false

            if (isDown != lastState) {
                keyStateMap[keyCode] = isDown
                callback?.invoke(matchedMapping.id, isDown)
            }
            // 拦截该按键，不让系统弹出音量条或干扰前台应用
            return true
        }
        return false
    }
}
