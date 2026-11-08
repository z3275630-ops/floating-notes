# 项目实际状态

更新时间：2026-11-08。规格版本 3.0。

## 已完成

- 设计与工程交接文档，包括 F01–F20、24 个页面、视觉/动效、数据、AI、同步/备份与 API。
- 34 项开发任务、90 项验收用例、7 份 ADR、Agent/贡献/安全/发布流程。
- 机器可读状态/设计参数、结构 schema、合成 fixtures、PR/Issue 模板及文档 CI 配置。

## 未完成

- Android、服务端、数据库 migrations、真实模型接入均未实现。
- APK、真机视觉验收、帧/热/电性能、模型质量及数据恢复演练均未执行。
- 所有 F 项为 planned；没有运行时功能被标为 verified。

## 仓库与任务状态

- 仓库：[z3275630-ops/floating-notes](https://github.com/z3275630-ops/floating-notes)，私有，默认分支 main。
- T01–T34 已分别建立为 GitHub Issues #1–#34，路线图与 task-catalog 中有对应链接；当前均未开工。
- 设计基线按 docs/project-baseline 分支 → PR → docs-contracts 检查 → 合并流程交付。
- 自动检查配置见 .github/workflows/docs.yml；实际运行结果在 [Actions](https://github.com/z3275630-ops/floating-notes/actions)，需按提交核对。
- 分支保护未启用；PR 和检查要求目前由协作流程约束，不声称已强制执行。

## 下一步

从 [T01 / #1](https://github.com/z3275630-ops/floating-notes/issues/1) 开始建立原生工程，按 T02–T04 验证玻璃材质、中文输入和四入口动效；T05–T06 验证检索与 Agent 执行协议。设备型号、模型预算和服务区域在对应任务开始前核对，不妨碍先做本地工程。

## 验证记录

本地文档检查结果见 docs/quality/documentation-validation.md。该结果不代表 Android/服务端或真机性能验证。
