# 文档检查记录

日期：2026-11-08。当前交付为设计与协作基线。

命令：`python3 scripts/check_docs.py`。

检查范围：内部文件链接、JSON 解析、功能与页面/用例映射、任务依赖无环、schema 示例、稳定块 ID、证据引用、事件序列、关键动效与工具权限约束。

结果：本地通过。检查了 42 份 Markdown、11 份 JSON、20 项功能与 34 项任务。

另外在临时副本注入四类错误：断开的内部链接、任务循环依赖、不存在的引用 ID、重复 block ID；检查脚本均正确返回失败。测试副本已清理，交付文件保持正确状态。

本检查不访问外部网址，不验证 Markdown 标题锚点，不替代完整 JSON Schema 验证器，也不验证任何尚未实现的 Android、后端或真实模型行为。GitHub Actions 的实际运行记录见 [Actions](https://github.com/z3275630-ops/floating-notes/actions)，按提交 SHA 核对；本文记录本地检查结果。
