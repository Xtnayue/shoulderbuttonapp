package com.shoulderbutton.mapper.detector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.service.ShoulderAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ForegroundAppDetector(private val context: Context) {

    private var currentForegroundPackage: String = ""
    private var listener: ((packageName: String) -> Unit)? = null
    private var pollingJob: Job? = null

    fun startListening(tier: PrivilegeTier, onAppChanged: (packageName: String) -> Unit) {
        this.listener = onAppChanged

        // 1. 无障碍服务窗口状态监听 (针对 Tier 1 极度省电且响应即时)
        ShoulderAccessibilityService.windowChangeListener = { packageName ->
            handlePackageChanged(packageName)
        }

        // 2. 轮询备用 (针对 Shizuku / Root 或无障碍未就绪情况)
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val pkg = detectCurrentTopPackage(tier)
                if (pkg.isNotBlank() && pkg != currentForegroundPackage) {
                    handlePackageChanged(pkg)
                }
                delay(1200L) // 1.2秒轮询一次，兼顾电量消耗与响应
            }
        }
    }

    fun stopListening() {
        ShoulderAccessibilityService.windowChangeListener = null
        pollingJob?.cancel()
        pollingJob = null
        listener = null
    }

    private fun handlePackageChanged(packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        // 忽略输入法、系统SystemUI状态栏等非主应用切换
        if (packageName.contains("inputmethod") || packageName == "com.android.systemui") return

        if (packageName != currentForegroundPackage) {
            currentForegroundPackage = packageName
            Log.i("ForegroundAppDetector", "Active foreground app changed to: $packageName")
            listener?.invoke(packageName)
        }
    }

    /**
     * 根据提权层级检测当前顶层包名
     */
    private fun detectCurrentTopPackage(tier: PrivilegeTier): String {
        return when (tier) {
            PrivilegeTier.ROOT -> getTopPackageViaShell("su")
            PrivilegeTier.ADB_SHIZUKU -> {
                if (Shizuku.pingBinder()) {
                    getTopPackageViaShizuku()
                } else {
                    getTopPackageViaUsageStats()
                }
            }
            PrivilegeTier.BASIC -> getTopPackageViaUsageStats()
        }
    }

    /**
     * 使用 UsageStatsManager 查询
     */
    private fun getTopPackageViaUsageStats(): String {
        try {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return ""
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 1000 * 5

            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()
            var lastPkg = ""

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    lastPkg = event.packageName
                }
            }
            return lastPkg
        } catch (_: Exception) {
            return ""
        }
    }

    /**
     * 通过 Shizuku dumpsys window 匹配 mCurrentFocus
     */
    private fun getTopPackageViaShizuku(): String {
        return try {
            val process = com.shoulderbutton.mapper.privilege.ShizukuProcessHelper.newProcess(
                arrayOf("sh", "-c", "dumpsys window | grep mCurrentFocus"), null, null
            )
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var foundPkg = ""
            while (reader.readLine().also { line = it } != null) {
                val pkg = extractPackageNameFromFocus(line ?: "")
                if (pkg.isNotBlank()) {
                    foundPkg = pkg
                    break
                }
            }
            foundPkg
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 通过 Root shell dumpsys window 匹配 mCurrentFocus
     */
    private fun getTopPackageViaShell(shellCmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(shellCmd, "-c", "dumpsys window | grep mCurrentFocus"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            var foundPkg = ""
            while (reader.readLine().also { line = it } != null) {
                val pkg = extractPackageNameFromFocus(line ?: "")
                if (pkg.isNotBlank()) {
                    foundPkg = pkg
                    break
                }
            }
            if (foundPkg.isBlank()) {
                // 备用 fallback: dumpsys activity activities
                val p2 = Runtime.getRuntime().exec(arrayOf(shellCmd, "-c", "dumpsys activity activities | grep topResumedActivity"))
                val r2 = BufferedReader(InputStreamReader(p2.inputStream))
                while (r2.readLine().also { line = it } != null) {
                    val pkg = extractPackageNameFromFocus(line ?: "")
                    if (pkg.isNotBlank()) {
                        foundPkg = pkg
                        break
                    }
                }
            }
            foundPkg
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractPackageNameFromFocus(focusLine: String): String {
        // 输出示例: mCurrentFocus=Window{c129e9d u0 com.tencent.tmgp.pubgmhd/com.epicgames.ue4.SplashActivity}
        // 或: topResumedActivity=ActivityRecord{... u0 com.tencent.tmgp.dfm/...}
        val match = Regex("""([a-zA-Z0-9._]+)/""").find(focusLine)
        return match?.groupValues?.get(1) ?: ""
    }
}
