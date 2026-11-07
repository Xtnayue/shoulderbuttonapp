package com.shoulderbutton.mapper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.shoulderbutton.mapper.model.AppProfile
import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.repository.ProfileRepository

class FloatingOverlayManager(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val onProfileSwitched: ((newProfile: AppProfile) -> Unit)? = null
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isEditMode = false
    private var isUsingAppSpecific = true // 是否使用当前应用的独立配置 (否则为通用配置)
    private var currentForegroundPackage = AppProfile.DEFAULT_PACKAGE
    private var currentProfile: AppProfile = profileRepository.getDefaultProfile()

    // 映射点 View 与对应布局参数
    private val mappingViews = mutableMapOf<String, View>()
    private val mappingLayoutParams = mutableMapOf<String, WindowManager.LayoutParams>()

    // 悬浮工具栏
    private var controlBarView: View? = null
    private var controlBarTitleView: TextView? = null
    private var profileSwitchBtn: TextView? = null

    // 边缘收起悬浮小球
    private var miniBubbleView: View? = null

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 外部启动/显示悬浮窗
     */
    fun showOverlay(profile: AppProfile) {
        if (!checkOverlayPermission()) return
        runOnMainThread {
            this.currentProfile = profile
            this.currentForegroundPackage = profile.packageName
            this.isUsingAppSpecific = (profile.packageName != AppProfile.DEFAULT_PACKAGE)

            removeMiniBubble()

            // 如果控制栏已存在，直接更新内容，避免重复添加引发双浮窗
            if (controlBarView != null) {
                updateControlBarTitle()
                updateProfileSwitchBtnText()
                return@runOnMainThread
            }

            hideOverlayInternal()
            createControlBar()
            createMappingPointViews()
        }
    }

    /**
     * 前台应用切换时更新
     */
    fun updateProfile(newProfile: AppProfile) {
        runOnMainThread {
            this.currentForegroundPackage = newProfile.packageName
            // 如果用户选择使用通用配置，则优先加载通用配置
            val targetProfile = if (!isUsingAppSpecific) {
                profileRepository.getDefaultProfile()
            } else {
                newProfile
            }
            this.currentProfile = targetProfile

            updateControlBarTitle()
            updateProfileSwitchBtnText()

            // 如果控制栏处于显示状态，重新刷新映射点
            if (controlBarView != null && controlBarView?.isAttachedToWindow == true) {
                removeMappingPointViews()
                createMappingPointViews()
            }
        }
    }

    fun hideOverlay() {
        runOnMainThread {
            hideOverlayInternal()
        }
    }

    private fun hideOverlayInternal() {
        removeMappingPointViews()
        removeControlBar()
    }

    /**
     * 创建控制栏
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createControlBar() {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6212121"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#448AFF"))
            }
            setPadding(16, 8, 16, 8)
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. 标题 (显示应用名)
        val titleTv = TextView(context).apply {
            text = "🎮 ${currentProfile.appName}"
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = 240
            setPadding(0, 0, 8, 0)
        }
        controlBarTitleView = titleTv
        layout.addView(titleTv)

        // 2. 配置切换按钮 (通用配置 / 独立配置)
        val switchBtn = TextView(context).apply {
            text = if (isUsingAppSpecific) "📌 独立" else "🌐 通用"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(if (isUsingAppSpecific) Color.parseColor("#7C4DFF") else Color.parseColor("#00B0FF"))
                cornerRadius = 12f
            }
            setPadding(14, 8, 14, 8)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                isUsingAppSpecific = !isUsingAppSpecific
                updateProfileSwitchBtnText()
                val target = if (isUsingAppSpecific) {
                    profileRepository.getProfileForPackage(currentForegroundPackage)
                } else {
                    profileRepository.getDefaultProfile()
                }
                currentProfile = target
                updateControlBarTitle()
                removeMappingPointViews()
                createMappingPointViews()
                onProfileSwitched?.invoke(target)
                Toast.makeText(
                    context,
                    if (isUsingAppSpecific) "已切换为【${target.appName}】独立配置" else "已切换为【通用全局配置】",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        profileSwitchBtn = switchBtn
        layout.addView(switchBtn)

        val spacer1 = View(context).apply { layoutParams = LinearLayout.LayoutParams(8, 1) }
        layout.addView(spacer1)

        // 3. 复位按钮 (将 L 和 R 圆圈复位到默认两侧位置)
        val resetBtn = TextView(context).apply {
            text = "🔄 复位"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF6D00"))
                cornerRadius = 12f
            }
            setPadding(14, 8, 14, 8)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                resetMappingPositions()
                Toast.makeText(context, "已复位 L/R 映射圈至默认位置", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(resetBtn)

        val spacer2 = View(context).apply { layoutParams = LinearLayout.LayoutParams(8, 1) }
        layout.addView(spacer2)

        // 4. 编辑 / 锁定模式按钮
        val modeBtn = TextView(context).apply {
            text = if (isEditMode) "🔒 锁定" else "✏️ 编辑"
            textSize = 11f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(if (isEditMode) Color.parseColor("#00E676") else Color.parseColor("#FFD600"))
                cornerRadius = 12f
            }
            setPadding(14, 8, 14, 8)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                isEditMode = !isEditMode
                text = if (isEditMode) "🔒 锁定" else "✏️ 编辑"
                background = GradientDrawable().apply {
                    setColor(if (isEditMode) Color.parseColor("#00E676") else Color.parseColor("#FFD600"))
                    cornerRadius = 12f
                }
                toggleEditMode(isEditMode)
                Toast.makeText(
                    context,
                    if (isEditMode) "已进入编辑定位模式：拖动圆圈至游戏按键处" else "已锁定：映射生效中",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(modeBtn)

        val spacer3 = View(context).apply { layoutParams = LinearLayout.LayoutParams(8, 1) }
        layout.addView(spacer3)

        // 5. 保存并隐藏悬浮窗按钮
        val saveBtn = TextView(context).apply {
            text = "💾 保存"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2979FF"))
                cornerRadius = 12f
            }
            setPadding(16, 8, 16, 8)
            isClickable = true
            isFocusable = false
            setOnClickListener {
                profileRepository.saveProfile(currentProfile)
                // 保存后收起并隐藏悬浮窗与圆圈，展示边缘迷你小球
                collapseToMiniBubble()
                Toast.makeText(
                    context,
                    "已保存配置！悬浮窗已收起 (点击边缘小球或下拉快捷开关随时展开)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        layout.addView(saveBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 35
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setupDraggable(layout, params)

        try {
            windowManager.addView(layout, params)
            controlBarView = layout
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add controlBarView to windowManager", e)
        }
    }

    private fun updateControlBarTitle() {
        controlBarTitleView?.text = "🎮 ${currentProfile.appName}"
    }

    private fun updateProfileSwitchBtnText() {
        profileSwitchBtn?.let { btn ->
            btn.text = if (isUsingAppSpecific) "📌 独立" else "🌐 通用"
            btn.background = GradientDrawable().apply {
                setColor(if (isUsingAppSpecific) Color.parseColor("#7C4DFF") else Color.parseColor("#00B0FF"))
                cornerRadius = 12f
            }
        }
    }

    /**
     * 复位映射点位置到默认值 (L: 15%, 35%; R: 85%, 35%)
     */
    private fun resetMappingPositions() {
        val metrics = getScreenMetrics()
        for (mapping in currentProfile.mappings) {
            if (mapping.id.endsWith("_L", ignoreCase = true) || mapping.id.contains("left", ignoreCase = true)) {
                mapping.xRatio = 0.15f
                mapping.yRatio = 0.35f
            } else if (mapping.id.endsWith("_R", ignoreCase = true) || mapping.id.contains("right", ignoreCase = true)) {
                mapping.xRatio = 0.85f
                mapping.yRatio = 0.35f
            }

            val view = mappingViews[mapping.id] ?: continue
            val params = mappingLayoutParams[mapping.id] ?: continue
            val sizePx = 130
            val absX = (mapping.xRatio * metrics.widthPixels).toInt()
            val absY = (mapping.yRatio * metrics.heightPixels).toInt()

            params.x = (absX - sizePx / 2).coerceAtLeast(0)
            params.y = (absY - sizePx / 2).coerceAtLeast(0)

            try {
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, params)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reposition mapping view ${mapping.id}", e)
            }
        }
    }

    /**
     * 点击保存后，收起控制栏与圆圈，在屏幕边缘显示极简小球
     */
    private fun collapseToMiniBubble() {
        hideOverlayInternal()
        createMiniBubble()
    }

    /**
     * 边缘迷你快捷小球 (44x44dp，带🎮图标，可拖动贴边，点击展开完整面板)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createMiniBubble() {
        removeMiniBubble()

        val sizePx = 110
        val bubble = TextView(context).apply {
            text = "🎮"
            textSize = 20f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D9212121"))
                setStroke(3, Color.parseColor("#00E676"))
            }
            alpha = 0.75f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        var isDragging = false
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    try {
                        if (bubble.isAttachedToWindow) {
                            windowManager.updateViewLayout(bubble, params)
                        }
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // 点击小球展开完整面板与圆圈
                        removeMiniBubble()
                        createControlBar()
                        createMappingPointViews()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(bubble, params)
            miniBubbleView = bubble
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add miniBubbleView", e)
        }
    }

    private fun removeMiniBubble() {
        miniBubbleView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        miniBubbleView = null
    }

    /**
     * 为当前 Profile 的每个映射创建屏幕悬浮点
     */
    private fun createMappingPointViews() {
        val metrics = getScreenMetrics()
        for (mapping in currentProfile.mappings) {
            val pointView = createSingleMappingPointView(mapping, metrics)
            mappingViews[mapping.id] = pointView
        }
    }

    private fun createSingleMappingPointView(mapping: KeyMapping, metrics: DisplayMetrics): View {
        val sizePx = 130
        val circleView = TextView(context).apply {
            text = mapping.label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            alpha = if (isEditMode) 0.90f else 0.65f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val colorHex = if (mapping.id.endsWith("_L", ignoreCase = true) || mapping.id.contains("left", ignoreCase = true)) "#E6E91E63" else "#E600BCD4"
                setColor(Color.parseColor(colorHex))
                setStroke(4, Color.WHITE)
            }
        }

        val absX = (mapping.xRatio * metrics.widthPixels).toInt()
        val absY = (mapping.yRatio * metrics.heightPixels).toInt()

        val flags = if (isEditMode) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (absX - sizePx / 2).coerceAtLeast(0)
            y = (absY - sizePx / 2).coerceAtLeast(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        mappingLayoutParams[mapping.id] = params
        setupMappingDraggable(circleView, params, mapping)

        try {
            windowManager.addView(circleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add mapping point view for ${mapping.id}", e)
        }

        return circleView
    }

    private fun toggleEditMode(editMode: Boolean) {
        runOnMainThread {
            for ((id, view) in mappingViews) {
                val mapping = currentProfile.mappings.find { it.id == id } ?: continue
                val params = (view.layoutParams as? WindowManager.LayoutParams) ?: mappingLayoutParams[id] ?: continue

                if (editMode) {
                    params.flags = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    view.alpha = 0.90f
                } else {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    view.alpha = 0.65f
                }

                try {
                    if (view.isAttachedToWindow) {
                        windowManager.updateViewLayout(view, params)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to updateViewLayout for $id in toggleEditMode", e)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMappingDraggable(view: View, params: WindowManager.LayoutParams, mapping: KeyMapping) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            if (!isEditMode) return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    params.x = initialX + dx
                    params.y = initialY + dy

                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.updateViewLayout(view, params)
                        }
                    } catch (_: Exception) {}

                    val metrics = getScreenMetrics()
                    val centerX = (params.x + view.width / 2f).coerceIn(0f, metrics.widthPixels.toFloat())
                    val centerY = (params.y + view.height / 2f).coerceIn(0f, metrics.heightPixels.toFloat())
                    mapping.xRatio = centerX / metrics.widthPixels
                    mapping.yRatio = centerY / metrics.heightPixels
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggable(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        if (view.isAttachedToWindow) {
                            windowManager.updateViewLayout(view, params)
                        }
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun removeMappingPointViews() {
        for ((_, view) in mappingViews) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        mappingViews.clear()
        mappingLayoutParams.clear()
    }

    private fun removeControlBar() {
        controlBarView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
        controlBarView = null
        controlBarTitleView = null
        profileSwitchBtn = null
    }

    fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        var w = 0
        var h = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
            metrics.density = context.resources.displayMetrics.density
            metrics.densityDpi = context.resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            w = metrics.widthPixels
            h = metrics.heightPixels
        }

        // 根据当前屏幕横竖屏配置确保宽高朝向正确
        val orientation = context.resources.configuration.orientation
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            metrics.widthPixels = Math.max(w, h)
            metrics.heightPixels = Math.min(w, h)
        } else if (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            metrics.widthPixels = Math.min(w, h)
            metrics.heightPixels = Math.max(w, h)
        } else {
            metrics.widthPixels = w
            metrics.heightPixels = h
        }

        return metrics
    }

    companion object {
        private const val TAG = "FloatingOverlayManager"
    }
}
