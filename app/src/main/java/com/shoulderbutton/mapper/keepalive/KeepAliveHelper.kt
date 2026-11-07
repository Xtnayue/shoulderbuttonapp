package com.shoulderbutton.mapper.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.shoulderbutton.mapper.R
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object KeepAliveHelper {

    const val CHANNEL_ID = "shoulder_button_mapper_service_channel"
    const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持肩键映射核心服务在后台持续稳定运行，避免系统OOM清理"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun buildForegroundNotification(context: Context, statusText: String): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * 检查是否已忽略电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }

    /**
     * 申请加入电池优化白名单
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (!isIgnoringBatteryOptimizations(context)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("KeepAliveHelper", "Failed to launch battery optimization request", e)
            }
        }
    }

    /**
     * Root 提权防 OOM 杀进程:
     * 将当前进程的 oom_score_adj 写入 -1000 (OOM_SCORE_ADJ_MIN)，
     * 内核在此优先级下绝对不会杀该进程，即便前台大型游戏消耗全部内存。
     */
    suspend fun applyRootOomImmunity(): Boolean = withContext(Dispatchers.IO) {
        val pid = Process.myPid()
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo -1000 > /proc/$pid/oom_score_adj"))
            val exitCode = process.waitFor()
            Log.i("KeepAliveHelper", "Root oom_score_adj result for PID $pid: exitCode=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e("KeepAliveHelper", "Failed to set oom_score_adj via root", e)
            false
        }
    }

    /**
     * 根据当前提权层级执行保活优化
     */
    suspend fun optimizeKeepAliveForTier(context: Context, tier: PrivilegeTier) {
        requestIgnoreBatteryOptimizations(context)
        when (tier) {
            PrivilegeTier.ROOT -> {
                applyRootOomImmunity()
            }
            PrivilegeTier.ADB_SHIZUKU -> {
                // ADB 模式下，系统赋予 Shell 权限，可保活前台服务
            }
            PrivilegeTier.BASIC -> {
                // 依赖前台服务与已绑定的无障碍服务 (AccessibilityService 与 system_server 双向绑定保活)
            }
        }
    }
}
