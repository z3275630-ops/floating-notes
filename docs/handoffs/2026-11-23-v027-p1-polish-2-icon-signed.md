# 浮记专属桌面图标上线与正式发布签名落地交接（v0.2.7-p1-polish.2）

日期：2026-11-23  
分支：`main`  
版本：`versionCode = 27`，`versionName = "0.2.7-p1-polish.2"`（调试包 `0.2.7-p1-polish.2-debug`）

## 1. 目标与约束

- 为「浮记」接入用户选定的现代扁平双卡片（雾薄荷斜角背景卡 + 翡翠绿主卡 + 暖琥珀灵光圆点）自适应桌面图标，补齐 `AndroidManifest.xml` 中缺失的应用图标声明。
- 在开发机本机用户目录配置长期保留的 PKCS12 正式签名证书，实现**直接可安装**的已签名正式版 `app-release.apk`，同时保持 GitHub 仓库零密钥文件与 CI 无密钥环境正常构建。
- 严格保持 Room `SCHEMA_VERSION = 7` 不变，荣耀 X10 全程仅使用 `adb install -r` 覆盖安装保留已有用户笔记。

## 2. 本轮改动内容

1. **自适应与多形态桌面图标（`res/mipmap-*`、`res/drawable/`、`AndroidManifest.xml`）**
   - 对用户提供的 `1024×1024` 双卡设计图做去底色溢出边缘修复与光学居中裁剪：清除原始底图边缘灰白杂点，沿卡面纯色边界向外做 5px 色彩溢出抗锯齿，并重新合成柔和棚拍悬空投影，保证圆形（66dp 安全区）与荣耀/iOS 圆角矩形（72dp 视口）裁剪下均不裁角、无白边。
   - 生成全密度自适应前景图 `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png`、自适应背景矢量渐变 `drawable/ic_launcher_background.xml`（`#FAF9F5` → `#EFECE5`）、Android 13+ 单色主题矢量 `drawable/ic_launcher_monochrome.xml`，以及 `mipmap-anydpi-v26/ic_launcher.xml`、`ic_launcher_round.xml` 和传统方形/圆形兼容切图 `ic_launcher.png` / `ic_launcher_round.png`。
   - `app/src/main/AndroidManifest.xml` `<application>` 标签补充 `android:icon="@mipmap/ic_launcher"` 与 `android:roundIcon="@mipmap/ic_launcher_round"`。

2. **安全本地发布签名（`.gitignore`、`app/build.gradle.kts`）**
   - 在本机 `C:\Users\邓国军1\.android\` 生成长期 PKCS12 签名钥匙 `floating-notes-release.jks`（`2048`-bit RSA，有效期 10,000 天，别名 `floating-notes-release`）与配置文件 `floating-notes-signing.properties`；密码随机生成且仅存于该本地文件，绝不提交仓库或写入日志。
   - `app/build.gradle.kts` 升级至 `versionCode = 27`、`versionName = "0.2.7-p1-polish.2"`，并支持按优先级从环境变量（`FLOATING_NOTES_*`）、根目录 `release-signing.properties`（已加入 `.gitignore`）或 `$USERPROFILE/.android/floating-notes-signing.properties`（UTF-8 读取，适配中文用户目录）条件加载发布签名（v1/v2/v3）；若环境无签名文件则自动跳过而不阻断 CI。

## 3. 验证结果

- **Gradle 全量构建、单测与 Lint**（镜像目录 `D:\Codex-FloatingNotes-T10-build-20261114`）：
  - `testDebugUnitTest`：**10 个测试类，62/62 tests PASS（0 failures, 0 errors, 0 skipped）**。
  - `lintDebug`：**0 errors（74 warnings, 2 informational）**。
- **签名与清单核验（`apksigner` & `aapt`）**：
  - `apksigner verify --verbose --print-certs app-release.apk`：**Verifies**（APK Signature Scheme v2 + v3 均通过；证书主体 `CN=Floating Notes, OU=Mobile, O=z3275630ops, L=Local, ST=Local, C=CN`，证书 SHA-256 `3ba971957d44d73d7ef4ae3c7fc7e168119dc11385a00e2257cd6cdfc6718195`）。
  - `aapt dump badging app-release.apk` 确认 `versionCode='27'`、`versionName='0.2.7-p1-polish.2'` 与自适应图标入口已正确写入。
- **构建产物 SHA-256**：
  - `release/app-release.apk`：`43,495,597` bytes（`41.48 MiB`），SHA-256 `eb6581c0ce00376222a9b3c40aa04f26060d81504d801f80ef9fa82fc1e5e9eb`
  - `debug/app-debug.apk`：`54,220,602` bytes（`51.71 MiB`），SHA-256 `5e68fde2ab1ace5bda77cd6f804017226042ffc27e54a6885ad66ce288416dc6`
  - `benchmark/app-benchmark.apk`：`43,478,760` bytes（`41.46 MiB`），SHA-256 `f441f78ee984a78421f2acfef79dbd7829225f95238285ec3a38e30be6e46fdd`
  - `androidTest/debug/app-debug-androidTest.apk`：`1,473,676` bytes（`1.41 MiB`），SHA-256 `35bfb3dae1eef9b8ff75df6ee6c4ff258f7cec3ffb7bf911a228d534871d7b73`
- **荣耀 X10 真机全量验收**（`SKSVB20603001748`，`adb install -r` 覆盖安装保留原笔记，`versionCode=27`）：
  - `am instrument -w io.github.z3275630ops.floatingnotes.debug.test/androidx.test.runner.AndroidJUnitRunner`：**23/23 类，87/87 tests PASS（耗时 80.532s）**。
- **发布状态**：
  - 已提交至 `main` 分支、打标签并发布：[v0.2.7-p1-polish.2](https://github.com/z3275630-ops/floating-notes/releases/tag/v0.2.7-p1-polish.2)。
