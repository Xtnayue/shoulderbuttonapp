package com.shoulderbutton.mapper.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.shoulderbutton.mapper.keepalive.KeepAliveHelper
import com.shoulderbutton.mapper.model.AppProfile
import com.shoulderbutton.mapper.model.ClickMode
import com.shoulderbutton.mapper.model.KeyMapping
import com.shoulderbutton.mapper.model.PrivilegeTier
import com.shoulderbutton.mapper.privilege.PrivilegeManager
import com.shoulderbutton.mapper.repository.ProfileRepository
import com.shoulderbutton.mapper.service.MappingCoreService
import com.shoulderbutton.mapper.service.ShoulderAccessibilityService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var privilegeManager: PrivilegeManager
    private lateinit var profileRepository: ProfileRepository

    private val keyLogs = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        privilegeManager = PrivilegeManager.getInstance(this)
        profileRepository = ProfileRepository.getInstance(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册临时按键调试监听
        ShoulderAccessibilityService.keyListener = { event ->
            val isDown = (event.action == KeyEvent.ACTION_DOWN)
            val actionStr = if (isDown) "按下 (DOWN)" else "抬起 (UP)"
            val log = "捕获按键: ${KeyEvent.keyCodeToString(event.keyCode)} ($actionStr, code=${event.keyCode})"
            keyLogs.add(0, log)
            if (keyLogs.size > 20) keyLogs.removeLast()
            false // 仅观察不拦截
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        keyLogs.add(0, "Activity按键: ${KeyEvent.keyCodeToString(keyCode)} (DOWN, code=$keyCode)")
        if (keyLogs.size > 20) keyLogs.removeLast()
        return super.onKeyDown(keyCode, event)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        var isServiceRunning by remember { mutableStateOf(MappingCoreService.isRunning) }
        var currentSelectedTier by remember { mutableStateOf(privilegeManager.selectedTier) }
        var isAutoTier by remember { mutableStateOf(privilegeManager.isAutoTier) }
        var effectiveTier by remember { mutableStateOf(PrivilegeTier.BASIC) }

        var isRootAvailable by remember { mutableStateOf(false) }
        var isShizukuAvailable by remember { mutableStateOf(false) }
        var isAccessibilityEnabled by remember { mutableStateOf(false) }
        var isOverlayGranted by remember { mutableStateOf(false) }

        var profiles by remember { mutableStateOf(profileRepository.getAllProfiles()) }
        var showAddAppDialog by remember { mutableStateOf(false) }
        var editingProfile by remember { mutableStateOf<AppProfile?>(null) }

        fun refreshStatus() {
            lifecycleScope.launch {
                isRootAvailable = privilegeManager.checkRootAvailable()
                isShizukuAvailable = privilegeManager.checkShizukuAvailable()
                isAccessibilityEnabled = privilegeManager.checkAccessibilityEnabled()
                isOverlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(this@MainActivity)
                } else true
                effectiveTier = privilegeManager.getEffectiveTier()
                profiles = profileRepository.getAllProfiles()
                isServiceRunning = MappingCoreService.isRunning
            }
        }

        LaunchedEffect(Unit) {
            refreshStatus()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("肩键触控映射器", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(
                                "生效层级: ${effectiveTier.displayName}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        // 服务启停主开关
                        FilledTonalButton(
                            onClick = {
                                if (isServiceRunning) {
                                    MappingCoreService.stop(this@MainActivity)
                                    isServiceRunning = false
                                } else {
                                    if (!isOverlayGranted) {
                                        Toast.makeText(this@MainActivity, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                                        requestOverlayPermission()
                                        return@FilledTonalButton
                                    }
                                    MappingCoreService.start(this@MainActivity)
                                    isServiceRunning = true
                                }
                            },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isServiceRunning) Color(0xFFE53935) else Color(0xFF43A047)
                            )
                        ) {
                            Text(if (isServiceRunning) "停止服务" else "启动映射", color = Color.White)
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 三套提权设置面板
                item {
                    PrivilegeTiersCard(
                        selectedTier = currentSelectedTier,
                        isAutoTier = isAutoTier,
                        effectiveTier = effectiveTier,
                        isRoot = isRootAvailable,
                        isShizuku = isShizukuAvailable,
                        isAccessibility = isAccessibilityEnabled,
                        isOverlay = isOverlayGranted,
                        onAutoTierChanged = {
                            isAutoTier = it
                            privilegeManager.isAutoTier = it
                            refreshStatus()
                        },
                        onTierSelected = {
                            currentSelectedTier = it
                            privilegeManager.selectedTier = it
                            refreshStatus()
                        },
                        onRequestOverlay = { requestOverlayPermission() },
                        onRequestAccessibility = { openAccessibilitySettings() },
                        onRequestShizuku = { privilegeManager.requestShizukuPermission(100) },
                        onRequestBattery = { KeepAliveHelper.requestIgnoreBatteryOptimizations(this@MainActivity) }
                    )
                }

                // 2. 前台多应用配置方案面板
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("多应用配置方案 (根据前台自动切换)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        IconButton(onClick = { showAddAppDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "添加游戏配置")
                        }
                    }
                }

                items(profiles) { profile ->
                    ProfileCard(
                        profile = profile,
                        onEdit = { editingProfile = profile },
                        onDelete = {
                            profileRepository.deleteProfile(profile.packageName)
                            refreshStatus()
                        }
                    )
                }

                // 3. 肩键调试实时日志卡片
                item {
                    KeyTesterCard(keyLogs = keyLogs, onClear = { keyLogs.clear() })
                }

                item {
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        // 添加游戏配置弹窗
        if (showAddAppDialog) {
            AddAppDialog(
                onDismiss = { showAddAppDialog = false },
                onAppSelected = { pkg, appName ->
                    val defaultProf = profileRepository.getDefaultProfile()
                    val newProf = AppProfile(
                        packageName = pkg,
                        appName = appName,
                        mappings = defaultProf.mappings.map { it.copy() }.toMutableList(),
                        isEnabled = true
                    )
                    profileRepository.saveProfile(newProf)
                    showAddAppDialog = false
                    refreshStatus()
                }
            )
        }

        // 编辑方案弹窗
        editingProfile?.let { profile ->
            EditProfileDialog(
                profile = profile,
                onDismiss = { editingProfile = null },
                onSave = { updated ->
                    profileRepository.saveProfile(updated)
                    editingProfile = null
                    refreshStatus()
                }
            )
        }
    }

    @Composable
    fun PrivilegeTiersCard(
        selectedTier: PrivilegeTier,
        isAutoTier: Boolean,
        effectiveTier: PrivilegeTier,
        isRoot: Boolean,
        isShizuku: Boolean,
        isAccessibility: Boolean,
        isOverlay: Boolean,
        onAutoTierChanged: (Boolean) -> Unit,
        onTierSelected: (PrivilegeTier) -> Unit,
        onRequestOverlay: () -> Unit,
        onRequestAccessibility: () -> Unit,
        onRequestShizuku: () -> Unit,
        onRequestBattery: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("三套提权模式设置", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动协商最高权限", fontSize = 12.sp)
                        Switch(
                            checked = isAutoTier,
                            onCheckedChange = onAutoTierChanged,
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 三个层级选择与状态
                PrivilegeTierItem(
                    tier = PrivilegeTier.BASIC,
                    isSelected = !isAutoTier && selectedTier == PrivilegeTier.BASIC,
                    isActive = effectiveTier == PrivilegeTier.BASIC,
                    statusText = if (isAccessibility) "无障碍就绪" else "需开启无障碍",
                    isReady = isAccessibility,
                    onClick = { onTierSelected(PrivilegeTier.BASIC) }
                )

                PrivilegeTierItem(
                    tier = PrivilegeTier.ADB_SHIZUKU,
                    isSelected = !isAutoTier && selectedTier == PrivilegeTier.ADB_SHIZUKU,
                    isActive = effectiveTier == PrivilegeTier.ADB_SHIZUKU,
                    statusText = if (isShizuku) "Shizuku已授权" else "Shizuku未连接",
                    isReady = isShizuku,
                    onClick = { onTierSelected(PrivilegeTier.ADB_SHIZUKU) }
                )

                PrivilegeTierItem(
                    tier = PrivilegeTier.ROOT,
                    isSelected = !isAutoTier && selectedTier == PrivilegeTier.ROOT,
                    isActive = effectiveTier == PrivilegeTier.ROOT,
                    statusText = if (isRoot) "Root可用 (oom_score_adj=-1000)" else "无Root权限",
                    isReady = isRoot,
                    onClick = { onTierSelected(PrivilegeTier.ROOT) }
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // 核心系统权限一键跳转
                Text("必要辅助权限:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRequestOverlay,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isOverlay) "悬浮窗: 已授权" else "开悬浮窗", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onRequestAccessibility,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isAccessibility) "无障碍: 已开启" else "开无障碍", fontSize = 12.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRequestShizuku,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isShizuku) "Shizuku: 已就绪" else "申请Shizuku", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onRequestBattery,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("电池优化白名单", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun PrivilegeTierItem(
        tier: PrivilegeTier,
        isSelected: Boolean,
        isActive: Boolean,
        statusText: String,
        isReady: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(
                    if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .clickable { onClick() }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = isSelected, onClick = onClick)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tier.displayName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(tier.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(8.dp))
            AssistChip(
                onClick = {},
                label = { Text(statusText, fontSize = 11.sp) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = if (isReady) Color(0xFF00E676) else Color(0xFFFFB74D)
                )
            )
        }
    }

    @Composable
    fun ProfileCard(
        profile: AppProfile,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(profile.appName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(profile.packageName, fontSize = 12.sp, color = Color.Gray)
                    }
                    Row {
                        IconButton(onClick = onEdit) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑映射配置")
                        }
                        if (profile.packageName != AppProfile.DEFAULT_PACKAGE) {
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Default.Delete, contentDescription = "删除配置", tint = Color.Red)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // 显示包含的按键映射标签
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profile.mappings.forEach { mapping ->
                        SuggestionChip(
                            onClick = onEdit,
                            label = {
                                Text("${mapping.label} -> (${(mapping.xRatio * 100).toInt()}%, ${(mapping.yRatio * 100).toInt()}%) [${mapping.clickMode.displayName}]", fontSize = 11.sp)
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun KeyTesterCard(keyLogs: List<String>, onClear: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("肩键触发实时测试器", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    TextButton(onClick = onClear) {
                        Text("清空")
                    }
                }
                Text("按下手机肩键 (F1/F2) 或物理按键，实时验证事件截获:", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (keyLogs.isEmpty()) {
                        Text(
                            "等待按键事件中... (可按音量+/-测试)",
                            color = Color.DarkGray,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(reverseLayout = false) {
                            items(keyLogs) { log ->
                                Text(log, color = Color(0xFF00FF66), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AddAppDialog(onDismiss: () -> Unit, onAppSelected: (pkg: String, name: String) -> Unit) {
        val pm = packageManager
        val installedApps = remember {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 } // 过滤第三方应用/游戏
                .map { appInfo ->
                    Pair(appInfo.packageName, pm.getApplicationLabel(appInfo).toString())
                }
        }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("选择要配置的游戏或应用", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(installedApps) { (pkg, name) ->
                            ListItem(
                                headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(pkg, fontSize = 11.sp) },
                                modifier = Modifier.clickable { onAppSelected(pkg, name) }
                            )
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text("取消")
                    }
                }
            }
        }
    }

    @Composable
    fun EditProfileDialog(
        profile: AppProfile,
        onDismiss: () -> Unit,
        onSave: (AppProfile) -> Unit
    ) {
        val workingCopy = remember { profile.copy(mappings = profile.mappings.map { it.copy() }.toMutableList()) }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("编辑配置: ${workingCopy.appName}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    workingCopy.mappings.forEachIndexed { index, mapping ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(mapping.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "节点: ${mapping.devicePath} | 键名: ${mapping.rawKeyName}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("物理按键:", fontSize = 12.sp)
                            FilterChip(
                                selected = mapping.rawKeyName == "KEY_F1",
                                onClick = {
                                    workingCopy.mappings[index] = mapping.copy(
                                        rawKeyName = "KEY_F1",
                                        rawEventCode = 59,
                                        targetKeyCode = KeyEvent.KEYCODE_F1,
                                        label = "左肩键 L (F1)"
                                    )
                                },
                                label = { Text("KEY_F1 (左肩键)", fontSize = 11.sp) }
                            )
                            FilterChip(
                                selected = mapping.rawKeyName == "KEY_F2",
                                onClick = {
                                    workingCopy.mappings[index] = mapping.copy(
                                        rawKeyName = "KEY_F2",
                                        rawEventCode = 60,
                                        targetKeyCode = KeyEvent.KEYCODE_F2,
                                        label = "右肩键 R (F2)"
                                    )
                                },
                                label = { Text("KEY_F2 (右肩键)", fontSize = 11.sp) }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("点击模式:", fontSize = 12.sp)
                            ClickMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = mapping.clickMode == mode,
                                    onClick = {
                                        workingCopy.mappings[index] = mapping.copy(clickMode = mode)
                                    },
                                    label = { Text(mode.displayName, fontSize = 11.sp) }
                                )
                            }
                        }
                        if (mapping.clickMode == ClickMode.RAPID_FIRE) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("连点频率: ${mapping.rapidFireHz}次/秒", fontSize = 12.sp)
                                Slider(
                                    value = mapping.rapidFireHz.toFloat(),
                                    onValueChange = {
                                        workingCopy.mappings[index] = mapping.copy(rapidFireHz = it.toInt())
                                    },
                                    valueRange = 5f..30f,
                                    steps = 25,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Button(onClick = { onSave(workingCopy) }) { Text("保存配置") }
                    }
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}

// 辅助 Modifier scale 扩展
private fun Modifier.scale(scale: Float): Modifier = this
