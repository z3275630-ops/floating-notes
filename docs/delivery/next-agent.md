# 下一位开发 Agent：直接开工

v3.1 路线继续执行。main 是设计基线，Android 当前开发分支是 `feat/T10-reliable-attachments`，基于 [PR #49](https://github.com/z3275630-ops/floating-notes/pull/49) 的 `edeaafb`。先读取 [STATUS](../../STATUS.md) 与 [T10 交接](../handoffs/2026-11-14-T10.md)，再核对目标提交的验证记录和产物。

## 已交付与接续

1. T01、T02、T03 已有原生构建和 API35 模拟器证据。T04 四入口、玻璃导航和跟手输入面板已接入，A04–A12 与真机验收仍待补。T07 已提交 CI 实际导出的 Room v1 schema，数据库重开、重复保存、迟到写入和注入故障回滚已测。
2. T08 原生本地记录及 T35 系统分享、选区收集已提交。查看 [T08 验证](../quality/T08-validation.md) 和 [T35 验证](../quality/T35-validation.md)，本轮 API35 联合回归 13 项全通过，真机和故障注入以验证记录中的边界为准。记录工作流和跨应用收集各有独立 PR、安装包和 CI 证据。
3. T09 正文 #/@ 即时识别、引用选区范围及真机验收仍见 [T09 界面交接](../handoffs/2026-11-09-composables-ui.md)。T10 接入本地媒体，后续按依赖推进 T11–T14。T11 的录音应沿用 T10 本机附件存储与引用；T14 需要接入备份租约与历史保留策略。修改 Room schema 时保留已归档版本，提供迁移及恢复测试。T05/T06 在不阻塞本地路径时执行。
4. T17 后做 T36，T18/T20 后做 T37。T31 集成覆盖 T35–T37。按路线图继续既定完整功能。

## 按任务读取，减少重复消耗

- 工程启动：roadmap 中 T01、engineering/architecture、engineering/android-platform；只查询当前工具链所需官方资料。
- UI/输入：design/visual-system、design/motion、相关 screens、design-tokens、features/capture-editor。
- 新增能力：[v3.1 实施合同](../features/evolution-v31.md)、task-catalog、Q091–Q102。
- 只修改当前任务涉及文件；先跑相关检查，遇到具体失败再扩大排查。不要重复生成整套设计文档，不要重复调研已经确认的产品方向。

## 每次交付都要留下

可运行产物或具体阻断、执行命令及结果、提交/PR、合成数据录屏、未完成项和下一任务。更新 STATUS 与对应 task/feature 状态，按 evidence 区分 implemented 与 verified。先运行 python3 scripts/check_docs.py；工程建立后追加真实 Android 构建和相关测试。没有真机不能宣布丝滑度已通过。

液态玻璃、柔和渐变、跟手动画属于验收条件；关键交互必须可中断，正文和光标始终清晰。账号/设备/付费服务确实阻断当前任务时再问产品所有者，独立可完成的部分继续做。
