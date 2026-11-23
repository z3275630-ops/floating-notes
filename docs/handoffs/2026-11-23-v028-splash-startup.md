# 启动页视觉统一与冷启动加速交接（v0.2.8-splash-startup.1）

日期：2026-11-23  
分支：`main`  
版本：`versionCode = 28`，`versionName = "0.2.8-splash-startup.1"`（调试包 `0.2.8-splash-startup.1-debug`）

## 1. 目标与约束

- 修复此前冷启动画面大面积纯薄荷色（`#BFEBD4` / `#1E4538`）直接闪切换到暖纸白（`#F6F4EF`）或深炭灰（`#121211`）主界面的视觉割裂问题，补充浅色/深色与 Android 12+（API 31+）一致的品牌启动页。
- 优化首帧构建与冷启动耗时，同时配套 R8 + ART Baseline/Startup Profile 规则加速正式包冷启动。
- 严格保持 Room `SCHEMA_VERSION = 7` 不变，不做 `abiFilters` 裁剪；荣耀 X10 全程仅通过 `adb install -r` 覆盖安装，完整保留设备上的现有笔记数据。

## 2. 根因分析与改动内容

1. **无缝品牌启动页改造（`res/values*`、`res/drawable/`、`AndroidManifest.xml`、`MainActivity.kt`）**
   - 根因定位：`themes.xml` 原本仅配置了纯薄荷色 `@color/window_mint` 作为窗口背景，既无居中品牌徽标，也缺少 `values-night/themes.xml`（深色模式状态栏图标反差异常）与 `values-v31/themes.xml`（Android 12+ SplashScreen API 属性）。
   - 底色统一：将 `app/src/main/res/values/colors.xml` 中 `window_canvas` 与 `window_mint` 统一为 `#F6F4EF`，将 `app/src/main/res/values-night/colors.xml` 中两者统一为 `#121211`，使系统启动窗口与 Compose 首帧画布无缝衔接。
   - 启动画层与主题切换：新增 `app/src/main/res/drawable/launch_splash.xml`（`layer-list`，`android:opacity="opaque"`，底层 `@color/window_canvas` 叠加居中 `210dp×210dp` 的 `@mipmap/ic_launcher_foreground`）；更新 `app/src/main/res/values/themes.xml` 与新增 `app/src/main/res/values-night/themes.xml`（定义 API 26 兼容的 `Theme.FloatingNotes` 与 `Theme.FloatingNotes.Starting`，透明状态栏/导航栏并分别设置 `windowLightStatusBar` 为 `true` / `false`）；新增 `app/src/main/res/values-v31/themes.xml` 配置 `windowSplashScreenBackground`、`windowSplashScreenAnimatedIcon` 与透明图标背景。
   - 入口衔接：`app/src/main/AndroidManifest.xml` 中 `MainActivity` 使用 `android:theme="@style/Theme.FloatingNotes.Starting"`，`MainActivity.kt` 在 `super.onCreate(savedInstanceState)` 之前调用 `setTheme(R.style.Theme_FloatingNotes)`。

2. **首帧减负、延迟非首屏任务与 ART Profile（`GlassSurface.kt`、`NotesShell.kt`、`NotesViewModel.kt`、`baseline-prof.txt`、`startup-prof.txt`、`app/build.gradle.kts`）**
   - 避免低版本冗余离屏渲染：`GlassSurface.kt`（`Modifier.glassSource`）在 `Build.VERSION.SDK_INT < 31` 设备（如荣耀 X10 Android 10）跳过离屏 `GraphicsLayer` 分配与双遍绘制。
   - 收敛 Compose 首帧计算与订阅：`NotesShell.kt` 将 `derivedTodoNoteIds` 与 `derivedPendingTodosCount` 移入懒求值 `todoFilterNoteIds ?: remember(notes)` / `pendingTasksCount ?: remember(notes)`，当 `MainActivity` 已传入时避免对全部笔记重复 `NoteDocument.decode`；将 `model.trashedNotes` 订阅收敛到 `showTrash || showStatsSheet`，将 `organization.links` 收敛到 `page == Page.Explore || model.editor != null`；对 `tagScope`、`tagMatches`、`notebookMatches`、`archivedFiltered`、`recordNotes` 补充 `remember` 缓存；保持 `NotesDrawer` 直接挂载在 `ModalNavigationDrawer` 内（因 6 项现有基准 UI 测试会在抽屉关闭时直接断言 `notes-drawer-list`）；新增 `LaunchedEffect(page)` 在切入 `Page.Agent` 时确保触发 `model.ensureAgentRunsObserved()`。
   - 延后非首帧数据库与仓库初始化：`NotesViewModel.kt` 将 `backupManager`、`search`、`widgetPreferences`、`discovery`、`agentRepository` 改为 `by lazy(LazyThreadSafetyMode.SYNCHRONIZED)`；`trashedCount` 改为基于仅查 ID 的 `trashedNoteIds.map { it.size }`；将 `init` 中的 `ensureAgentRunsObserved()` 延后 `1_200L` ms（同时在 `executeAgentRun`、`loadPastAgentRun` 与 `Page.Agent` 保留即时触发）；将首次 `onForeground()` 的 `reconcileTimeChange()` 与 `purgeExpiredTrashIfNeeded()` 延后 `650L` ms 在 `Dispatchers.IO` 执行。
   - 新增 ART 启动/基准配置文件：新增 `app/src/main/baseline-prof.txt`（14 条 `HSPL...**->**(**)**` 规则）与 `app/src/main/startup-prof.txt`（12 条规则），并确认产物合并写入 `mergeReleaseArtProfile/baseline-prof.txt`；`app/build.gradle.kts` 升级至 `versionCode = 28`、`versionName = "0.2.8-splash-startup.1"`。

