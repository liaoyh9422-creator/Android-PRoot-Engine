# Android-PRoot-Engine: 关键文件与符号索引

本文档提供仓库内全部核心源码、资产文件、配置脚本的完整索引，方便开发人员与 AI Agent 快速检索定位。

---

## 一、项目根目录 (Root Directory)

| 文件路径 | 类型 | 职责说明 |
| :--- | :--- | :--- |
| [`settings.gradle`](file:///storage/emulated/0/projects/Android-PRoot-Engine/settings.gradle) | Gradle 配置 | 声明根工程名称 `Android-PRoot-Engine`，并挂载 `:app` 与 `:core` 两个子模块 |
| [`build.gradle`](file:///storage/emulated/0/projects/Android-PRoot-Engine/build.gradle) | Gradle 配置 | 根构建脚本，配置 AGP 8.2.0、公共仓库地址及 `clean` 任务 |
| [`gradle.properties`](file:///storage/emulated/0/projects/Android-PRoot-Engine/gradle.properties) | 环境配置 | JVM 内存参数配置（`-Xmx2048m`）与 AndroidX 全局开关 |
| [`gradlew`](file:///storage/emulated/0/projects/Android-PRoot-Engine/gradlew) | Shell 脚本 | Linux / macOS / Android (Termux) 平台下的构建入口包装脚本 (Gradle 8.11.1) |
| [`.gitignore`](file:///storage/emulated/0/projects/Android-PRoot-Engine/.gitignore) | Git 规则 | 过滤构建临时输出目录（`build/`、`.gradle/`、`*.apk`、`*.aar` 等） |
| [`README.md`](file:///storage/emulated/0/projects/Android-PRoot-Engine/README.md) | 文档 | 国际化主自述文档（支持中英日多语言导航切换） |
| [`README.zh-CN.md`](file:///storage/emulated/0/projects/Android-PRoot-Engine/README.zh-CN.md) | 文档 | 简体中文专版文档 |
| [`README.ja-JP.md`](file:///storage/emulated/0/projects/Android-PRoot-Engine/README.ja-JP.md) | 文档 | 日本語专版文档 |
| [`AI_INDEX.md`](file:///storage/emulated/0/projects/Android-PRoot-Engine/AI_INDEX.md) | 文档 | AI 智能语义导航与项目知识总入口 |

---

## 二、核心库模块 (`core/`)

模块标识符：`:core` ｜ 包名命名空间：`com.android.proot` ｜ 构建产物：`core-release.aar`

### 1. Java 核心控制平面 (`core/src/main/java/com/android/proot/`)

| 类文件 | 关键符号 / 核心方法 | 职责与设计 |
| :--- | :--- | :--- |
| [`PRootEngine.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootEngine.java) | `initialize()`<br>`launch(PRootConfig)`<br>`probeNativeLibs()`<br>`setupGlibcLibs()`<br>`setupRootfs()` | **整个虚拟化引擎的总入口**。<br>负责检查并定位底层原生库、解压释放 Glibc 运行时、构建 rootfs 骨架与 DNS/CA 证书、拼装最终 PRoot 命令行并生成进程。 |
| [`PRootConfig.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootConfig.java) | `Builder.setExecutable(...)`<br>`Builder.addArg(...)`<br>`Builder.setWorkDir(...)`<br>`Builder.setFakeRoot(...)`<br>`Builder.addBind(...)`<br>`Builder.addEnv(...)` | **进程启动配置构建器**。<br>通过链式调用组装待执行二进制（支持 Guest 路径或 Host 文件自适应挂载）、命令行参数、工作目录、环境变量与自定义绑定挂载。 |
| [`PRootProcess.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootProcess.java) | `getPid()`<br>`getInputStream()`<br>`getErrorStream()`<br>`destroyProcessTree()`<br>`isAlive()` | **虚拟化进程实体包装器**。<br>持有底层 `java.lang.Process`，提供原生 Linux PID、输入输出流桥接以及一键递归终止进程树的能力。 |
| [`ProcessUtil.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/ProcessUtil.java) | `getPid(Process)`<br>`killProcessTree(Process)`<br>`killProcessesMatching(...)` | **底层进程控制工具类**。<br>跨 Android 版本（ART ProcessImpl / Java 9+ `pid()` / private 字段反射）探测真实 Linux PID；利用 `kill -9 -PID` 及 `pgrep` 查杀整棵子进程树。 |
| [`AssetExtractor.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/AssetExtractor.java) | `extractAsset(...)`<br>`extractAssetTar(...)`<br>`extractAssetDir(...)` | **资源包极速释放工具类**。<br>处理 assets 压缩包流式写入与利用系统 `tar xzf` 秒级解压，保证无额外第三方依赖。 |
| [`CaCertHelper.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/CaCertHelper.java) | `setupCaCertificates(...)`<br>`setupNetworkConfig(...)` | **网络与证书融合助手**。<br>扫描 `/system/etc/security/cacerts` 生成标准 PEM 证书链，并注入 `resolv.conf`、`nsswitch.conf` 和 `hosts`。 |

### 2. 原生 Native 驱动 (`core/src/main/jniLibs/arm64-v8a/`)

| 文件名 | 体积 | 职责说明 |
| :--- | :--- | :--- |
| `libproot.so` | 264 KB | PRoot 主程序可执行 ELF（伪装成 `.so` 命名以合规绕过 Android 10+ W^X 限制，由系统安装器提取至原生私有执行目录） |
| `libloader.so` | 18 KB | PRoot 进程拦截注入器，拦截目标程序的系统调用并协同 `libproot.so` 处理重定向 |
| `libldlinux.so` | 204 KB | 64 位 Glibc 动态链接器（`ld-linux-aarch64.so.1`），沙箱内部 ELF 程序加载解析的基础 |
| `libtalloc.so` | 31 KB | Talloc 内存分级分配池，PRoot 依赖的核心底层 C 库 |
| `libbash.so` | 1.25 MB | 内置精简 Bash / Sh 解释器，映射至沙箱内 `/bin/sh`，支持启动复杂 Shell 脚本 |
| `libtinfo.so` | 200 KB | Terminfo 终端显示与控制台信息支持库 |
| `libandroid-shmem.so` | 14 KB | Android 共享内存兼容库 |

### 3. Glibc 离线切片包 (`core/src/main/assets/`)

| 文件名 | 体积 | 职责说明 |
| :--- | :--- | :--- |
| `glibc-libs-arm64.tar.bin` | 4.7 MB | 裁剪版 GNU Glibc ARM64 最小运行环境压缩包，解压后包含 `libc.so.6`, `libm.so.6`, `libpthread.so.0`, `libdl.so.2`, `libresolv.so.2` 等基础动态库 |

---

## 三、演示测试应用模块 (`app/`)

模块标识符：`:app` ｜ 包名命名空间：`com.android.proot.sample` ｜ 构建产物：`app-release.apk`

| 文件路径 | 职责说明 |
| :--- | :--- |
| [`app/build.gradle`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/build.gradle) | 配置内置发布签名 `proot.keystore`（V1+V2），配置 `useLegacyPackaging = true`，引入 `project(':core')` |
| [`app/proot.keystore`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/proot.keystore) | 内置自动化发布签名密钥库（有效期 10000 天） |
| [`app/src/main/AndroidManifest.xml`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/AndroidManifest.xml) | 应用清单文件，声明网络访问权限及主入口 `MainActivity` |
| [`app/src/main/java/com/android/proot/sample/I18n.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/com/android/proot/sample/I18n.java) | **纯 Java 国际化字典管理器**，不依赖 Android XML 资源，支持中文/英文/日文无缝即时热切换与偏好持久化 |
| [`app/src/main/java/com/android/proot/sample/MainActivity.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/com/android/proot/sample/MainActivity.java) | 演示界面交互控制，实现一键初始化容器、测试执行 Linux 命令、实时控制台日志刷新及进程强杀 |
| [`app/src/main/java/com/android/proot/sample/ui/UiTheme.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/com/android/proot/sample/ui/UiTheme.java) | **UI 主题与控件工厂**，移植 CLIProxyAPI 暗黑极客风格色板、圆角卡片背景、微胶囊按键、动态状态指示圆点与沉浸式状态栏 |
| [`app/src/main/java/bin/mt/file/content/MTDataFilesProvider.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/bin/mt/file/content/MTDataFilesProvider.java) | **MT 管理器私有存储 DocumentsProvider 实现**，暴露内部与外部数据/OBB/用户DE目录，支持符号链接/权限扩展与只读无锁探查 |
| [`app/src/main/java/bin/mt/file/content/MTDataFilesWakeUpActivity.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/bin/mt/file/content/MTDataFilesWakeUpActivity.java) | **MT 管理器唤醒透明 Activity**，用于冷启动或受限状态下拉起进程以向外部安全提供 ContentProvider 数据通道 |
| [`app/src/main/res/layout/activity_main.xml`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/res/layout/activity_main.xml) | 暗黑极客风格控制台布局，包含顶部状态徽章、多语言切换胶囊栏、控制面板按键组、命令交互行与终端日志窗口 |
| [`app/src/main/res/values/styles.xml`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/res/values/styles.xml) | 全局 Material 暗色主题声明（`AppTheme`），匹配 `#0D1117` 窗口与导航条底色 |
| [`app/src/main/res/mipmap-*/`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/res/) | **应用桌面启动图标集**（mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi），包含纯黑底色方形与圆形图标 (`ic_launcher` / `ic_launcher_round`) |
