# Android 构建与安装

工具链决策见 [ADR 0010](../decisions/0010-android-bootstrap.md)。根目录使用 Gradle Wrapper；Android Studio 打开仓库根目录，Gradle JDK 选 17。安装 Android SDK platform 35、Build Tools 35.0.0、platform-tools，设置 ANDROID_HOME 或未跟踪的 local.properties（sdk.dir）。

```bash
python3 scripts/check_docs.py
./gradlew --no-daemon assembleDebug assembleRelease assembleBenchmark lintDebug testDebugUnitTest assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.z3275630ops.floatingnotes.debug/io.github.z3275630ops.floatingnotes.MainActivity
```

connectedDebugAndroidTest 需要已启动模拟器或设备。构建和测试是两个独立结论。CI 的 build/smoke 作业各自产出证据；查看具体提交，不将 workflow 配置当成通过结果。

- debug：`app/build/outputs/apk/debug/app-debug.apk`，开发签名、独立数据目录、合成数据按钮。
- release：`app/build/outputs/apk/release/app-release-unsigned.apk`，优化、未签名，不能直接安装。
- benchmark：`app/build/outputs/apk/benchmark/app-benchmark.apk`，优化、开发签名、独立包名、供真机性能采样；无合成数据入口。

首次用“载入合成示例”观察列表；内容明确标注虚构。此 T01 样例暂不保存笔记，下一阶段输入和持久化独立验收。自签名开发包可能触发 Android 外部安装确认。不要为安装卸载已有真实数据；签名不匹配时先停止并保留数据。

Gradle 分发 ZIP 已在 wrapper 中固定 SHA256；wrapper JAR 来自 Gradle v8.11.1 官方源码仓库。CI 默认只读权限，无生产签名和部署凭据。构建日志不得打印环境中的令牌。
