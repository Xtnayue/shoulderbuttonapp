package com.shoulderbutton.mapper.model

import android.view.KeyEvent

/**
 * 三套提权层级
 */
enum class PrivilegeTier(val displayName: String, val description: String) {
    BASIC(
        "Basic (基础无障碍)",
        "免Root/免ADB。利用系统无障碍服务(AccessibilityService)捕获标准按键，使用dispatchGesture进行手势模拟。"
    ),
    ADB_SHIZUKU(
        "ADB / Shizuku (特权服务)",
        "无需Root。通过Shizuku绑定系统级IInputManager或执行adb getevent/sendevent，毫秒级注入触控与高速事件监听。"
    ),
    ROOT(
        "Root (底层驱动级)",
        "最高权限。直接监听/dev/input/event*内核节点(支持私有电容肩键传感器)，独占拦截，uinput或直接写入触控驱动，调整oom_score_adj实现真后台保活。"
    );

    companion object {
        fun fromName(name: String?): PrivilegeTier {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: BASIC
        }
    }
}

/**
 * 肩键触发后的点击模式
 */
enum class ClickMode(val displayName: String) {
    SINGLE_TAP("单次点击"),
    RAPID_FIRE("高速连点"),
    PRESS_HOLD("按住持续触摸")
}

/**
 * 单个肩键与屏幕映射配置
 */
data class KeyMapping(
    val id: String,                 // 触发器标识，例如 "trigger_L", "trigger_R"
    var label: String,              // 界面展示名称，例如 "左肩键 L (F1)", "右肩键 R (F2)"
    var targetKeyCode: Int,         // 基础模式监听的按键码 (KeyEvent.KEYCODE_F1 = 131, KEYCODE_F2 = 132)
    var rawEventCode: Int = 0,      // Linux input_event keycode (KEY_F1=59, KEY_F2=60)
    var rawKeyName: String = "",    // getevent -l 中的键名名称，例如 "KEY_F1", "KEY_F2"
    var devicePath: String = "/dev/input/event1", // 硬件输入节点路径
    var xRatio: Float = 0.2f,       // 映射在屏幕上的水平相对坐标 (0.0f ~ 1.0f)
    var yRatio: Float = 0.5f,       // 映射在屏幕上的垂直相对坐标 (0.0f ~ 1.0f)
    var clickMode: ClickMode = ClickMode.SINGLE_TAP,
    var rapidFireHz: Int = 10,      // 连点模式下的每秒点击次数 (5 ~ 30Hz)
    var isEnabled: Boolean = true
)

/**
 * 针对特定应用的映射配置方案 (支持多应用独立配置)
 */
data class AppProfile(
    val packageName: String,        // 目标应用包名 (例如 "com.tencent.tmgp.pubgmhd" 或 "default")
    var appName: String,            // 应用名称 (例如 "和平精英" 或 "默认全局方案")
    val mappings: MutableList<KeyMapping> = mutableListOf(),
    var isEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_PACKAGE = "default"

        fun createDefaultProfile(): AppProfile {
            return AppProfile(
                packageName = DEFAULT_PACKAGE,
                appName = "通用默认方案 (F1/F2肩键)",
                mappings = mutableListOf(
                    KeyMapping(
                        id = "trigger_L",
                        label = "左肩键 L (F1)",
                        targetKeyCode = KeyEvent.KEYCODE_F1, // 131
                        rawEventCode = 59,                  // KEY_F1 (0x3b)
                        rawKeyName = "KEY_F1",
                        devicePath = "/dev/input/event1",
                        xRatio = 0.15f,
                        yRatio = 0.35f,
                        clickMode = ClickMode.SINGLE_TAP
                    ),
                    KeyMapping(
                        id = "trigger_R",
                        label = "右肩键 R (F2)",
                        targetKeyCode = KeyEvent.KEYCODE_F2, // 132
                        rawEventCode = 60,                  // KEY_F2 (0x3c)
                        rawKeyName = "KEY_F2",
                        devicePath = "/dev/input/event1",
                        xRatio = 0.85f,
                        yRatio = 0.35f,
                        clickMode = ClickMode.SINGLE_TAP
                    )
                ),
                isEnabled = true
            )
        }
    }
}