## 3. 验证结果

- **Gradle 全量构建、单测与 Lint**（镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114`，离线复跑全部任务 exit code `0`）：
  - `testDebugUnitTest`：**10 个测试类，62/62 tests PASS（total=62, failures=0, errors=0, skipped=0）**。
  - `lintDebug`：**0 errors（75 warnings, 2 informational）**。
- **正式包签名核验（`apksigner`）**：
  - `apksigner verify --verbose --print-certs app-release.apk`：**Verifies**（v2=`true`、v3=`true`，`Signer #1 certificate SHA-256 digest: 3ba971957d44d73d7ef4ae3c7fc7e168119dc11385a00e2257cd6cdfc6718195`）。
- **构建产物校验（字节大小与 SHA-256）**：
  - `release/app-release.apk`：`43,496,752` bytes（`41.48 MiB`），SHA-256 `9a85d6aea862280a5a38e47069e1559e37ba41a5106735bfadcc5ad4c8157787`
  - `debug/app-debug.apk`：`54,714,098` bytes（`52.18 MiB`），SHA-256 `9bec739ac887bb42bb862eabeca9d61b845c468f87318fe3dd9e7b3dc07399e8`
  - `benchmark/app-benchmark.apk`：`43,479,832` bytes（`41.47 MiB`），SHA-256 `c01571e335033a3452c137d8218b10e2b46e1eabd6c08745b4962bb1451aecf0`
  - `androidTest/debug/app-debug-androidTest.apk`：`1,473,676` bytes（`1.41 MiB`），SHA-256 `35bfb3dae1eef9b8ff75df6ee6c4ff258f7cec3ffb7bf911a228d534871d7b73`
- **荣耀 X10 真机实测与验收**（`SKSVB20603001748`，`adb install -r` 覆盖安装保留原笔记，`versionCode=28`）：
  - 启动页视觉验收：真机实机截图确认启动界面呈现居中双卡片徽标配暖白纸色 `#F6F4EF` 背景，与主界面底色无缝衔接。
  - 冷启动耗时实测（`am start -W -S`）：
    - R8 + ART Baseline/Startup Profile 正式等价包（`app-benchmark.apk`，测试后已干净卸载）：`TotalTime: 641 / 468 / 468` ms（`WaitTime: 644 / 471 / 470` ms），稳态冷启动约 `0.47s`。
    - 调试包（`app-debug.apk`，`install -r` 后实测，含未压缩字节码、调试器与 Compose 工具链开销）：`TotalTime: 1904 / 1730 / 1714` ms（`WaitTime: 1907 / 1732 / 1716` ms）；改前调试包基线为 `TotalTime: 1567 / 1545 / 1557` ms（`WaitTime: 1571 / 1547 / 1559` ms）。由于调试包（`*.debug`）与正式签名包（`io.github.z3275630ops.floatingnotes`）应用 ID 独立，若用户希望在主力机获得约 `0.47s` 的正式版冷启动体验，可通过备份/恢复 ZIP 迁移后安装正式包 `app-release.apk`。
  - 真机全量 UI 测试：`am instrument -w io.github.z3275630ops.floatingnotes.debug.test/androidx.test.runner.AndroidJUnitRunner`：**23/23 类，87/87 tests PASS（耗时 81.037s）**，用户现有笔记保持完整。
- **发布状态**：
  - 已提交至 `main` 分支、打标签并发布：[v0.2.8-splash-startup.1](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.2.8-splash-startup.1)。
