# UI/UX 打磨交接（Opus 5.5 录屏评审专轮 · v0.2.3-ui-polish.1）

日期：2026-11-15  
分支：`feat/T26-T29-stats-widgets`  
版本：`versionCode = 22`，`versionName = "0.2.3-ui-polish.1"`（调试包 `0.2.3-ui-polish.1-debug`）

## 1. 背景与评审方式

在荣耀 X10（Android 10，序列号 `SKSVB20603001748`）通过 `scrcpy` 录制 49 秒实机完整交互视频（覆盖首页记录流、正文编辑器、快速记录弹窗及键盘弹出、左侧抽屉、待办清单、探索三大页签、统计与热力图、分享卡片、Agent、笔记库与设置页），抽取 17 张关键帧交由 Claude Opus 5.5（`ux-advisor`）做只读 UI/UX 评审，并按其 P1–P7 与无实时模糊纸感体系落地打磨。

## 2. 本轮落地改动清单

1. **P1 消除切页鬼影与提升 Agent 可读性**
   - `NotesShell.kt`：将顶级页面切换由重叠 `Crossfade` 改为顺序非重叠 `AnimatedContent`（先退出再淡入），彻底消除 Agent 等高信息密度页面底部的上一页文字残影。
   - `AgentWorkspace.kt`：范围筛选标签由换行 `FlowRow` 改为单行横向滚动 `Row`，超长标签截断展示；`MintPillButton` 禁用态文字对比度提高（`onSurface.copy(alpha = 0.45f)`），保证“开始本机分析”清晰可读。
2. **P2 修正根页面图标语义与底部留白防遮挡**
   - `NotesShell.kt` / `TrashView.kt`：将根页左上角易误解为返回的 `<` 箭头统一替换为抽屉菜单图标 `MintIcons.Menu`（`≡ 文件夹`）。
   - `NotesShell.kt`：提高首页/探索/Agent 列表底部 `LazyColumn` 内边距（`116dp`）与底部渐变遮罩高度（`108dp`），缓解列表卡片在悬浮底栏下方露头。
3. **P3 笔记卡片排版层级与网址降噪**
   - `NotesShell.kt`：优化 `deriveNoteCardMeta` 与 `NoteCard`——自动把标题中的冗余 `https://...` 长链或 UUID 拆离为次级元数据行中的安静域名徽标（`example.com`），突出 `16sp` 半粗体标题、`14sp` 摘要、`12sp` 等宽数字时间与轻量化 `17dp` 更多按钮。
4. **P4 探索页按钮换行与次级切换降权**
   - `ExploreScreen.kt`：将回顾卡片操作区改为双行布局——“上一张 / 下一张”均分一行，“分享卡片 / 从这里漫步”右对齐独立成行（`maxLines = 1, softWrap = false`），彻底修复“从这里漫步”竖排折行或右侧裁切问题。
   - `ExploreFindAndWalk.kt`：将漫步页“单卡漫步 / 双卡对撞”由第二个全宽胶囊切换条降级为紧凑 `MintChip`，保留与主导航/页签 `MintSegmentedBar` 的层级区分。
5. **P5 底部 Sheet 规范化与分享卡片主按钮吸底**
   - `MintControls.kt`：收敛 `MintBottomSheet` 顶部圆角至 `22dp`、调整遮罩不透明度（浅色 `0.28f` / 深色 `0.48f`）、加宽居中标题列并限制副标题单行展示，且在头部下方增加 `0.5dp` 细分割线。
   - `ShareCardSheet.kt`：将主操作“打开系统分享”按钮固定（sticky）在卡片底部，不随中间选项滚出可视区。
   - `StatsSheet.kt`：收紧统计弹窗副标题为单行“按个人时区统计 · 区分手写与导入”。
6. **P6 快速记录引导文案精简**
   - `NoteEditor.kt`：将空白快速记录提示语收敛为清晰单行 `18sp`“写点什么…  #标签 @引用”；在已有标题但正文为空时增加“轻点此处继续书写正文…”提示。
7. **P7 无实时模糊下的 Apple 纸感与圆角区分**
   - `MintPalette.kt` / `GlassSurface.kt`：微调薄荷渐变底色（`#F3F8F4`）与降级玻璃层卡片实体度（`#FCFDFC`，`0.98f`/`1f`），增加 `0.5dp`–`0.6dp` 发丝细边框。
   - `MintControls.kt`：将 `MintPillButton`（`12dp`）与 `MintChip`（`10dp`）由全胶囊改为 Apple 风格大圆角矩形，使主分段切换栏保留独立胶囊识别度。
   - `NotesDrawer.kt`：选中目录背景由高饱和渐变改为柔和半透明高亮（`primary` 10% 浅色 / 18% 深色）。
   - `TasksScreen.kt`：已完成待办勾选圆圈填充柔化为 `primary.copy(alpha = 0.58f)`。
8. **测试稳定性顺手修复**
   - `LocalRecordingTest.kt` / `M4StatsShareWidgetTest.kt`：移除 `M4StatsShareWidgetTest` 对吸底按钮的无效滚动；在 `LocalRecordingTest` 引用选择器匹配中显式排除异步触发的后台 `editor-edge-echo` 节点，消除偶发节点数歧义。

## 3. 实测验证结果

所有测试均在最终代码定稿后于纯英文镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114` 与荣耀 X10（`SKSVB20603001748`，采用 `adb install -r` 覆盖安装保留手机已有笔记）实测完成：

- **JVM 单元测试**：`32/32` 通过（`FAIL=0 ERR=0 SKIP=0`）
- **Lint 检查**：`LINT_ERRORS=0`（`LINT_WARNINGS=92`）
- **真机全量 `androidTest`**：20 个测试类、`70/70` 用例全部通过（耗时 `73.26s`，含 `NoteSearchTest` 10,000 条本地检索 p95 延迟 `56.06ms / 182.32ms`）
- **真机改后截图核验**：已通过 ADB `screencap` 检查首页、`探索 -> 回顾`、`分享卡片预览` 与 `Agent` 4 张实机截图，确认无鬼影、无按钮折行或裁切、主按钮吸底正常。

### 构建产物与 SHA-256

| 产物 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `app-debug.apk` | 54,149,658 | `FB75BE5AFD647E96F7FBFB0FF7DC7086B186147B8102578F89B5C7FD21BA0C49` |
| `app-debug-androidTest.apk` | 1,385,303 | `D3D76331C657C410329D682CDC6FAFBA5CD187E0312A08577050F1E0CB2FB4A2` |
| `app-release-unsigned.apk` | 43,150,112 | `55E90C6FD066FC130128D96D4A950C77030B15EDC620E980ADDB6A4BA02DC2BB` |
| `app-benchmark.apk` | 43,158,316 | `01BC31AE41C0EF33BADDB5FFB40EE9AC40F60670D2B2B333F43EF998A93A1BA5` |

## 4. 验证边界与后续可选打磨项

- **验证边界**：荣耀 X10 为 Android 10，未开启实时背景模糊（Android 12+ 设备实时模糊效果待后续测）；未在真机单独安装运行 `release` 或 `benchmark` 构建包（仅完成编译打包含 R8 压缩检查）。
- **留待产品决策的可选项**：
  1. 首页多条“继续收集草稿”提醒卡片可考虑合并为单条折叠入口，减少首屏挤压；
  2. 可按需在阅读/回顾卡片引入“日记模式”微衬线标题开关与轻量静态纸感微噪点层。
