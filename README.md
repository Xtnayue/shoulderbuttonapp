# ShoulderButtonMapper (安卓物理肩键屏幕映射器)

一款面向具备物理肩键/升降键安卓手机（如 Redmi K40 Gaming / POCO F3 GT 等）的专业屏幕按键映射工具。

支持将手机物理肩键（F1/F2等）拦截并极速映射到屏幕指定坐标的点击、长按或高频连点，专为《三角洲行动》、《和平精英》、《使命召唤手游》等射击游戏设计。

---

## 🌟 核心特性与架构

### 1. 三套权限与提权层级 (3 Privilege Tiers)
- **Root (底层驱动级直写)**：
  - 针对 KernelSU / Magisk 环境开发；
  - 内置 NDK 编译的高性能 `evdev_injector` 原生 C 守护进程，直接向触摸屏硬件节点（`/dev/input/event4`，`fts_ts`）写入 Linux Multi-Touch Type B 数据包；
  - **实现芯片级多点并存（Slot 0 与 Slot 8/9 并行），彻底根除开镜/开火时左手摇杆断触、瞬移问题**；
  - 自动向 `/proc/$PID/oom_score_adj` 写入 `-1000`，实现系统最高级别真后台保活。
- **ADB / Shizuku (特权服务级)**：
  - 免 Root 方案，通过 Shizuku 绑定系统级 `IInputManager` Binder；
  - 完整实现 Android Multi-Touch 状态机（`ACTION_DOWN`、`ACTION_POINTER_DOWN`、`ACTION_POINTER_UP`、`ACTION_UP`），支持双肩键同时按下与连点。
- **Basic (基础无障碍级)**：
  - 免 Root、免 ADB，基于系统 `AccessibilityService` 捕获基础按键，通过 `dispatchGesture` 进行模拟。

### 2. 悬浮窗配置与游戏按键定位
- **实时悬浮控制栏**：
  - 紧凑型胶囊设计，支持在游戏界面内直接进入定位编辑或锁定模式；
  - 屏幕显示 **L**（左肩键）与 **R**（右肩键）拖动圆圈，直接对齐游戏内的开镜、开火、跳跃等按键；
  - 点击“💾 保存”后，悬浮窗自动折叠为边缘极简小球（🎮），不遮挡游戏视野；
  - 提供“🔄 复位”按钮，一键还原圆圈至双侧默认位置。

### 3. 多应用独立方案自动切换
- 支持全局通用方案与应用独立定制方案（例如为《三角洲行动》单独设定开镜按住与开火模式）；
- `ForegroundAppDetector` 智能监测当前前台运行的游戏，毫秒级自动切换对应的按键布局与点击模式。

### 4. 丰富点击模式
- **单次点击 (SINGLE_TAP)**
- **按住持续触摸 (PRESS_HOLD)**
- **高速连点 (RAPID_FIRE)**：支持 5Hz ~ 40Hz 自定义连点频率。

---

## 🛠️ 项目技术栈
- **语言**：Kotlin + C (Linux input subsystem)
- **编译工具链**：Gradle 8.5.0 + Android NDK r30 (Clang AArch64)
- **目标 SDK**：Android 15/16 (API 35+)
- **系统底层机制**：Linux evdev / Multi-Touch Protocol Type B / Shizuku API / IInputManager Binder 反射

---

## 🚀 编译与构建

```bash
# 编译原生 evdev 注入守护进程 (可选，已内置编译产物至 assets)
$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang -O3 -pie app/src/main/cpp/evdev_injector.c -o app/src/main/assets/evdev_injector
$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip app/src/main/assets/evdev_injector

# 编译 APK
./gradlew assembleDebug

# 安装到目标设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License
MIT License
