# 项目实际状态

更新时间：2026-11-14。规格版本 3.1。

## 当前开发轮次

- T01 工程启动已 verified（仅工程/安装范围），分支 `feat/T01-native-bootstrap`，源码 `8550e76`，[PR #40](https://github.com/z3275630-ops/floating-notes/pull/40)。
- 原生 debug/release/benchmark、lint、测试 APK 编译通过；API35 模拟器安装启动与 1 项 Compose 测试通过。JVM 测试为 NO-SOURCE。
- 产物、命令、失败修复与未测项见 [T01 报告](docs/quality/T01-validation.md)，交接见 [本轮记录](docs/handoffs/2026-11-08-T01.md)。
- T02 玻璃材质已通过构建和 2 项 API35 模拟器测试，真机性能待测。见 [T02 验证记录](docs/quality/T02-validation.md) 与 [交接](docs/handoffs/2026-11-09-T02.md)。
- T03 原生输入样例通过构建和 API35 自动化，真实输入法待测。见 [T03 验证](docs/quality/T03-validation.md) 与 [交接](docs/handoffs/2026-11-09-T03.md)。
- T04 四入口与可拖拽输入面板通过 5 项模拟器测试，A04–A12 与真机验收待做。见 [验证](docs/quality/T04-validation.md) 和 [交接](docs/handoffs/2026-11-09-T04.md)。
- T07 Room 事务、v1 schema 与 8 项模拟器测试通过。见 [验证](docs/quality/T07-validation.md) 和 [交接](docs/handoffs/2026-11-09-T07.md)。
- T08 已通过新建、待办混排、保存重开和自动保存的 API35 验证。见 [验证](docs/quality/T08-validation.md) 和 [交接](docs/handoffs/2026-11-09-T08.md)。
- T35 的签名 APK、系统分享和选区预览通过验证，联合回归 13 项全通过。见 [验证](docs/quality/T35-validation.md) 和 [交接](docs/handoffs/2026-11-09-T35.md)。

- T09 标签、笔记本、引用与 Room v2 迁移已通过构建和 16 项模拟器测试，[PR #47](https://github.com/z3275630-ops/floating-notes/pull/47)。验证证据见 [T09 验证](docs/quality/T09-validation.md) 和 [交接](docs/handoffs/2026-11-09-T09.md)。

- 薄荷绿主题、笔记侧栏和玻璃调整通过构建与 18 项 API 35 模拟器测试，[PR #48](https://github.com/z3275630-ops/floating-notes/pull/48)。见 [验证记录](docs/quality/mint-glass-validation.md) 与 [交接](docs/handoffs/2026-11-09-mint-glass.md)。

- T09 已移植 Composables UI 的笔记菜单与引用筛选，构建和 18 项模拟器测试通过，已发 [v0.1.8-composables.1](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.8-composables.1)。见 [选型记录](docs/decisions/0011-composables-ui-source.md) 与 [交接](docs/handoffs/2026-11-09-composables-ui.md)。

- T10 在 `feat/T10-reliable-attachments`（源码 `89e791b`）接入图片、音频和视频导入、草稿恢复、缩略图与无引用文件清理。荣耀 X10（Android 10）全量回归 24 项通过，系统选择、保存和重开已实测。调试 APK 和截图见 [v0.1.9-attachments.1](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.9-attachments.1)。功能状态和实测命令见 [T10 验证](docs/quality/T10-validation.md) 与 [T10 交接](docs/handoffs/2026-11-14-T10.md)。

- T11 在 `feat/T11-voice-capture`（源码 `3eec079`）接入前台录音、暂停、继续与本机附件保存，并修复首页状态栏浅色断层。荣耀 X10 通过短时录音实测和 22 项回归。转写与整理版仍在开发。见 [T11 交接](docs/handoffs/2026-11-14-T11.md) 和 [内测预览](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.10-voice.1)。

- T11 转写在 `feat/T11-offline-transcription`（源码 `3d9ffca`）接入 Vosk 本机普通话识别、原文/整理版切换和插入正文。已在荣耀 X10 测试系统导入、模型下载、识别和重开复用。见 [转写交接](docs/handoffs/2026-11-14-T11-transcription.md) 和 [调试预览](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.11-transcription.1)。

- T12 在 `feat/T12-local-search`（源码 `dc80a35`）接入本地中文搜索、组合筛选和保存条件。Android 构建及 Windows SQLite 查询验证已通过，荣耀 X10 已覆盖安装并验证升级，查询、保存条件及修复后的界面流程通过。见 [T12 交接](docs/handoffs/2026-11-14-T12.md) 和 [测试预览](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.12-search.1)。

- 原生玻璃样板与 T12 界面修复 `295110e` 已实现。弥散背景、悬浮底栏、胶囊快速记录和搜索弹层按 [视觉规范](docs/design/visual-system.md) 接入。见 [玻璃搜索预览](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.1.12-search.2)。

- 笔记库入口改版 `216c26e` 已在荣耀 X10 验证。筛选条件和已保存搜索直接可见，低频管理操作收进菜单。见 [笔记库交接](docs/handoffs/2026-11-14-library-ux.md)。

## 已完成

- 设计与工程交接文档，包括 F01–F20、24 个页面、视觉/动效、数据、AI、同步/备份与 API。
- 37 项开发任务、102 项验收用例、10 份 ADR（0008 为 proposed）、Agent/贡献/安全/发布流程。
- 机器可读状态/设计参数、结构 schema、合成 fixtures、PR/Issue 模板及文档 CI 配置。

## 未完成

- Android 首个本地记录切片与跨应用收集已提交。服务端、真实模型、完整标签检索与后续业务待实现。Room v1 已归档，后续 schema 变更需要迁移测试。
- 已产出 T01 工程 APK 与 T08/T35 开发 APK。真机视觉、帧/热/电性能、模型质量、真实强杀与磁盘满演练未执行。
- F01、F03、F12、F18、F20 为 in_progress，已有部分自动化证据。F01–F20 的完整验收尚未结束。

## 仓库与任务状态

- 仓库：[z3275630-ops/floating-notes](https://github.com/z3275630-ops/floating-notes)，公开（Public），默认分支 main。
- T01–T34 对应 Issues #1–#34，新增 T35/T36/T37 对应 #37/#38/#39；路线图与 task-catalog 已关联，T01 工程验证通过。原生任务实际状态以本页、task-catalog 和对应 PR 为准。
- 设计基线按 docs/project-baseline 分支 → PR → docs-contracts 检查 → 合并流程交付。
- 自动检查配置见 .github/workflows/docs.yml 与 .github/workflows/android.yml；实际运行结果在 [Actions](https://github.com/z3275630-ops/floating-notes/actions)，需按提交核对。
- 分支保护未启用；PR 和检查要求目前由协作流程约束，不声称已强制执行。

## 已采纳的 v3.1 开发计划

- [v3.1 产品评审](docs/product/v3.1-evolution-review.md) 与 [首个 APK 切片](docs/delivery/first-apk-vertical-slice.md) 已由所有者授权采纳；当前产品规格为 v3.1，继承 v3.0 完整范围和数据 schema。
- [ADR 0008](docs/decisions/0008-progressive-local-ai.md) 为 proposed 的技术候选；[ADR 0009](docs/decisions/0009-public-repository-private-data.md) 为 accepted 的公开仓库决策。
- 设计基线新增 T35–T37 与 Q091–Q102。现已交付 T35 开发包和模拟器证据，T36/T37 仍待实现。

## 下一步

[T08 / PR #45](https://github.com/z3275630-ops/floating-notes/pull/45) 与 [T35 / PR #46](https://github.com/z3275630-ops/floating-notes/pull/46) 的安装和自动化已通过。T09 正文 #/@ 即时识别与引用选区范围待补齐。按依赖推进 T12 中文搜索与范围、T13 和 T14。T11 真实来电、断网和取消重试验收仍待执行。T36 依赖 T17 相关笔记，T37 依赖 T18 漫步与 T20 洞察。T04 的 A04–A12 按业务交互补齐，真机视觉与动效保留验收。

## 验证记录

T09 集成构建源码 `d100102`。[Android CI](https://github.com/z3275630-ops/floating-notes/actions/runs/42645622221)、[APK 归档](https://github.com/z3275630-ops/floating-notes/actions/runs/42645622221/artifacts/13687622074)、[录屏与测试报告](https://github.com/z3275630-ops/floating-notes/actions/runs/42645622221/artifacts/13688141493)。文档检查通过，计 71 Markdown、13 JSON、20 features、37 tasks。

开发入口：[下一位 Agent 开工说明](docs/delivery/next-agent.md)。T35 在 T08 后优先，T36 在 T08/T17 后，T37 在 T18/T20 后；T31 增加这三项集成依赖。T09 集成分支 feat/T09-composables-ui，PR #49。
