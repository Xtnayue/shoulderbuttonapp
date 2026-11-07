package com.shoulderbutton.mapper.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.shoulderbutton.mapper.detector.ForegroundAppDetector
import com.shoulderbutton.mapper.injector.AccessibilityTouchInjector
import com.shoulderbutton.mapper.injector.ITouchInjector
import com.shoulderbutton.mapper.injector.RootTouchInjector
import com.shoulderbutton.mapper.injector.ShizukuTouchInjector
import com.shoulderbutton.mapper.interceptor.BasicAccessibilityInterceptor
import com.shoulderbutton.mapper.interceptor.IKeyInterceptor
import com.shoulderbutton.mapper.interceptor.RootKeyInterceptor
import com.shoulderbutton.mapper.interceptor.ShizukuKeyInterceptor
import com.shoulderbutton.mapper.keepalive.KeepAliveHelper
import com.shoulderbutton.mapper.model.AppProfile
import com.shoulderbutton.mapper.model.ClickMode
import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.overlay.FloatingOverlayManager
import com.shoulderbutton.mapper.privilege.PrivilegeManager
import com.shoulderbutton.mapper.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MappingCoreService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var privilegeManager: PrivilegeManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var overlayManager: FloatingOverlayManager
    private lateinit var appDetector: ForegroundAppDetector

    private var currentTier: PrivilegeTier = PrivilegeTier.BASIC
    private var keyInterceptor: IKeyInterceptor? = null
    private var touchInjector: ITouchInjector? = null

    private var currentProfile: AppProfile = AppProfile.createDefaultProfile()
    private val rapidFireJobs = ConcurrentHashMap<String, Job>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true

        privilegeManager = PrivilegeManager.getInstance(this)
        profileRepository = ProfileRepository.getInstance(this)
        overlayManager = FloatingOverlayManager(this, profileRepository) { switchedProfile ->
            currentProfile = switchedProfile
            keyInterceptor?.updateMappings(switchedProfile.mappings)
            Log.i(TAG, "Profile manual switched via overlay to: ${switchedProfile.appName}")
        }
        appDetector = ForegroundAppDetector(this)

        KeepAliveHelper.createNotificationChannel(this)
        val notification = KeepAliveHelper.buildForegroundNotification(
            this,
            "肩键映射引擎正在初始化..."
        )
        startForeground(KeepAliveHelper.NOTIFICATION_ID, notification)

        serviceScope.launch {
            initEngine()
        }
    }

    private suspend fun initEngine() {
        currentTier = privilegeManager.getEffectiveTier()
        Log.i(TAG, "Initializing engine with Tier: $currentTier")

        // 1. 保活优化 (如果是 Root 则写入 oom_score_adj=-1000)
        KeepAliveHelper.optimizeKeepAliveForTier(this, currentTier)

        // 2. 初始化对应提权层级的触控注入器
        touchInjector = when (currentTier) {
            PrivilegeTier.ROOT -> RootTouchInjector(this)
            PrivilegeTier.ADB_SHIZUKU -> ShizukuTouchInjector()
            PrivilegeTier.BASIC -> AccessibilityTouchInjector()
        }

        // 3. 初始化对应提权层级的按键拦截器
        keyInterceptor = when (currentTier) {
            PrivilegeTier.ROOT -> RootKeyInterceptor()
            PrivilegeTier.ADB_SHIZUKU -> ShizukuKeyInterceptor()
            PrivilegeTier.BASIC -> BasicAccessibilityInterceptor()
        }

        // 4. 加载默认配置并显示屏幕悬浮窗
        currentProfile = profileRepository.getDefaultProfile()
        overlayManager.showOverlay(currentProfile)

        // 5. 启动按键监听
        startKeyInterception()

        // 6. 启动前台应用自动检测与配置切换
        startForegroundAppDetection()

        // 更新常驻通知内容
        val notification = KeepAliveHelper.buildForegroundNotification(
            this,
            "模式: ${currentTier.displayName} | 方案: ${currentProfile.appName}"
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        manager?.notify(KeepAliveHelper.NOTIFICATION_ID, notification)
    }

    private fun startKeyInterception() {
        keyInterceptor?.start(currentProfile.mappings) { mappingId, isDown ->
            handleTriggerEvent(mappingId, isDown)
        }
    }

    private fun startForegroundAppDetection() {
        appDetector.startListening(currentTier) { packageName ->
            switchProfileForPackage(packageName)
        }
    }

    /**
     * 自动为切换的前台应用加载对应配置
     */
    private fun switchProfileForPackage(packageName: String) {
        serviceScope.launch(Dispatchers.Main) {
            val newProfile = profileRepository.getProfileForPackage(packageName)
            currentProfile = newProfile
            Log.i(TAG, "Switched mapping profile to: ${newProfile.appName} (${newProfile.packageName})")

            // 停止上个配置的连点
            stopAllRapidFireJobs()

            // 更新悬浮窗与按键监听
            overlayManager.updateProfile(newProfile)
            keyInterceptor?.updateMappings(newProfile.mappings)

            // 更新通知状态
            val notification = KeepAliveHelper.buildForegroundNotification(
                this@MappingCoreService,
                "模式: ${currentTier.displayName} | 前台: ${newProfile.appName}"
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            manager?.notify(KeepAliveHelper.NOTIFICATION_ID, notification)
        }
    }

    /**
     * 处理肩键按下/抬起事件并执行屏幕映射
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::overlayManager.isInitialized) {
            overlayManager.showOverlay(currentProfile)
        }
        return START_STICKY
    }

    private fun handleTriggerEvent(mappingId: String, isDown: Boolean) {
        val mapping = currentProfile.mappings.find { it.id == mappingId && it.isEnabled } ?: return
        val metrics = overlayManager.getScreenMetrics()
        val targetX = mapping.xRatio * metrics.widthPixels
        val targetY = mapping.yRatio * metrics.heightPixels
        // 肩键分配高位 pointerId (8 和 9)，避免与屏幕物理手指 (0, 1, 2) 发生 ID 冲突导致摇杆断触
        val pointerId = if (mappingId.endsWith("_L", ignoreCase = true) || mappingId.contains("left", ignoreCase = true)) 8 else 9

        Log.i(TAG, "Trigger event: mappingId=$mappingId, isDown=$isDown -> pos=($targetX, $targetY), mode=${mapping.clickMode}")

        val injector = touchInjector ?: return

        when (mapping.clickMode) {
            ClickMode.SINGLE_TAP -> {
                if (isDown) {
                    injector.performClick(pointerId, targetX, targetY, 20L)
                }
            }
            ClickMode.PRESS_HOLD -> {
                if (isDown) {
                    injector.touchDown(pointerId, targetX, targetY)
                } else {
                    injector.touchUp(pointerId, targetX, targetY)
                }
            }
            ClickMode.RAPID_FIRE -> {
                if (isDown) {
                    startRapidFire(pointerId, mapping, targetX, targetY)
                } else {
                    stopRapidFire(mapping.id)
                }
            }
        }
    }

    private fun startRapidFire(pointerId: Int, mapping: KeyMapping, x: Float, y: Float) {
        stopRapidFire(mapping.id)
        val intervalMs = (1000L / mapping.rapidFireHz.coerceIn(2, 40))
        val job = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                touchInjector?.performClick(pointerId, x, y, 15L)
                delay(intervalMs)
            }
        }
        rapidFireJobs[mapping.id] = job
    }

    private fun stopRapidFire(mappingId: String) {
        rapidFireJobs.remove(mappingId)?.cancel()
    }

    private fun stopAllRapidFireJobs() {
        for (job in rapidFireJobs.values) {
            job.cancel()
        }
        rapidFireJobs.clear()
        touchInjector?.cancelAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopAllRapidFireJobs()
        touchInjector?.cancelAll()
        keyInterceptor?.stop()
        appDetector.stopListening()
        overlayManager.hideOverlay()
        (touchInjector as? RootTouchInjector)?.close()
        Log.i(TAG, "MappingCoreService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "MappingCoreService"

        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, MappingCoreService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MappingCoreService::class.java)
            context.stopService(intent)
        }
    }
}
