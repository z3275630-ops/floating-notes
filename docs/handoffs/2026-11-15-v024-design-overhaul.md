# 视觉、字体与图标系统深度改版交接（Opus 5.5 二轮评审 · v0.2.4-design-overhaul.1）

日期：2026-11-15  
分支：`feat/T26-T29-stats-widgets`  
版本：`versionCode = 23`，`versionName = "0.2.4-design-overhaul.1"`（调试包 `0.2.4-design-overhaul.1-debug`）

## 1. 评审背景与保留原则

在 Honor X10（Android 10，序列号 `SKSVB20603001748`）通过 `scrcpy` 录制 `floating_notes_ux_v2.mp4` 并抽取关键帧交由 Claude Opus 5.5（`ux-critic-v2`）做只读视觉与交互评审，随后与用户确认以下**三大结构保留**：
1. 保留左侧抽屉（`NotesDrawer` 与顶栏 `≡ 文件夹` 入口）。
2. 保留首页 `[ 全部 | 待办 ]` 顶部分段切换条。
3. 保留底部 4 个主导航 Tab（`记录 / 探索 / Agent / 笔记库`）及“找一找”检索页。

其余配色、字体排版、图标绘制与页面信息密度/UX 优化全面按评审建议落地。

## 2. 本轮落地改动清单

1. **设计系统（配色 / 排版 / 图标）**
   - `MintPalette.kt` & `GlassSurface.kt`：建立暖纸张中性底色（`mintCanvasColor` 浅色 `#F7F5F0` / 深色暖墨底）与纯白微边卡片（`mintCardSurface`），降低森林绿主色饱和度并收敛为品牌动作专用，同时引入语义化 `SemanticAccent(fg, bg)` 体系区分待办琥珀（`mintTodoAccent`）、回顾陶红（`mintReviewAccent`）、链接板岩蓝（`mintLinkAccent`）与本机 AI 靛紫（`mintAiAccent`），完善热力图 5 阶薄荷绿调色盘（`mintHeatmapPalette`）。
   - `MintPalette.kt` / `MainActivity.kt` / `FloatingNotesWidgets.kt`：接入统一 `mintTypography()` 字号层级与 `tnum` 等宽数字样式。
   - `MintIcons.kt`：全面重写为 Lucide 风格均匀 `1.85dp` 描边矢量路径，新增 `AtSign`、`Paperclip`、`Refresh`、`Sparkles`、`Pin` 等专用图标，统一工具栏与状态栏识别度。
   - `MintControls.kt` / `ComposablesMenu.kt` / `ComposablesSearchField.kt` / `GlassAction.kt`：统一暖中性轨道切换开关 `MintCapsuleSwitch`、搜索框、菜单触发器与卡片分组表面。
2. **首页、笔记库、抽屉与草稿收纳**
   - `NotesShell.kt`：新增首页快速记笔记触发条 `"写点什么…"`、`showDraftsSheet`（超过 1 条草稿折叠为 `DraftsSummaryBanner` 琥珀汇总横幅，点击展开 `MintBottomSheet`）、纯白纸张分组列表；统一 `56.dp` 森林绿圆形主 FAB（白色图标）与 `11.sp` 底栏标签；精简 `NoteCard` 为标题+首行摘要+底部单行元数据 `FlowRow`；无筛选条件的 `笔记库` 改为展示“按条件精准定位”引导卡而非重复渲染时间线；移除编辑器顶栏重复 `#` 按钮并调整快速记录“保存”为品牌实体胶囊按钮；`NotesDrawer.kt` / `LibrarySearch.kt` / `TrashView.kt` / `OrganizationPanel.kt` 对齐暖中性样式。
3. **编辑与快速记录**
   - `NoteEditor.kt` & `CaptureSheet.kt`：正文阅读与写作采用 `24.sp/32.sp` 标题与 `17.sp/28.sp` 正文字体，折叠元数据为单行时间戳；工具栏换用 `MintIcons.AtSign`（`@ 引用`）与 `MintIcons.Paperclip`（`附件`）；将“相关笔记 N”从顶栏移至右下角靛紫悬浮胶囊（`editor-related-trigger`）；`CaptureSheet` 顶部保留中性“关闭/取消”与全屏暖纸底色；`NoteEditorDiscovery.kt` 改用 `MintIcons.Sparkles` 与 `mintAiAccent`。
