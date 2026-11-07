package com.shoulderbutton.mapper.privilege

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.service.ShoulderAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

class PrivilegeManager private constructor(private val context: Context) {

    private val sharedPrefs = context.getSharedPreferences("privilege_prefs", Context.MODE_PRIVATE)

    var selectedTier: PrivilegeTier
        get() {
            val name = sharedPrefs.getString("tier_mode", PrivilegeTier.BASIC.name)
            return PrivilegeTier.fromName(name)
        }
        set(value) {
            sharedPrefs.edit().putString("tier_mode", value.name).apply()
        }

    var isAutoTier: Boolean
        get() = sharedPrefs.getBoolean("auto_tier", true)
        set(value) = sharedPrefs.edit().putBoolean("auto_tier", value).apply()

    /**
     * 检测 Root 是否可用 (非阻塞执行)
     */
    suspend fun checkRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup"
        )
        val hasBinary = suPaths.any { File(it).exists() } || canExecuteSu()
        hasBinary
    }

    private fun canExecuteSu(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检测 Shizuku 服务运行状态与权限授权
     */
    fun checkShizukuAvailable(): Boolean {
        return try {
            if (Shizuku.pingBinder()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 检测 Shizuku 服务是否运行 (即便暂未授权)
     */
    fun isShizukuServiceRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission(requestCode: Int) {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(requestCode)
        }
    }

    /**
     * 检测无障碍服务是否已开启
     */
    fun checkAccessibilityEnabled(): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val expectedServiceName = "${context.packageName}/${ShoulderAccessibilityService::class.java.canonicalName}"
        return enabledServices.contains(expectedServiceName) || accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        ).any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    /**
     * 获取当前生效的提权层级 (若开启自动选择，则按 Root > Shizuku > Basic 降级)
     */
    suspend fun getEffectiveTier(): PrivilegeTier {
        if (!isAutoTier) {
            return selectedTier
        }
        if (checkRootAvailable()) {
            return PrivilegeTier.ROOT
        }
        if (checkShizukuAvailable()) {
            return PrivilegeTier.ADB_SHIZUKU
        }
        return PrivilegeTier.BASIC
    }

    companion object {
        @Volatile
        private var instance: PrivilegeManager? = null

        fun getInstance(context: Context): PrivilegeManager {
            return instance ?: synchronized(this) {
                instance ?: PrivilegeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
