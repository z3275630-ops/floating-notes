# T26 / T29 统计热力图、分享卡片与桌面小组件交接说明

- 日期：2026-11-15
- 分支：`feat/T26-T29-stats-widgets`（基于 `feat/T37-ux-agent-m3` 提交 `efb75e5` 新建）
- 版本：`versionCode = 21`，`versionName = "0.2.2-stats-widget.1"`（调试包 `0.2.2-stats-widget.1-debug`）
- 目标设备：荣耀 X10（`SKSVB20603001748`，Android 10，未开启实时背景模糊）
- 数据库：`SCHEMA_VERSION = 7`（沿用 M3 结构，本轮无破坏性迁移）

## 1. 本轮完成内容

### 1.1 T26：本地记录统计与热力图（F13 / S17 / Q038–Q040）
- `data/stats/StatsEngine.kt`：
  - 新增 `StatsOriginScope`（`Manual` 默认、`All`、`Imported`、`AiGenerated`），严格将个人手写与随记同外部导入、Agent/双卡自动产物分离，不伪造个人写作节奏（Q039）。
  - 计算 `StatsSnapshot`：活跃天数、温和连续天数提示（无压力式惩罚）、当前月份对照（当上期基数为 0 时 `deltaPercent = null`，仅展示绝对数量，不造假百分比 · Q040）、近 16 周热力图格网与月度日历、`dayNotesByDate` 每日笔记下钻列表（与格子数字同口径并剔除 `deletedAt != null` · Q038）、写作时段偏好柱状图与热门标签统计。
  - 支持任意 `ZoneId`（系统时区 / UTC 切换重算 · Q040）。
- `feature/stats/StatsSheet.kt`：
  - 以 Mint Glass `MintBottomSheet` 样式构建统计面板，采用 `FlowRow` 展示 4 类来源筛选标签、分段切换「热力节奏 / 月度日历」、可点击日期格子下钻查看并跳回原笔记、点击标签直达笔记库筛选。
- 入口接入：
  - `ExploreScreen.kt` 顶部新增「统计与热力图」胶囊入口（`explore-open-stats`，保证始终位于悬浮底栏上方一触即达），底部保留知识脉络概览卡片。
  - `NotesDrawer.kt` 抽屉新增「记录统计与热力图」（`drawer-stats`）入口。

### 1.2 T29：本地长文分页分享卡片与隐私控制（F18 / S19 / Q055–Q056）
- `feature/share/ShareCardRenderer.kt`：
  - `ShareCardPaginator` 采用纯 Kotlin `MiniJson` 解析笔记块结构（保证 JVM 与 Android 行为一致），支持长文智能分页，统计并诚实提示暂不内嵌的音频/视频附件数。
  - 提供 `ShareCardOptions`：默认关闭 `includeSourceMetadata`（不泄露原出处 URL / 内部来源标记 · Q056），可自由开关标题、记录时间与标签。
  - `ShareCardRenderer` 本地渲染 3 套视觉模板（`玻璃渐变 Glass` / `纯净简洁 Minimal` / `暖白纸感 Paper`）1080px PNG 至 `cacheDir/share_cards/`，经 `FileProvider`（`${packageName}.attachments`，已配置 `res/xml/attachment_paths.xml`）生成单页或 `ACTION_SEND_MULTIPLE` 多页分享 URI。
- `feature/share/ShareCardSheet.kt`：
  - 预览分页切换、附件与分页提示、样式芯片、隐私开关；通过 `ActivityResultContracts.StartActivityForResult` 调起系统分享选择器，**取消或返回时不将分享状态误判为成功**（Q055）。
- 入口接入：`NoteCard` 更多菜单、`NoteEditor` 顶部更多菜单及「每日回顾」卡片均接入「生成分享卡片」。

### 1.3 T29：Android 桌面小组件与锁屏隐私保护（F18 / S19 / S02 / S03 / Q054 / Q056）
- `feature/widget/WidgetPreferences.kt` & `FloatingNotesWidgetState.kt`：
  - 基于原生 Android `AppWidgetProvider` + `RemoteViews` 实现（不引入额外 Gradle 在线依赖），默认开启 `privacyModeEnabled = true`（锁屏与桌面组件默认仅展示「今日待重温（共 N 篇）」，不暴露具体隐私正文 · Q056），剔除废纸篓笔记并显示精确 `更新于 MM/dd HH:mm`（Q054）。
