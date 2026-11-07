package com.shoulderbutton.mapper.interceptor

import android.util.Log
import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.model.PrivilegeTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuKeyInterceptor : IKeyInterceptor {

    override val tier: PrivilegeTier = PrivilegeTier.ADB_SHIZUKU

    private var activeMappings: List<KeyMapping> = emptyList()
    private var callback: ((String, Boolean) -> Unit)? = null
    private var workerJob: Job? = null
    private var shizukuProcess: Process? = null

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
            listenEventsViaShizuku()
        }
    }

    override fun updateMappings(mappings: List<KeyMapping>) {
        this.activeMappings = mappings
    }

    override fun stop() {
        workerJob?.cancel()
        workerJob = null
        try {
            shizukuProcess?.destroy()
        } catch (_: Exception) {}
        shizukuProcess = null
        callback = null
    }

    private suspend fun listenEventsViaShizuku() {
        try {
            val cmd = arrayOf("sh", "-c", "getevent -l")
            Log.i("ShizukuKeyInterceptor", "Starting Shizuku getevent -l")
            val process = com.shoulderbutton.mapper.privilege.ShizukuProcessHelper.newProcess(cmd, null, null)
            shizukuProcess = process

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                line = reader.readLine() ?: break
                parseEventLine(line)
            }
        } catch (e: Exception) {
            Log.e("ShizukuKeyInterceptor", "Error reading getevent stream via Shizuku", e)
        }
    }

    private fun parseEventLine(rawLine: String) {
        if (!rawLine.contains("EV_KEY")) return

        val cleanLine = rawLine.replace(ansiEscapeRegex, "").trim()
        val match = evKeyRegex.find(cleanLine) ?: return

        val keyName = match.groupValues[1]  // KEY_F1, KEY_F2
        val action = match.groupValues[2]   // DOWN, UP

        // 过滤触摸屏本身的点击事件 (如 BTN_TOUCH)，避免干扰按键拦截
        if (keyName.contains("TOUCH", ignoreCase = true)) return

        val isDown = action.startsWith("DOWN", ignoreCase = true) || action == "00000001"
        val isUp = action.startsWith("UP", ignoreCase = true) || action == "00000000"

        if (!isDown && !isUp) return

        Log.i("ShizukuKeyInterceptor", "Captured key: $keyName isDown=$isDown")

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
            Log.i("ShizukuKeyInterceptor", "Dispatching mapping ${it.id} ($keyName) isDown=$isDown")
            callback?.invoke(it.id, isDown)
        }
    }
}
