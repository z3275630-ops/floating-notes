# 浮记 · Floating Notes

一款面向 Android 的个人笔记与思考应用。快速记录与长文、清单共用一份笔记；每日回顾、随机漫步、洞察和 Agent 帮助旧记录重新参与思考。

**液态玻璃、柔和渐变、动画跟手感属于发布要求。功能完整与视觉质量必须同时验收。**

当前为 **v3.1 工程设计与协作基线**，已准备详细文档、契约样例、任务拆分和文档检查。Android 工程已开始 T01 原生启动，构建结果见 [实际验证](docs/quality/T01-validation.md)；本地记录与服务端尚未实现，没有已通过的真机性能报告。

仓库：[z3275630-ops/floating-notes](https://github.com/z3275630-ops/floating-notes) · [开发任务](https://github.com/z3275630-ops/floating-notes/issues) · [自动检查](https://github.com/z3275630-ops/floating-notes/actions)

## v3.1 已采纳的开发计划

- [功能与交互取舍](docs/product/v3.1-evolution-review.md)：优先跨应用捕获、边缘 AI 回响气泡和双卡概念对撞，保留四个主入口。
- [首个 APK 垂直切片](docs/delivery/first-apk-vertical-slice.md)：按 T01/T02/T03/T04/T07/T08 的依赖先做本地记录闭环。
- [ADR 0008：渐进式端侧检索](docs/decisions/0008-progressive-local-ai.md)（proposed）；[ADR 0009：公开仓库边界](docs/decisions/0009-public-repository-private-data.md)（accepted）。

**当前产品规格为 v3.1，沿用 v3.0 完整功能与数据 schema。新增任务 T35–T37 和用例 Q091–Q102 均待实现/执行。**

开发从 [下一位 Agent 开工说明](docs/delivery/next-agent.md) 开始；新增交互按 [实施合同](docs/features/evolution-v31.md) 验收。

## 从这里开始

| 读者 / 任务 | 文件 |
| --- | --- |
| 产品所有者：用普通话了解项目 | [项目说明](docs/00-start-here.md) |
| 新 Agent：开始接手 | [AGENTS.md](AGENTS.md) → [实际状态](STATUS.md) → [接手步骤](docs/delivery/handoff.md) |
| 产品与交互 | [完整范围](docs/product/requirements.md)、[20 项功能验收合同](docs/product/feature-contracts.md)、[页面规格](docs/design/screens.md) |
| 视觉与动画 | [视觉系统](docs/design/visual-system.md)、[动效合同](docs/design/motion.md)、[设计参数](contracts/design-tokens.json) |
| 工程实现 | [架构](docs/engineering/architecture.md)、[数据](docs/engineering/data-model.md)、[API](docs/engineering/api.md)、[AI](docs/engineering/ai.md) |
| 数据安全与恢复 | [同步和备份](docs/engineering/sync-backup.md)、[SECURITY.md](SECURITY.md) |
| 计划与协作 | [路线图](docs/delivery/roadmap.md)、[流程](docs/delivery/workflow.md)、[CONTRIBUTING.md](CONTRIBUTING.md) |
| 验收 | [质量门槛](docs/quality/acceptance.md)、[测试用例](docs/quality/test-cases.md)、[风险](docs/delivery/risks.md) |
| 调研依据 | [flomo 对照](docs/research/flomo-audit.md)、[官方资料](docs/research/sources.md) |

## 已确定的方向

- Android 原生 Kotlin + Jetpack Compose；本地 Room / SQLite 保存。没有网络也能记录、编辑、检索和导出已有内容。
- 底部四个入口：记录、探索、Agent、笔记库。快速记录始终容易触达。
- Agent、洞察、随机漫步、记忆和认知地图全部在完整版本范围内。里程碑表示实现顺序，不允许擅自删减。
- 可选服务端使用 Python / FastAPI、PostgreSQL 与 pgvector、S3 兼容附件存储。同步与云端 AI 明确开关；纯本地模式不会伪装成具备全天候任务。
- 工作名为“浮记”；应用标识暂定 `io.github.z3275630ops.floatingnotes`。首次分发可安装版本前冻结标识和签名策略。

## 本仓库现在能运行什么

需要 Python 3.11 或更新版本，执行：

```bash
python3 scripts/check_docs.py
```

该命令检查文档内部文件链接、JSON 契约、功能映射和示例的关键一致性。它不构建 Android，不代表 UI、AI 或性能已实现。Android 构建命令在 M0 完成可运行工程后写入开发指南。

所有样例均为虚构测试数据。仓库不含账户密钥、用户真实笔记、签名文件或生产部署。当前未授予开源许可证；公开与开源授权由所有者另行决定。

Android 工程启动与安装命令见 [构建说明](docs/engineering/android-build.md)。完整记录闭环按原定依赖推进，状态以 STATUS 为准。
