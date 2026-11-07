package com.shoulderbutton.mapper.interceptor

import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.model.PrivilegeTier

interface IKeyInterceptor {
    val tier: PrivilegeTier

    /**
     * 开始监听肩键事件
     * @param mappings 当前活动配置中的肩键映射列表
     * @param onTriggerEvent 触发回调 (mappingId, isDown)
     */
    fun start(
        mappings: List<KeyMapping>,
        onTriggerEvent: (mappingId: String, isDown: Boolean) -> Unit
    )

    /**
     * 更新当前监听的映射列表 (在切换配置时调用)
     */
    fun updateMappings(mappings: List<KeyMapping>)

    /**
     * 停止监听
     */
    fun stop()
}
