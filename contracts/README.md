# 机器可读契约

这些文件是设计合同，尚未对应运行中的应用或 API。

- feature-catalog.json：F01–F20 的页面、规格、测试和真实实现状态。
- task-catalog.json：T01–T34 依赖图，禁止形成循环。
- design-tokens.json：视觉和动效初始参数，真机验证后统一修订。
- note-document.schema.json、note-scope.schema.json、evidence.schema.json：JSON Schema 2020-12 结构合同。
- tool-registry.json：Agent 工具权限/参数清单；完整运行时参数 schema 在 T06/T19 补齐，不能直接执行。

examples 只含合成数据。检查脚本验证本仓库 schemas 使用的有限关键词、业务不变量与文档关联，不是通用 JSON Schema 引擎。M0/M3 引入客户端/服务端正式验证器，复用 fixtures 对照结果。未知文档 block 的保留策略是读取兼容规则；当前写入 schema 仅允许本版已支持类型。

任何破坏兼容的改动升级 schema_version，增加旧版本迁移 fixture。不要为通过检查直接更新样例答案而不检查行为。
