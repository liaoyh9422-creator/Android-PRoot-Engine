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
**Android-PRoot-Engine** 是一个面向 Android (ARM64) 平台的**口袋级免 Root Linux 虚拟化与 AI 极客工作站 SDK**。它不仅是一个可直接嵌入 Android 应用的模块化类库（AAR），更集成了一套完整的用户态容器生态：
1. **Termux TerminalView + JNI PTY 交互终端**：提供完整的 ANSI/VT100 终端模拟、手势双指缩放、剪贴板交互以及专用虚拟辅助键盘栏（`ESC`, `TAB`, `CTRL`, `ALT`, `^C`, `^D`, 方向键等）。
2. **预调优 Alpine Linux 3.20 (aarch64) 容器**：开箱即用集成清华镜像源、官方社区源、Mozilla CA 根证书、UTF-8 中文环境及 `apk` 包管理体系。
3. **预置 `sigoden/aichat` 静态单二进制 AI Agent**：内建 Rust aarch64 musl 原生 AI 命令行助手，提供便捷的 UI 密钥与模型管理弹窗，开箱即用支持 DeepSeek (`deepseek-chat` / `deepseek-reasoner`)、OpenAI 与任意兼容接口。
4. **全自闭环仅 16MB**：打包了完备的 Linux 运行时、伪终端核心与 AI Agent，整包体积极致控制在 16MB 以内。

它从根本上解决了 Android 系统运行原生 Linux 程序的几大技术壁垒：
1. **C 运行库断层（Bionic vs Musl/Glibc）**：Android 采用自研的 Bionic C 库，无法直接加载依赖 GNU Glibc 或 Musl 的标准 Linux 程序。本项目在沙箱内动态映射接管 Alpine musl 体系并保留了标准 64 位 Glibc 动态链接器（`/lib/ld-linux-aarch64.so.1`）。
2. **Android 10+ W^X 内存安全限制**：利用 Android 原生动态库加载规范与 ptrace 路径模拟技术，合规绕过私有目录下可执行文件的执行限制，不触发任何 SELinux 安全告警。
3. **网络与 CA 证书缺失**：内置 Mozilla 全量 `ca-certificates.crt` 根证书，并自动注入定制 DNS 解析配置，确保外联 HTTPS 请求 100% 顺畅。

---

### 二、核心特性
- 🚀 **免 Root 虚拟化**：纯用户态运行，基于 Linux 原生 `PTRACE_SYSCALL` 系统调用劫持与虚构。
- 🖥️ **Termux 级交互终端**：基于 `com.termux.view.TerminalView` 与 JNI `libtermux.so`，支持 `top`、`vi`、彩色 ANSI、软键盘按键与虚拟控制键栏。
- 🏔️ **预调优 Alpine 3.20 系统**：预置国内清华源 + 官方主源，开箱可直接使用 `apk add` 安装 python、nodejs、git、curl 等各类工具链。
- 🤖 **内置 aichat AI Agent**：基于 Rust 高性能静态二进制，零 Python 解释器或外部庞大依赖，一键唤醒终端 AI 智能体对话与代码生成。
- 🔑 **直观的 AI 密钥管理**：提供可视化弹窗配置 DeepSeek / OpenAI API 密钥，实时同步沙箱内 `~/.config/aichat/config.yaml` 配置文件与环境变量。
- 📦 **开箱即用自闭环**：离线自包含 Alpine rootfs 与 Glibc 兼容运行时，初次启动秒级释放，整包仅 16MB。
- 🔒 **Android 10+ 兼容**：通过原生库机制，全面兼容 Android 7.0 至 Android 15+。
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

