# 下一位开发 Agent：直接开工

当前是已采纳的 v3.1 开发计划，尚无 Android 运行时代码。先拉取 main，检查工作区及进行中的 PR，再读 README → AGENTS → STATUS；按本文件执行，无需重新规划整个产品。

## 第一轮交付

1. 认领 T01 / Issue #1，分支 feat/T01-native-bootstrap。核对官方兼容资料后固定 Kotlin/Compose/Gradle/AGP/JDK 版本；建立原生工程、版本目录、可复现构建与 CI，交付可安装的合成数据样例。禁止把 HTML 演示当 APK。
2. T01 通过后做 T02、T03：先证明玻璃/渐变材质和中文输入可用。默认单 Agent 顺序完成；仅在本轮用户授权后并行 Agent。
3. 满足依赖后做 T04、T07，再做 T08：四入口外壳、可中断转场、Room 事务、长短文/checkbox、草稿与重新打开。详见 [首个 APK](first-apk-vertical-slice.md)。首个 APK 是可体验检查点，完整 F01–F20 仍需全部实现。
4. T08 通过后优先 T35 / Issue #37 跨应用文本收集，随后继续 M1 的标签、媒体、搜索、待办和恢复；T05/T06 在不阻塞上述路径时推进。
5. T17 后做 T36 / Issue #38 回响；T18/T20 后做 T37 / Issue #39 双卡对撞。继续既有 Agent、洞察、地图、同步等任务。T31 集成必须覆盖 T35–T37，不能遗漏新任务。

## 按任务读取，减少重复消耗

- 工程启动：roadmap 中 T01、engineering/architecture、engineering/android-platform；只查询当前工具链所需官方资料。
- UI/输入：design/visual-system、design/motion、相关 screens、design-tokens、features/capture-editor。
- 新增能力：[v3.1 实施合同](../features/evolution-v31.md)、task-catalog、Q091–Q102。
- 只修改当前任务涉及文件；先跑相关检查，遇到具体失败再扩大排查。不要重复生成整套设计文档，不要重复调研已经确认的产品方向。

## 每次交付都要留下

可运行产物或具体阻断、执行命令及结果、提交/PR、合成数据录屏、未完成项和下一任务。更新 STATUS 与对应 task/feature 状态，按 evidence 区分 implemented 与 verified。先运行 python3 scripts/check_docs.py；工程建立后追加真实 Android 构建和相关测试。没有真机不能宣布丝滑度已通过。

液态玻璃、柔和渐变、跟手动画属于验收条件；关键交互必须可中断，正文和光标始终清晰。账号/设备/付费服务确实阻断当前任务时再问产品所有者，独立可完成的部分继续做。
