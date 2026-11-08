# T01 原生工程验证记录

2026-11-08；基线 main a65913e；分支 feat/T01-native-bootstrap。

## 已执行

- 读取 README、AGENTS、STATUS、handoff、roadmap、next-agent 与 Android/视觉/输入规格；工作区初始干净，GitHub 当前无 open PR。
- `python3 scripts/check_docs.py`：基线通过（49 Markdown、11 JSON、20 features、37 tasks）。
- 环境：Linux x86_64，OpenJDK 17.0.20，约 10GB 内存，无 /dev/kvm，无已连接设备。
- 下载官方 Gradle 8.11.1、Android CLI、platform 35、Build Tools 35.0.0、platform-tools。Gradle ZIP 的 SHA256 已在下述修复后与官方值核对；wrapper JAR SHA256 为 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`。
- Gradle 初次 `help` 失败：Java 无法连接环境代理（Connection refused / Network is unreachable），插件解析失败。已改用仅本地的临时依赖中转继续构建；该环境设置不进入仓库。

## 实际 Android 验证

源码提交 `8550e764473894e1e8d4eda548f571912a074e6a`；GitHub PR #40；[Android CI run 42494881346](https://github.com/z3275630-ops/floating-notes/actions/runs/42494881346)。PR 构建的 merge SHA 为 `a45ae02442fe955ea1da442aa1d1326d125d5d5c`，产物以该 SHA 命名。

- build job 128775024900：`./gradlew --no-daemon assembleDebug assembleRelease assembleBenchmark lintDebug testDebugUnitTest assembleDebugAndroidTest`，BUILD SUCCESSFUL，3m15s。三个应用构建及测试 APK 编译通过；lint 无阻断。`testDebugUnitTest` 为 **NO-SOURCE**，没有 JVM 测试通过的含义。
- `aapt dump badging` 核对 debug 包名、versionCode=1、SDK35；`apksigner verify --verbose` 成功，v2 签名通过。
- smoke job 128775024702：API35 / Android15 / x86_64 模拟器，`./gradlew connectedDebugAndroidTest`，实际安装并启动，1 项 Compose 测试完成，BUILD SUCCESSFUL，2m57s。测试验证载入合成数据后列表显示真实 UI。启动期 adb 尚未就绪的重试未导致测试失败。
- [构建产物](https://github.com/z3275630-ops/floating-notes/actions/runs/42494881346/artifacts/13622409188)，ZIP SHA256 `aa5c726fe1df6b15aa839b120e4a9d4360561e913b61d389efb01ca78e0f2301`。ZIP 含 debug/benchmark 可安装开发包、未签名 release、测试包、报告和各 APK 校验值。
- 文档检查最终通过：53 Markdown、11 JSON、20 features、37 tasks；`git diff --check` 通过。

## 失败及修复记录

- run 42494471908：setup-android 默认安装已移除的 tools 包，失败；显式 packages=platform-tools，固定 ubuntu-24.04 后解除。
- run 42494561848：Gradle 校验值抄写多了一个字符，分发校验失败；已按官方值与实际下载计算值纠正为 64 位 SHA256。没有绕过校验。
- 自审发现 benchmark 的 initWith 不继承 release 源集，已显式共享非调试样例入口源集；最终三种构建实际通过。

## 未执行

无真机：60/120Hz 帧、温升、电量、OPPO 中文 IME、TalkBack、安装和录屏未执行。T01 工程样例只展示合成列表；T02/T03/T04/T07/T08 尚未交付，F01 未实现。禁止将本记录当成首个记录闭环验收。
