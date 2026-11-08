# ADR 0010：原生 Android 工程基线

状态：accepted；2026-11-08；任务 T01，功能 F01。

## 决策

单 app 模块 Kotlin/Compose。JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin/Compose compiler 2.2.10，Compose BOM 2025.04.01；minSdk 26，compile/targetSdk 35，Build Tools 35.0.0。依赖集中在版本目录，禁止动态版本。锁定已发布稳定组合；未来发布渠道的 targetSdk 政策在发布前复核，不由工程样例声称达标。

依据：[AGP 8.9 官方兼容表](https://developer.android.com/build/releases/agp-8-9-0-release-notes)列出 Gradle 8.11.1、JDK 17、API 35；[Kotlin 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)用于核对 Kotlin/Gradle/AGP；[Compose compiler 插件](https://developer.android.com/develop/ui/compose/compiler)与 Kotlin 使用同版本；[BOM](https://developer.android.com/develop/ui/compose/bom)统一 Compose 组件，编译器版本独立。实际构建结果另存报告，资料兼容不等于构建通过。

## 身份、数据和签名

冻结基础 applicationId 为 `io.github.z3275630ops.floatingnotes`。debug 为 `.debug`，benchmark 为 `.benchmark`，互不覆盖正式库；versionCode 1，versionName 0.1.0-t01。release 保持未签名，生产密钥由所有者在正式分发前另行管理，绝不上传仓库。debug/benchmark 使用 SDK 自动生成的开发签名，只供内部体验与性能采样；跨构建环境签名可能不同，不承诺升级安装。

benchmark 继承 release 优化和非 debuggable 配置，使用开发签名，允许 shell profile；release 不伪装成可直接安装的签名正式包。系统备份暂关闭，避免恢复未定义数据版本。

合成数据通过 debug 源集显式加载；release/benchmark 不编入合成内容与调试入口。当前样例只在内存中展示，不提供假保存。T07 接入 Room 后另立事务测试，不将工程启动声称为本地记录闭环。

## 验证与退回

CI 编译 debug、release、benchmark、测试 APK，运行 lint/JVM 测试及 API35 模拟器安装启动测试。保留 APK 和校验和。没有 60/120Hz 真机时，玻璃、IME、帧/热/电指标保持待测。回退可删除独立 app/Gradle 工程；本任务无持久笔记与迁移。
