package com.shoulderbutton.mapper.injector

import com.shoulderbutton.mapper.model.PrivilegeTier

interface ITouchInjector {
    val tier: PrivilegeTier

    /**
     * 按下触摸点
     * @param pointerId 触控点 ID (多点触控 0, 1, 2...)
     * @param x 屏幕绝对横坐标 (px)
     * @param y 屏幕绝对纵坐标 (px)
     */
    fun touchDown(pointerId: Int, x: Float, y: Float)

    /**
     * 移动触摸点
     */
    fun touchMove(pointerId: Int, x: Float, y: Float)

    /**
     * 抬起触摸点
     */
    fun touchUp(pointerId: Int, x: Float, y: Float)

    /**
     * 执行单次点击
     * @param pointerId 触控点 ID (多点触控 0, 1, 2...)
     */
    fun performClick(pointerId: Int = 0, x: Float, y: Float, durationMs: Long = 25L)

    /**
     * 取消并重置所有触控点状态
     */
    fun cancelAll() {}
}
