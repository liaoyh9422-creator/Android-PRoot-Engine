# Android-PRoot-Engine: 总览架构与模块拓扑

本篇文档系统性阐述 **Android-PRoot-Engine** 的分层体系结构、用户态虚拟化机制、模块拓扑关系以及文件系统映射拓扑。

---

## 一、分层架构模型

本项目基于 Linux 原生 `ptrace` 系统调用劫持与虚构机制，在完全不需要 Android 手机 Root 权限的前提下，建立起一个完整的 GNU Linux 用户态执行环境。

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     1. Android 宿主应用层 (Host App)                     │
│               [:app 模块] UI 交互 / 后台守护服务 / 日志监控面板              │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ 调用 SDK API
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    2. 引擎控制平面层 (Engine SDK)                        │
│                          [:core 核心库模块]                              │
│   PRootEngine (生命周期/环境准备)  •  PRootConfig (构建器)  •  PRootProcess (进程) │
│       CaCertHelper (证书/DNS熔接)  •  AssetExtractor (解压)  •  ProcessUtil (进程树) │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ 启动执行 ProcessBuilder
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                 3. PRoot 用户态虚拟化内核 (PRoot Kernel)                 │
│                      (libproot.so + libloader.so)                       │
│    • PTRACE_SYSCALL 系统调用拦截与重写 (open, execve, socket, kill 等)   │
│    • 路径重定向 (Path Virtualization) 与 伪造 Root 权限 (uid=0, gid=0)    │
│    • 符号链接穿透模拟 (--link2symlink)                                   │
└───────────────────┬───────────────────┬───────────────────┬─────────────┘
                    │                   │                   │
                    ▼                   ▼                   ▼
      ┌───────────────────┐ ┌───────────────────┐ ┌───────────────────────┐
      │ 4. Glibc 运行时   │ │ 5. 虚拟 Rootfs    │ │ 6. 网络与 CA 根证书    │
      │ 动态链接器        │ │ 骨架系统          │ │ 动态热熔接            │
      │ /lib/ld-linux-    │ │ /app, /tmp,       │ │ /etc/resolv.conf      │
      │ aarch64.so.1      │ │ /dev, /proc, /sys │ │ /etc/ssl/certs/...    │
      └───────────────────┘ └───────────────────┘ └───────────────────────┘
                    │                   │                   │
                    └───────────────────┼───────────────────┘
                                        ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                 7. 目标 Linux ARM64 可执行程序 (Target ELF)              │
│       (标准 Go / Rust / C++ 二进制，例如: GoModel, New API, cloudflared) │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 二、模块拓扑关系 (Gradle Module Topology)

工程采用经典的低耦合多模块拓扑架构：

```mermaid
graph TD
    subgraph "Android-PRoot-Engine Root Project"
        APP[":app (演示与测试模块)<br/>- MainActivity.java<br/>- I18n.java<br/>- 签名发布 app-release.apk"]
        CORE[":core (核心引擎模块)<br/>- PRootEngine / Config / Process<br/>- 预编译 7 大 Native SO<br/>- 内置 4.7MB Glibc 资产包<br/>- 输出 core-release.aar"]
    end

    APP -->|implementation project(':core')| CORE

    subgraph "第三方宿主集成"
        OTHER_APP["外部 Android App<br/>(如 AI 代理/本地大模型服务)"]
    end

    OTHER_APP -.->|直接引用 AAR 或 Maven| CORE
```

### 模块职责界定

1. **`:core` 模块（纯净无业务底座）**：
   - 依赖项：仅依赖 Android 基础 SDK，无任何三方 Java 第三方库；
   - 产出物：通用 AAR 类库（`core-release.aar`）；
   - 核心资产：预编译的 `arm64-v8a` 驱动集合以及 Glibc 离线压缩包；
   - 外部可见性：完全公开通用接口 `com.android.proot.*`。

2. **`:app` 模块（极简测试验证器）**：
   - 作为 `:core` 模块的消费者与端到端测试用例；
   - 包含纯 Java 动态国际化界面（中/英/日），提供一键初始化、命令执行与进程强杀功能；
   - 内置生产签名密钥库 `proot.keystore`，用于自动化输出可直接分发的 Release APK。

---

## 三、文件系统虚拟挂载拓扑表 (VFS Mount Map)

PRoot 通过 `-b`（Bind Mount）参数构建隔离沙箱，将 Android 宿主特定目录精准绑定至 Linux 标准根目录路径：

| Linux 虚拟路径 (Guest) | Android 宿主真实路径 (Host) | 权限 / 模式 | 功能与设计目的 |
| :--- | :--- | :--- | :--- |
| `/lib/ld-linux-aarch64.so.1` | `$glibcDir/lib/ld-linux-aarch64.so.1` (或 `libldlinux.so`) | 只读 (`r-x`) | **突破 Bionic 限制**：接管 Glibc 64位 ELF 动态链接入口 |
| `/usr/lib64`, `/lib64` | `$glibcDir/usr/lib64` | 只读 (`r-x`) | 提供 Linux 基础 C 库（`libc.so.6`, `libm.so.6`, `libpthread.so.0`） |
| `/bin/sh` | `$nativeLibDir/libbash.so` | 可执行 (`r-x`) | 为沙箱内提供内建轻量 Bash/Sh 解释器 |
| `/app` | `context.getFilesDir()` | 读写 (`rwx`) | 应用程序私有持久化工作空间（主进程 `HOME` 目录） |
| `/tmp` | `context.getCacheDir() + "/tmp"` | 读写 (`rwx`) | 容器临时文件存放区（进程退出可自动回收） |
| `/etc/resolv.conf` | `$rootfsDir/etc/resolv.conf` | 只读 (`r--`) | **解决无 DNS 问题**：注入内建公共 DNS 解析器 |
| `/etc/ssl/certs/ca-certificates.crt` | `$rootfsDir/etc/ssl/certs/ca-certificates.crt` | 只读 (`r--`) | **打通 HTTPS 通信**：从 Android 系统证书目录抽取合成的标准 PEM 证书链 |
| `/dev`, `/proc`, `/sys` | `/dev`, `/proc`, `/sys` | 宿主直通 | 直通 Linux 内核虚拟文件系统，保障基础设备和进程状态查询 |
| `/sdcard`, `/storage` | `/sdcard`, `/storage` | 读写 (`rwx`) | 方便访问设备外部大容量存储与下载目录 |
