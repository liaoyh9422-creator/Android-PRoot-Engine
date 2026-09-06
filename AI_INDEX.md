# Android-PRoot-Engine: AI 智能导航与知识总览

> **面向 AI 智能体 (Agent) 与开发者的全局语义导航中枢**  
> 本文档旨在为大型语言模型（LLM/Agent）及工程师提供高信息密度的项目知识图谱、意图路由、时序逻辑及技术陷阱速查。

---

## 🧭 快速语义导航图谱 (Intent Routing Map)

当遇到特定开发或运维意图时，请直接路由至对应的核心实现或专项文档：

```
用户/开发者意图 (User Intent)
│
├── 🚀 "我想在普通 Android 手机上免 Root 运行 Linux ARM64 二进制"
│   └── 核心 API: PRootEngine.java ➔ initialize() ➔ launch(PRootConfig)
│
├── ⚙️ "我想配置启动参数、环境变量或映射宿主目录至沙箱"
│   └── 配置类: PRootConfig.Builder ➔ setExecutable(), addArg(), addBind(), addEnv()
│
├── 🔍 "我想实时读取 Linux 进程输出，或彻底杀死进程及所有子进程"
│   └── 进程控制: PRootProcess.java ➔ getInputStream() / destroyProcessTree()
│   └── 底层实现: ProcessUtil.java ➔ getPid() 跨版本反射 / killProcessTree()
│
├── 🌐 "我想排查外网 HTTPS 请求失败或域名解析无响应问题"
│   └── 证书与 DNS: CaCertHelper.java ➔ setupCaCertificates() / setupNetworkConfig()
│   └── 避坑指引: docs/SPEC_AND_PITFALLS.md ➔ "坑 4：DNS 解析与 HTTPS 证书"
│
├── 📱 "我想查看或修改前台演示界面，增加其他语种"
│   └── 界面控制: MainActivity.java
│   └── 纯 Java 国际化: I18n.java (零 XML 资源化，支持中/英/日动态热切换)
│
├── 📦 "我想编译 Release 版 AAR 类库或签名 APK"
│   └── 命令速查: docs/COMMANDS_CHEATSHEET.md ➔ "Gradle 构建命令"
│
└── 🏛️ "我想深入理解虚拟化分层原理与底层驱动机制"
    └── 架构与拓扑: docs/ARCHITECTURE.md
    └── 执行生命周期: docs/EXECUTION_SEQUENCE.md
```

---

## 📚 专项深水区文档索引

本项目在 [`docs/`](file:///storage/emulated/0/projects/Android-PRoot-Engine/docs) 目录下建立了 5 份结构化的专项文档：

| 专册文档 | 核心内容提要 |
| :--- | :--- |
| 📐 [**总览架构与模块拓扑**](file:///storage/emulated/0/projects/Android-PRoot-Engine/docs/ARCHITECTURE.md) | 六层架构模型图、`:core` 与 `:app` 拓扑依赖关系、VFS 文件系统虚拟挂载拓扑表 |
| ⏱️ [**核心执行时序与生命周期**](file:///storage/emulated/0/projects/Android-PRoot-Engine/docs/EXECUTION_SEQUENCE.md) | 完整 Mermaid 时序图、原生探针 ➔ Glibc 释放 ➔ Rootfs/CA 合成 ➔ 进程启动 ➔ 进程树查杀 |
| 🗂️ [**关键文件与符号索引**](file:///storage/emulated/0/projects/Android-PRoot-Engine/docs/FILE_INDEX.md) | 全量代码树、7 大底层 Native 驱动职责、Glibc 资产构成及核心类与方法索引 |
| 🛡️ [**设计规范与避坑指南**](file:///storage/emulated/0/projects/Android-PRoot-Engine/docs/SPEC_AND_PITFALLS.md) | W^X 内存绞杀防范、FUSE noexec 规避、Bionic/Glibc 断层突破、僵尸进程强杀机制 |
| ⚡ [**常用命令速查手册**](file:///storage/emulated/0/projects/Android-PRoot-Engine/docs/COMMANDS_CHEATSHEET.md) | Gradle 构建、apksigner 签名校验、ADB 部署、实时 Logcat 过滤命令集合 |

---

## 🏛️ 总览架构与模块拓扑摘要

1. **模块职责边界**：
   - [`core/`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core)：核心库模块（命名空间 `com.android.proot`），打包为 **`core-release.aar`**（5.5MB）。内置 7 大原生驱动 SO 及 4.7MB Glibc 运行时切片，**零外部网络依赖、零具体业务耦合**。
   - [`app/`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app)：测试演示模块（命名空间 `com.android.proot.sample`），依赖 `:core`，内置签名输出 **`app-release.apk`**。
2. **虚拟文件系统核心映射**：
   - `/lib/ld-linux-aarch64.so.1` ➔ 映射至 Glibc 64 位链接器（接管 ELF 执行）
   - `/usr/lib64`, `/lib64` ➔ 映射至内置 Glibc 运行库
   - `/bin/sh` ➔ 映射至内置 `libbash.so`
   - `/etc/ssl/certs/ca-certificates.crt` ➔ 聚合自 Android 系统的全部根证书
   - `/etc/resolv.conf` ➔ 注入抗污染的高可用公共 DNS 服务列表

---

## ⏱️ 核心执行时序六阶段摘要

```
1. probeNativeLibs()      ➔ 智能轮询确定 libproot.so, libloader.so, libldlinux.so 路径
2. setupGlibcLibs()       ➔ 校验 .done 标记，秒级释放并赋权 glibc-libs-arm64.tar.bin
3. setupRootfs()          ➔ 创建目录骨架，注入 DNS 并合成标准 PEM 根证书
4. launch(PRootConfig)    ➔ 组装 proot 参数，注入 GODEBUG/SSL 环境变量，启动沙箱进程
5. PRootProcess           ➔ 暴露 stdout/stderr 日志流，提供真实物理 Linux PID
6. destroyProcessTree()   ➔ kill -9 -PID 杀进程组 + pgrep 递归查杀子进程，杜绝端口占用
```

---

## 🗂️ 核心文件直达索引

- 🚪 **引擎入口**：[`PRootEngine.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootEngine.java)
- 🎛️ **参数构建器**：[`PRootConfig.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootConfig.java)
- 🧵 **进程实体封装**：[`PRootProcess.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootProcess.java)
- 🔍 **跨版本 PID 与查杀**：[`ProcessUtil.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/ProcessUtil.java)
- 🌐 **CA 证书与 DNS 熔接**：[`CaCertHelper.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/CaCertHelper.java)
- 📦 **资产极速释放**：[`AssetExtractor.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/AssetExtractor.java)
- 🌍 **纯 Java 多语言控制器**：[`I18n.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/com/android/proot/sample/I18n.java)
- 📱 **测试应用主界面**：[`MainActivity.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/com/android/proot/sample/MainActivity.java)

---

## ⚡ 核心命令速查备忘

```bash
# 1. 编译核心 AAR 依赖库
bash ./gradlew :core:assembleRelease

# 2. 编译正式版内置签名 APK
bash ./gradlew :app:assembleRelease

# 3. 校验 Release APK 签名完整性
/root/android-sdk/build-tools/34.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk

# 4. ADB 安装并启动
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.android.proot.sample/.MainActivity
```
