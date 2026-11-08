# T01 原生工程验证记录

2026-11-08；基线 main a65913e；分支 feat/T01-native-bootstrap。

## 已执行

- 读取 README、AGENTS、STATUS、handoff、roadmap、next-agent 与 Android/视觉/输入规格；工作区初始干净，GitHub 当前无 open PR。
- `python3 scripts/check_docs.py`：基线通过（49 Markdown、11 JSON、20 features、37 tasks）。
- 环境：Linux x86_64，OpenJDK 17.0.20，约 10GB 内存，无 /dev/kvm，无已连接设备。
- 下载官方 Gradle 8.11.1、Android CLI、platform 35、Build Tools 35.0.0、platform-tools。Gradle ZIP 与官方 SHA256 核对；wrapper JAR SHA256 为 `2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`。
- Gradle 初次 `help` 失败：Java 无法连接环境代理（Connection refused / Network is unreachable），插件解析失败。已改用仅本地的临时依赖中转继续构建；该环境设置不进入仓库。

## 进行中 / 未执行

Android assembly、lint、测试 APK 编译和模拟器 smoke 的最终结果在执行后补充。配置了 CI 不等于 CI 已通过。

无真机：60/120Hz 帧、温升、电量、OPPO 中文 IME、TalkBack、安装和录屏未执行。T01 工程样例只展示合成列表；T02/T03/T04/T07/T08 尚未交付，F01 未实现。禁止将本记录当成首个记录闭环验收。
