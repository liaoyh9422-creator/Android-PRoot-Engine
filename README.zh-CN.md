# Android-PRoot-Engine (简体中文)

<div align="center">

**面向 Android (ARM64) 的轻量级免 Root Linux 用户态虚拟化引擎与沙箱 SDK**

[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-arm64--v8a%20(aarch64)-0078D7?style=flat-square&logo=arm)](https://arm.com)
[![Root Required](https://img.shields.io/badge/Root-%E6%97%A0%E9%9C%80%20Root-brightgreen?style=flat-square)](#)
[![W^X Safe](https://img.shields.io/badge/Android%2010%2B-W%5EX%20%E5%90%88%E8%A7%84-blueviolet?style=flat-square)](#)
[![License](https://img.shields.io/badge/License-MIT-orange?style=flat-square)](LICENSE)

---

### [ 🌐 Switch to English ](README.md#english) • [ 🇯🇵 日本語 ](README.ja-JP.md) • [ 🤖 AI 导航入口 ](AI_INDEX.md)

---

</div>

<br/>

### 一、项目简介
**Android-PRoot-Engine** 是一个可直接嵌入 Android 应用的模块化类库（AAR）。它允许在**完全无需 Root 权限**的普通 Android 手机沙箱环境中，平滑运行任意标准 GNU Linux ARM64 (aarch64) ELF 格式的可执行程序。

它从根本上解决了 Android 系统运行原生 Linux 程序的几大技术壁垒：
1. **C 运行库断层（Bionic vs Glibc）**：Android 采用自研的 Bionic C 库，无法直接加载依赖 GNU Glibc 的 Linux 程序。本项目在沙箱内动态映射并接管了标准的 64 位 Glibc 动态链接器（`/lib/ld-linux-aarch64.so.1`）与基础依赖库。
2. **Android 10+ W^X 内存安全绞杀**：利用 Android 原生动态库加载规范与 ptrace 路径模拟技术，合规绕过私有目录下可执行文件的执行限制，不触发任何 SELinux 安全告警。
3. **网络与 CA 证书缺失**：自动提取并熔接 Android 系统内部的 CA 根证书，生成标准 Linux PEM 证书链，并注入定制的 DNS 解析配置，确保外联 HTTPS 请求 100% 顺畅。

---

### 二、核心特性
- 🚀 **免 Root 虚拟化**：纯用户态运行，基于 Linux 原生 `PTRACE_SYSCALL` 系统调用劫持与虚构。
- 📦 **开箱即用自闭环**：内置 4.8MB 经裁剪的极简 Glibc ARM64 运行时资产包，初次启动秒级释放，完全无需联网。
- 🔒 **Android 10+ 兼容**：通过原生库障眼法与执行流调度，全面兼容 Android 7.0 至 Android 15+。
- 🌐 **HTTPS & DNS 全打通**：自动热熔接系统 `/system/etc/security/cacerts` 为标准 `/etc/ssl/certs/ca-certificates.crt`，并支持内建纯 Go DNS。
- 🎛️ **极简 Java / Kotlin API**：封装了进程生命周期管理、真实 PID 获取、输出日志流监听与进程树强力查杀。

---

### 三、架构设计图

```
┌─────────────────────────────────────────────────────────────┐
│                Android 宿主层 (Java / Kotlin)                │
│   PRootEngine.initialize()  ➔  PRootEngine.launch(config)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 PRoot 用户态内核模拟层 (用户空间)              │
│               (libproot.so + libloader.so)                  │
│       [PTRACE 系统调用拦截 • 路径虚拟重定向 • 伪造 Root 权限]    │
└───────┬──────────────────────┬──────────────────────┬───────┘
        │                      │                      │
        ▼                      ▼                      ▼
┌─────────────────┐    ┌─────────────────┐    ┌───────────────┐
│ 动态链接器重定向 │    │ 虚拟 Rootfs 骨架 │    │ CA 证书与 DNS │
│  /lib/ld-linux- │    │  /app, /tmp,    │    │ 动态热熔接    │
│  aarch64.so.1   │    │  /dev, /proc    │    │ SSL & Resolv  │
└─────────────────┘    └─────────────────┘    └───────────────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               ▼
     ┌──────────────────────────────────────────────────┐
     │   目标 Linux ARM64 可执行程序 (Go / Rust / C++)   │
     │   (例如: GoModel, New API, CLIProxy, cloudflared)│
     └──────────────────────────────────────────────────┘
```

---

### 四、快速接入指南

#### 第一步：初始化容器运行环境
```java
PRootEngine engine = new PRootEngine(context);

// 自动完成 Glibc 释放校验、Rootfs 骨架创建、DNS 及 CA 证书熔接
boolean ready = engine.initialize();
if (!ready) {
    Log.e("PRoot", "容器环境初始化失败");
}
```

#### 第二步：启动执行 Linux ARM64 程序
```java
File myBinary = new File(context.getFilesDir(), "bin/gomodel");

PRootConfig config = new PRootConfig.Builder()
        .setExecutable(myBinary)
        .addArg("--config", "/app/config.yaml")
        .setWorkDir("/app")
        .setFakeRoot(true) // 模拟 uid=0 (root 权限)
        .addEnv("PORT", "8080")
        .addEnv("GODEBUG", "netdns=go")
        .build();

PRootProcess process = engine.launch(config);
Log.i("PRoot", "Linux 进程已启动，PID: " + process.getPid());

// 读取标准输出日志
BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
String line;
while ((line = reader.readLine()) != null) {
    Log.d("PRootLog", line);
}

// 停止或清理进程树
process.destroyProcessTree();
```

### 五、项目模块结构
- `core/`：核心 AAR 类库模块，包含底层驱动（`libproot.so` 等）、Glibc 资产包及 Java/Kotlin 控制平面。
- `app/`：极简参考演示 App，安装在手机上可一键测试执行 `uname -a` 或自定义二进制，直观验证免 Root 运行能力。

---

### 六、编译与构建指南

#### 1. 环境准备
- **JDK**：17+
- **Android SDK**：API 34 (Build-Tools 34.0.0+)
- **NDK**：**无需单独安装**（已内置预编译好的 `arm64-v8a` 底层驱动 SO 库）。

#### 2. 构建命令
```bash
# 编译核心 AAR 类库 (产物位于 core/build/outputs/aar/)
./gradlew :core:assembleRelease

# 编译演示测试应用 (产物位于 app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# 安装应用至手机设备 (需开启 ADB 调试)
./gradlew :app:installDebug
```

> **提示**：若在 Android 手机本机（如 Termux/外部存储）编译，遇权限受限请使用 `bash ./gradlew ...` 运行。

---

### 📄 开源协议
本项目采用 [MIT License](LICENSE) 授权。

