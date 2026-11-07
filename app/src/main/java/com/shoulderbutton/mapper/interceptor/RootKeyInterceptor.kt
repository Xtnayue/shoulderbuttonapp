package com.shoulderbutton.mapper.interceptor

import android.util.Log
import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.model.PrivilegeTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class RootKeyInterceptor : IKeyInterceptor {

    override val tier: PrivilegeTier = PrivilegeTier.ROOT

    private var activeMappings: List<KeyMapping> = emptyList()
    private var callback: ((String, Boolean) -> Unit)? = null
    private var workerJob: Job? = null
    private var rootProcess: Process? = null

    // 匹配 EV_KEY 事件行，包容 ANSI 转义符与前导终端垃圾字符
    // 匹配如: /dev/input/event1: EV_KEY KEY_F1 DOWN
    // 或: ^[OP/dev/input/event7: EV_KEY KEY_F1 UP
    private val evKeyRegex = Regex("""EV_KEY\s+([A-Za-z0-9_]+)\s+([A-Za-z0-9_]+)""")
    private val ansiEscapeRegex = Regex("""\u001B(\[[0-9;]*[a-zA-Z]|O[a-zA-Z])""")

    override fun start(
        mappings: List<KeyMapping>,
        onTriggerEvent: (mappingId: String, isDown: Boolean) -> Unit
    ) {
        this.activeMappings = mappings
        this.callback = onTriggerEvent

        workerJob?.cancel()
        workerJob = CoroutineScope(Dispatchers.IO).launch {
            listenEventsViaRoot()
        }
    }

    override fun updateMappings(mappings: List<KeyMapping>) {
        this.activeMappings = mappings
    }

    override fun stop() {
        workerJob?.cancel()
        workerJob = null
        try {
            rootProcess?.destroy()
        } catch (_: Exception) {}
        rootProcess = null
        callback = null
    }

    private suspend fun listenEventsViaRoot() {
        try {
            // 监听所有输入设备，以同时捕获双物理肩键 (K40游戏增强版/Ares 上 event1 与 event7 均对应 xm_gamekey)
            val cmd = "getevent -l"
            Log.i("RootKeyInterceptor", "Starting root getevent with cmd: $cmd")

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            rootProcess = process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                line = reader.readLine() ?: break
                parseEventLine(line)
            }
        } catch (e: Exception) {
            Log.e("RootKeyInterceptor", "Root getevent stream error", e)
        }
    }

    private fun parseEventLine(rawLine: String) {
        if (!rawLine.contains("EV_KEY")) return

        // 清理终端 ANSI 转义字符
        val cleanLine = rawLine.replace(ansiEscapeRegex, "").trim()
        val match = evKeyRegex.find(cleanLine) ?: return

        val keyName = match.groupValues[1]  // 如 KEY_F1, KEY_F2
        val action = match.groupValues[2]   // 如 DOWN, UP, 00000001, 00000000

        // 过滤屏幕原生触摸事件
        if (keyName.contains("TOUCH", ignoreCase = true)) return

        val isDown = action.startsWith("DOWN", ignoreCase = true) || action == "00000001"
        val isUp = action.startsWith("UP", ignoreCase = true) || action == "00000000"

        if (!isDown && !isUp) return

        Log.i("RootKeyInterceptor", "Captured key: $keyName isDown=$isDown (raw: $rawLine)")

        val matched = activeMappings.find { mapping ->
            if (!mapping.isEnabled) return@find false

            // 精准匹配肩键 F1 (左 L) 与 F2 (右 R)
            if (keyName.equals("KEY_F1", ignoreCase = true)) {
                return@find mapping.id.endsWith("_L", ignoreCase = true) || mapping.id.contains("left", ignoreCase = true) || mapping.rawKeyName.equals("KEY_F1", ignoreCase = true) || mapping.rawEventCode == 59
            }
            if (keyName.equals("KEY_F2", ignoreCase = true)) {
                return@find mapping.id.endsWith("_R", ignoreCase = true) || mapping.id.contains("right", ignoreCase = true) || mapping.rawKeyName.equals("KEY_F2", ignoreCase = true) || mapping.rawEventCode == 60
            }

            if (mapping.rawKeyName.isNotBlank() && mapping.rawKeyName.equals(keyName, ignoreCase = true)) {
                return@find true
            }

            (keyName.contains("VOLUMEUP", ignoreCase = true) && mapping.rawEventCode == 115) ||
            (keyName.contains("VOLUMEDOWN", ignoreCase = true) && mapping.rawEventCode == 114) ||
            (keyName.contains("BTN_TL", ignoreCase = true) && mapping.rawEventCode == 310) ||
            (keyName.contains("BTN_TR", ignoreCase = true) && mapping.rawEventCode == 311)
        }

        matched?.let {
            Log.i("RootKeyInterceptor", "Dispatching mapping ${it.id} ($keyName) isDown=$isDown")
            callback?.invoke(it.id, isDown)
        }
    }
}