- `feature/widget/FloatingNotesWidgets.kt` & `res/layout/widget_*.xml` / `res/xml/widget_*_info.xml`：
  - **4×1 浮记快捷桌面组件**（`FloatingNotesQuickCaptureWidgetProvider`）：直达快速记笔记、探索或 Agent 工作区。
  - **4×2 今日回顾桌面组件**（`FloatingNotesReviewWidgetProvider`）：绑定最新 `ReviewSessionEntity`（新增 `DiscoveryDao.latestReviewSession()`），点击整卡通过 `WidgetContract.ACTION_OPEN_REVIEW_SESSION` 深链直达「探索 → 回顾」当前会话（Q054）。
  - 提供 `WidgetConfigActivity` 与 `WidgetUpdater.requestRefreshAll(...)`（在笔记保存、移入废纸篓与恢复时实时刷新组件）。
- `feature/home/BackupRestoreSheet.kt`：
  - 新增「桌面组件与锁屏隐私保护」开关（`settings-widget-privacy-toggle`）与「添加桌面小组件（回顾 / 快速记录）」钉选引导（调用 `AppWidgetManager.requestPinAppWidget` 并在厂商限制时给出桌面长按提示）。

## 2. 构建产物与校验和（Build Artifacts）

在纯英文构建镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114` 执行全量构建：

```powershell
.\gradlew.bat --no-daemon --max-workers=2 "-Pkotlin.compiler.execution.strategy=in-process" testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease assembleBenchmark --console=plain
```

- `app-debug.apk`（54,320,006 bytes）
  SHA-256: `425AA4FCE6C7CFF8F6064C17913ABD378A4BADDEC51C4309BB76CF8C948727CC`
- `app-release-unsigned.apk`（43,150,112 bytes）
  SHA-256: `76AAD77A0220122E7AE7F8E7964E3BB452C28A8DAA541D104CAAE3ECC77CE590`
- `app-benchmark.apk`（43,158,312 bytes）
  SHA-256: `5C913FAFD9130294E18BC52264A6E70FB9C26319F4B3CA6D902B434C82E8E239`
- `app-debug-androidTest.apk`（1,385,374 bytes）
  SHA-256: `05FAE0A4DF48CE5158DE3E24D0F4D1A6754A6E58FC9A5653226D8CC45BADAE95`

## 3. 验证结果与真实边界

- **JVM 单元测试（`testDebugUnitTest`）**：**32/32 项通过**（0 失败 / 0 跳过），覆盖 6 个测试类：
  - `NoteDocumentRangeTest`（3）、`LocalAgentAndDualWalkTest`（4）、`BackupCodecTest`（9）、`DiscoveryEngineTest`（6）、`StatsAndShareTest`（4）、`TaskRecurrenceTest`（6）。
- **静态检查（`lintDebug`）**：**0 Errors**，92 Warnings。
- **荣耀 X10 真机自动化（`SKSVB20603001748`，`adb install -r` 保留原有 231 篇笔记）**：
  - 全量 **20/20 类、70/70 项 `androidTest` 100% 通过（72.249s）**，新增 `M4StatsShareWidgetTest` 实测统计下钻/口径/时区、多页分享卡片 PNG+FileProvider 与组件深链直达「探索」回顾页。
- **文档契约检查（`C:\Python314\python.exe scripts/check_docs.py`）**：见提交后运行结果 `PASS`。
- **已知边界（未声称范围）**：
  - 荣耀 EMUI/Magic UI 物理桌面上长按添加小组件、锁屏通知遮罩实际样式以及微信/微博等第三方分享接收App的真实回执依赖真机交互确认；自动化已验证 `WidgetUpdater` / `FloatingNotesWidgetStateBuilder` / DeepLink Intent 直达、FileProvider PNG 多页生成与选择器非成功假设。
  - M4 服务端相关任务（T27 增量同步、T28 多端迁移、T30 外部 MCP/API 授权）与云端大模型洞察（T19/T20/T21）及 release 签名/benchmark 真机跑分仍待后续轮次推进。

## 4. 下一步建议（供下一位 Agent 参考）

1. 当前纯端侧闭环（M1 core + M2 Explore + T37/T06/M3 local workspace + T26 Stats + T29 Share/Widget）已全部就绪并真机稳定通过。
2. 接下来重点转向 **服务端与多端同步阶段**：推进 T19（云端/服务端基础设施与认证）、T20/T21（真实端云协同语义检索/大模型洞察桥接）以及 T27/T28（增量同步与冲突解决）。
3. 进入新轮次前请确认 `git pull origin feat/T26-T29-stats-widgets` 或基于本分支检出新分支，继续遵守荣耀 X10 `adb install -r`（禁止卸载或清数据）及 `D:\Codex-FloatingNotes-T10-build-20261114` 英文构建镜像规则。