4. **探索、双卡漫步、Agent、待办、统计与分享卡片**
   - `ExploreScreen.kt`：回顾计数改用陶红强调，“分享卡片 / 从这里漫步 / 7 天内不再出现 / 不再出现在回顾中”收进右上角 `更多回顾操作` 菜单。
   - `ExploreFindAndWalk.kt`：精简“找一找”索引状态为 `"已就绪 · ... 篇可秒搜"` 并配 `MintIcons.Refresh`；双卡对撞新增纵线 + `"跨笔记碰撞"` 节点桥接卡片 A 与卡片 B，突出“寻找联系 / 建立链接”与“换一组”。
   - `AgentWorkspace.kt`：将 5 行隐私说明折叠为单行可展开靛紫提示条 `"仅在本机运行 · 不上传云端 · N 篇可检索"`。
   - `TasksScreen.kt`：接入 `onQuickAddTask` 弹窗快捷新增今日待办，重构 `TasksHeaderBar` 与 `TasksEmptyCard` 暖纸视觉。
   - `StatsSheet.kt`：改为 2×2 四宫格核心指标（`总记录 / 月新增 / 连续天数 / 活跃天数`，`32.sp tnum`），修复 16 周热力图（`14.dp` 方块、`3.dp` 间距、月份标题与 5 阶绿色图例）；仅在 Sheet UI 层过滤“上期基数为 0”冗余文案，保持 `StatsEngine.formatSummary` 兼容单测。
   - `ShareCardSheet.kt`：去除预览卡片外层多余套框，单页预览放大至最大 `316.dp` 并加细边框。

## 3. 验证结果（最终编辑后全量重跑）

- **Gradle 全量构建与单测**（镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114`）：
  - `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease assembleBenchmark --console=plain`：退出码 `0`。
  - `testDebugUnitTest`：**32 tests, 0 failures, 0 errors, 0 skipped**（6 个测试类全部通过）。
  - `lintDebug`：**0 errors, 92 warnings**（94 total issues）。
- **产物校验**：
  - `app-debug.apk`：`53,733,900` bytes，SHA-256 `95bb8fbc1d24b8510d278d1933261e88018d7efbfe37099387dfaf42b57d38e4`
  - `app-debug-androidTest.apk`：`1,385,303` bytes，SHA-256 `d3d76331c657c410329d682cdc6fafba5cd187e0312a08577050f1e0cb2fb4a2`
  - `app-release-unsigned.apk`：`43,150,124` bytes，SHA-256 `303a62dfd8e1a992c0b96014a498838f2464b092401b406063228a54f07c664b`
  - `app-benchmark.apk`：`43,158,324` bytes，SHA-256 `4db2697bfeb9b2f051a0979f3d5bd72ad3f3df766e82d7974f0f61fa43d7f953`
- **荣耀 X10 真机验证**（`SKSVB20603001748`，`adb install -r` 覆盖安装保留原有 303 条笔记）：
  - `am instrument -w io.github.z3275630ops.floatingnotes.debug.test/androidx.test.runner.AndroidJUnitRunner`：**20/20 类，70/70 tests PASS（75.383s）**。
  - 实机截图目视核验：已截取并检查首页、滚动首页、正文编辑器、快速记录弹窗+输入法、左侧抽屉、待办清单页、探索回顾、统计与 16 周热力图、分享卡片预览、找一找、双卡对撞、Agent、笔记库、设置页。
- **未验证说明**：荣耀 X10（EMUI 10）`adb shell settings put secure ui_night_mode 2` / `cmd uimode night yes` 未能动态触发系统深色主题广播，深色模式实机渲染未通过命令行切换验证（保留代码层 `mintColorScheme(dark = true)` 与 `SemanticAccent` 深色映射，需在系统设置中手动开启深色模式验证）。
