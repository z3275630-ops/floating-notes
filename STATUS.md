# 项目实际状态

更新时间：2026-11-08。规格版本 3.1。

## 当前开发轮次

- T01 工程启动已 verified（仅工程/安装范围），分支 `feat/T01-native-bootstrap`，源码 `8550e76`，[PR #40](https://github.com/z3275630-ops/floating-notes/pull/40)。
- 原生 debug/release/benchmark、lint、测试 APK 编译通过；API35 模拟器安装启动与 1 项 Compose 测试通过。JVM 测试为 NO-SOURCE。
- 产物、命令、失败修复与未测项见 [T01 报告](docs/quality/T01-validation.md)，交接见 [本轮记录](docs/handoffs/2026-11-08-T01.md)。
- T02 玻璃材质已通过构建和 2 项 API35 模拟器测试，真机性能待测。见 [T02 验证记录](docs/quality/T02-validation.md) 与 [交接](docs/handoffs/2026-11-09-T02.md)。
- T03 原生输入样例通过构建和 API35 自动化，真实输入法待测。见 [T03 验证](docs/quality/T03-validation.md) 与 [交接](docs/handoffs/2026-11-09-T03.md)。
- T04 四入口与可拖拽输入面板已实现，A04–A12 与真机验收待做。见 [验证](docs/quality/T04-validation.md) 和 [交接](docs/handoffs/2026-11-09-T04.md)。
- T07/T08/T35 待执行，F01–F20 仍为 planned。

## 已完成

- 设计与工程交接文档，包括 F01–F20、24 个页面、视觉/动效、数据、AI、同步/备份与 API。
- 37 项开发任务、102 项验收用例、9 份 ADR（0008 为 proposed）、Agent/贡献/安全/发布流程。
- 机器可读状态/设计参数、结构 schema、合成 fixtures、PR/Issue 模板及文档 CI 配置。

## 未完成

- Android 工程正在启动；本地记录、服务端、数据库 migrations、真实模型接入均未实现。
- 已产出工程样例 APK 并完成模拟器安装；真机视觉、帧/热/电性能、模型质量及数据恢复演练未执行。
- 所有 F 项为 planned；没有运行时功能被标为 verified。

## 仓库与任务状态

- 仓库：[z3275630-ops/floating-notes](https://github.com/z3275630-ops/floating-notes)，公开（Public），默认分支 main。
- T01–T34 对应 Issues #1–#34，新增 T35/T36/T37 对应 #37/#38/#39；路线图与 task-catalog 已关联，T01 已完成工程验证，其余以 task-catalog 为准。
- 设计基线按 docs/project-baseline 分支 → PR → docs-contracts 检查 → 合并流程交付。
- 自动检查配置见 .github/workflows/docs.yml；实际运行结果在 [Actions](https://github.com/z3275630-ops/floating-notes/actions)，需按提交核对。
- 分支保护未启用；PR 和检查要求目前由协作流程约束，不声称已强制执行。

## 已采纳的 v3.1 开发计划

- [v3.1 产品评审](docs/product/v3.1-evolution-review.md) 与 [首个 APK 切片](docs/delivery/first-apk-vertical-slice.md) 已由所有者授权采纳；当前产品规格为 v3.1，继承 v3.0 完整范围和数据 schema。
- [ADR 0008](docs/decisions/0008-progressive-local-ai.md) 为 proposed 的技术候选；[ADR 0009](docs/decisions/0009-public-repository-private-data.md) 为 accepted 的公开仓库决策。
- 此次仅文档与任务变更：新增 T35–T37、Q091–Q102，所有功能和任务仍为 planned；现有数据 schema、Android 和服务端实现未更改；docs-contracts 的实际 CI 结果以 PR 页面为准。

## 下一步

T01 原生工程已通过，从 T02、T03 顺序继续、随后 T04/T07/T08 跑通本地记录 APK（维持原有任务依赖）；T05–T06 可继续独立验证检索与 Agent 执行协议。设备型号、模型预算和服务区域在对应任务开始前核对，不妨碍先做本地工程。

## 验证记录

本地文档检查结果见 docs/quality/documentation-validation.md。该结果不代表 Android/服务端或真机性能验证。

开发入口：[下一位 Agent 开工说明](docs/delivery/next-agent.md)。T35 在 T08 后优先，T36 在 T08/T17 后，T37 在 T18/T20 后；T31 增加这三项集成依赖。交接分支 docs/T01-v31-design-review-20261108；PR #36；最终合并提交以 PR 页面为准。
