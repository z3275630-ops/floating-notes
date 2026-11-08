# 文档检查记录

日期：2026-11-08。当前交付为设计与协作基线。

命令：`python3 scripts/check_docs.py`。

检查范围：内部文件链接、JSON 解析、功能与页面/用例映射、任务依赖无环、schema 示例、稳定块 ID、证据引用、事件序列、关键动效与工具权限约束。

结果：本地通过。检查了 42 份 Markdown、11 份 JSON、20 项功能与 34 项任务。

另外在临时副本注入四类错误：断开的内部链接、任务循环依赖、不存在的引用 ID、重复 block ID；检查脚本均正确返回失败。测试副本已清理，交付文件保持正确状态。

本检查不访问外部网址，不验证 Markdown 标题锚点，不替代完整 JSON Schema 验证器，也不验证任何尚未实现的 Android、后端或真实模型行为。GitHub Actions 的实际运行记录见 [Actions](https://github.com/z3275630-ops/floating-notes/actions)，按提交 SHA 核对；本文记录本地检查结果。

## v3.1 计划采纳检查 · 2026-11-08

命令：`python3 scripts/check_docs.py`。本次实际结果：PASS，49 Markdown、11 JSON、20 功能、37 任务。新增 Q091–Q102 均待执行；本结果只覆盖文档链接、契约样例、任务依赖和映射，不代表 Android/模型/真机通过。初次准备检查因尚未填入新 Issue URL 失败；建立 #37–#39 并替换占位后复检通过。最终 CI 按 PR #36 最新提交核对。
