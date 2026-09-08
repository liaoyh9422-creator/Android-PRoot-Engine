# Android-PRoot-Engine

<div align="center">

**Zero-Root Linux User-Space Virtualization Engine & Sandbox SDK for Android**  
**面向 Android (ARM64) 的轻量级免 Root Linux 用户态虚拟化引擎与沙箱 SDK**  
**Android (ARM64) 向け ルート不要の軽量 Linux ユーザ空間仮想化エンジン＆サンドボックス SDK**

[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-arm64--v8a%20(aarch64)-0078D7?style=flat-square&logo=arm)](https://arm.com)
[![Root Required](https://img.shields.io/badge/Root-Not%20Required-brightgreen?style=flat-square)](#)
[![W^X Safe](https://img.shields.io/badge/Android%2010%2B-W%5EX%20Compliant-blueviolet?style=flat-square)](#)
[![License](https://img.shields.io/badge/License-MIT-orange?style=flat-square)](LICENSE)

---

### Language Switch / 语言切换 / 言語切替
[ 🇺🇸 **English** ](#-english) • [ 🇨🇳 **简体中文** ](#-简体中文) • [ 🇯🇵 **日本語** ](#-日本語) • [ 🤖 **AI Index** ](AI_INDEX.md)

---

</div>

<br/>

<a id="english"></a>
## 🇺🇸 English

### 1. Overview
**Android-PRoot-Engine** is a pocket-sized, zero-root Linux virtualization and AI geek workstation SDK designed for Android (ARM64). It is an embeddable, modular Android Library (AAR) that combines:
1. **Termux TerminalView + JNI PTY Console**: Full ANSI/VT100 terminal emulation, two-finger font zoom, clipboard sync, and an accessory virtual keybar (`ESC`, `TAB`, `CTRL`, `ALT`, `^C`, `^D`, arrows).
2. **Pre-tuned Alpine Linux 3.20 (aarch64) Mini Rootfs**: Out-of-the-box system with Tsinghua mirrors, community repositories, Mozilla CA bundle, UTF-8 locale, and `apk` package manager.
3. **Pre-installed `sigoden/aichat` (v0.30.0) AI Agent**: High-performance Rust musl static single-binary LLM CLI agent with GUI API key management for DeepSeek (`deepseek-chat` / `deepseek-reasoner`), OpenAI, and custom endpoints.
4. **Self-Contained & Compact (16MB)**: Completely offline runtime and AI workstation packed within a single 16MB release APK.

It solves fundamental Android architectural barriers:
- **ABI & Runtime Divergence**: Android utilizes Google's Bionic C library, making it incapable of loading standard GNU C (Glibc) or Musl binaries. `Android-PRoot-Engine` dynamically bridges and maps an Alpine musl rootfs and a complete 64-bit Glibc runtime into the process space.
- **Android 10+ W^X Enforcement**: By leveraging native library packing mechanics and ptrace-driven user-space redirection, it safely complies with Android's `W^X` (Write XOR Execute) memory restrictions without triggering SELinux security violations.
- **Network & TLS Bridging**: Automatically integrates Mozilla CA certificates and custom DNS parameters, ensuring seamless outbound HTTPS connections.

---

### 2. Key Features
- 🚀 **Zero-Root Required**: Operates completely in Android user-space using `PTRACE_SYSCALL` interception.
- 🖥️ **Termux-Grade Interactive Terminal**: Powered by `com.termux.view.TerminalView` and JNI `libtermux.so` for interactive `top`, `vi`, nano, and shells.
- 🏔️ **Alpine Linux 3.20 Mini Rootfs**: Run `apk add python3 git curl nodejs` directly on your Android phone.
- 🤖 **Built-in aichat AI Agent**: Rust static single-binary with zero Python or heavy external dependencies.
- 🔑 **Visual AI Key Manager**: Configure DeepSeek / OpenAI API keys in seconds, automatically synchronizing sandbox `~/.config/aichat/config.yaml` and environment variables.
- 📦 **Offline Self-Contained**: Out-of-the-box pre-packaged Alpine rootfs & Glibc compatibility runtime, all under 16MB.
- 🔒 **Android 10+ W^X Compliant**: Legitimate ELF binary execution on Android 7.0 through Android 15+.
- 🎛️ **Fluent Fluent API**: Clean Java/Kotlin process management (`PRootEngine`, `PRootConfig`, `PRootProcess`).

---

### 3. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│             Android Application (Java / Kotlin)             │
│   PRootEngine.initialize()  ➔  PRootEngine.launch(config)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               PRoot User-Space Sandbox Kernel               │
│               (libproot.so + libloader.so)                  │
│       [PTRACE Interception • Syscall Translation]           │
└───────┬──────────────────────┬──────────────────────┬───────┘
        │                      │                      │
        ▼                      ▼                      ▼
┌─────────────────┐    ┌─────────────────┐    ┌───────────────┐
│ Dynamic Linker  │    │  Virtual Rootfs │    │  CA Cert &    │
│  /lib/ld-linux- │    │  /app, /tmp,    │    │  DNS Fusion   │
│  aarch64.so.1   │    │  /dev, /proc    │    │  SSL & Resolv │
└─────────────────┘    └─────────────────┘    └───────────────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               ▼
     ┌──────────────────────────────────────────────────┐
     │   Target Linux ARM64 Binary (Go / Rust / C++)    │
     │   (e.g., GoModel, New API, CLIProxy, cloudflared)│
     └──────────────────────────────────────────────────┘
```

---

### 4. Quick Start

#### Step 1: Initialize the Engine
```java
PRootEngine engine = new PRootEngine(context);

// Unpacks glibc runtime, synthesizes rootfs, and builds DNS/CA certs
boolean ready = engine.initialize();
if (!ready) {
    Log.e("PRoot", "Initialization failed");
}
```

#### Step 2: Execute a Linux ARM64 Binary
```java
File myBinary = new File(context.getFilesDir(), "bin/gomodel");

PRootConfig config = new PRootConfig.Builder()
        .setExecutable(myBinary)
        .addArg("--config", "/app/config.yaml")
        .setWorkDir("/app")
        .setFakeRoot(true) // Simulates uid=0 (root)
        .addEnv("PORT", "8080")
        .addEnv("GODEBUG", "netdns=go")
        .build();

PRootProcess process = engine.launch(config);
Log.i("PRoot", "Linux process started with PID: " + process.getPid());

// Read output stream
BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
String line;
while ((line = reader.readLine()) != null) {
    Log.d("PRootLog", line);
}

// Terminate gracefully or kill process tree
process.destroyProcessTree();
```

---

### 5. Repository Structure
- `core/`: Core Android Library (`.aar`) containing native libs (`libproot.so`, `libloader.so`), Glibc assets, and sandbox lifecycle controller.
- `app/`: Minimal reference demonstration app to test and verify Linux commands and daemon processes on real devices.

---

### 6. Build & Compilation

#### Requirements
- **JDK**: 17+
- **Android SDK**: API 34 (Build-Tools 34.0.0+)
- **NDK**: **Not required** (Prebuilt `arm64-v8a` native drivers are bundled).

#### Commands
```bash
# Build AAR Library (output: core/build/outputs/aar/)
./gradlew :core:assembleRelease

# Build Sample Test App (output: app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# Install Sample to Connected Device
./gradlew :app:installDebug
```

> **Note**: If building on-device in Android (Termux/storage), execute with `bash ./gradlew ...` to bypass FUSE permissions.

---
<br/>

---

<a id="chinese"></a>
## 🇨🇳 简体中文

### 1. 项目简介
**Android-PRoot-Engine** 是一个面向 Android (ARM64) 平台的**口袋级免 Root Linux 虚拟化与 AI 极客工作站 SDK**。它不仅是一个可直接嵌入 Android 应用的模块化类库（AAR），更集成了一套完整的用户态容器生态：
1. **Termux TerminalView + JNI PTY 交互终端**：提供完整的 ANSI/VT100 终端模拟、手势双指缩放、剪贴板交互以及专用虚拟辅助键盘栏（`ESC`, `TAB`, `CTRL`, `ALT`, `^C`, `^D`, 方向键等）。
2. **预调优 Alpine Linux 3.20 (aarch64) 容器**：开箱即用集成清华镜像源、官方社区源、Mozilla CA 根证书、UTF-8 中文环境及 `apk` 包管理体系。
3. **预置 `sigoden/aichat` 静态单二进制 AI Agent**：内建 Rust aarch64 musl 原生 AI 命令行助手，提供便捷的 UI 密钥与模型管理弹窗，开箱即用支持 DeepSeek (`deepseek-chat` / `deepseek-reasoner`)、OpenAI 与任意兼容接口。
4. **全自闭环仅 16MB**：打包了完备的 Linux 运行时、伪终端核心与 AI Agent，整包体积极致控制在 16MB 以内。

它从根本上解决了 Android 系统运行原生 Linux 程序的几大技术壁垒：
- **C 运行库断层（Bionic vs Musl/Glibc）**：Android 采用自研的 Bionic C 库，无法直接加载依赖 GNU Glibc 或 Musl 的标准 Linux 程序。本项目在沙箱内动态映射接管 Alpine musl 体系并保留了标准 64 位 Glibc 动态链接器（`/lib/ld-linux-aarch64.so.1`）。
- **Android 10+ W^X 内存安全限制**：利用 Android 原生动态库加载规范与 ptrace 路径模拟技术，合规绕过私有目录下可执行文件的执行限制，不触发任何 SELinux 安全告警。
- **网络与 CA 证书缺失**：内置 Mozilla 全量 `ca-certificates.crt` 根证书，并自动注入定制 DNS 解析配置，确保外联 HTTPS 请求 100% 顺畅。

---

### 2. 核心特性
- 🚀 **免 Root 虚拟化**：纯用户态运行，基于 Linux 原生 `PTRACE_SYSCALL` 系统调用劫持与虚构。
- 🖥️ **Termux 级交互终端**：基于 `com.termux.view.TerminalView` 与 JNI `libtermux.so`，支持 `top`、`vi`、彩色 ANSI、软键盘按键与虚拟控制键栏。
- 🏔️ **预调优 Alpine 3.20 系统**：预置国内清华源 + 官方主源，开箱可直接使用 `apk add` 安装 python、nodejs、git、curl 等各类工具链。
- 🤖 **内置 aichat AI Agent**：基于 Rust 高性能静态二进制，零 Python 解释器或外部庞大依赖，一键唤醒终端 AI 智能体对话与代码生成。
- 🔑 **直观的 AI 密钥管理**：提供可视化弹窗配置 DeepSeek / OpenAI API 密钥，实时同步沙箱内 `~/.config/aichat/config.yaml` 配置文件与环境变量。
- 📦 **开箱即用自闭环**：离线自包含 Alpine rootfs 与 Glibc 兼容运行时，初次启动秒级释放，整包仅 16MB。
- 🔒 **Android 10+ 兼容**：通过原生库机制，全面兼容 Android 7.0 至 Android 15+。
- 🎛️ **极简 Java / Kotlin API**：封装了进程生命周期管理、真实 PID 获取、输出日志流监听与进程树强力查杀。

---

### 3. 架构设计图

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

### 4. 快速接入指南

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

---

### 5. 项目模块结构
- `core/`：核心 AAR 类库模块，包含底层驱动（`libproot.so` 等）、Glibc 资产包及 Java/Kotlin 控制平面。
- `app/`：极简参考演示 App，安装在手机上可一键测试执行 `uname -a` 或自定义二进制，直观验证免 Root 运行能力。

---

### 6. 编译与构建指南

#### 环境准备
- **JDK**：17+
- **Android SDK**：API 34 (Build-Tools 34.0.0+)
- **NDK**：**无需单独安装**（已内置预编译好的 `arm64-v8a` 底层驱动 SO 库）。

#### 构建命令
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
<br/>

---

<a id="japanese"></a>
## 🇯🇵 日本語

### 1. 概要
**Android-PRoot-Engine** は、ルート権限（Root）を一切必要とせず、通常の Android 端末上で標準的な GNU Linux ARM64 (aarch64) ELF バイナリを実行可能にするモジュール型 Android ライブラリ（AAR）です。

Android 特有の以下のアーキテクチャ障壁を根本から解決します：
- **C ランタイムの非互換性（Bionic vs Glibc）**：Android は独自の Bionic C ライブラリを採用しているため、通常の Linux（Glibc 依存）バイナリを実行できません。本エンジンは 64bit Glibc 動的リンカー（`/lib/ld-linux-aarch64.so.1`）と依存ライブラリをサンドボックス内に動的マッピングします。
- **Android 10+ の W^X セキュリティ制限**：ネイティブライブラリの読み込み規則と ptrace によるシステムコールフックを活用し、SELinux 違反を起こさず安全にバイナリを実行します。
- **CA ルート証明書と DNS の自動補完**：Android 端末内の CA 証明書を標準的な PEM 形式（`/etc/ssl/certs/ca-certificates.crt`）に自動統合し、セキュアな HTTPS 通信を可能にします。

---

### 2. 主な機能
- 🚀 **ルート権限不要（Zero-Root）**：Linux カーネルの `PTRACE_SYSCALL` を利用した純粋なユーザ空間エミュレーション。
- 📦 **完全自己完結型**：4.8MB の軽量 Glibc ARM64 ランタイムを内蔵。初回起動時もオフラインで即時利用可能。
- 🔒 **Android 10〜15+ 完全対応**：W^X（Write XOR Execute）制限を回避し、最新の Android OS に適合。
- 🌐 **HTTPS & DNS 自動解決**：Android システム証明書の動的マージと、純粋な Go 向け DNS 設定の自動注入。
- 🎛️ **直感的な Java / Kotlin API**：プロセス起動、PID 取得、標準入出力ストリーム監視、プロセスツリー一括終了を簡単実装。

---

### 3. アーキテクチャ図

```
┌─────────────────────────────────────────────────────────────┐
│                 Android アプリ層 (Java / Kotlin)              │
│   PRootEngine.initialize()  ➔  PRootEngine.launch(config)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│            PRoot ユーザ空間仮想化カーネル (ユーザ空間)            │
│               (libproot.so + libloader.so)                  │
│     [PTRACE システムコール遮断 • パスリダイレクト • 仮想 Root]   │
└───────┬──────────────────────┬──────────────────────┬───────┘
        │                      │                      │
        ▼                      ▼                      ▼
┌─────────────────┐    ┌─────────────────┐    ┌───────────────┐
│ 動的リンカー    │    │ 仮想 Rootfs     │    │ CA 証明書＆DNS│
│ /lib/ld-linux-  │    │ /app, /tmp,     │    │ 動的マージ    │
│ aarch64.so.1    │    │ /dev, /proc     │    │ SSL & Resolv  │
└─────────────────┘    └─────────────────┘    └───────────────┘
        │                      │                      │
        └──────────────────────┼──────────────────────┘
                               ▼
     ┌──────────────────────────────────────────────────┐
     │    実行対象 Linux ARM64 バイナリ (Go / Rust / C++) │
     │    (例: GoModel, New API, CLIProxy, cloudflared) │
     └──────────────────────────────────────────────────┘
```

---

### 4. クイックスタート

#### ステップ 1：コンテナ環境の初期化
```java
PRootEngine engine = new PRootEngine(context);

// Glibc の展開、仮想 rootfs 構築、証明書および DNS の合成を実行
boolean ready = engine.initialize();
if (!ready) {
    Log.e("PRoot", "初期化に失敗しました");
}
```

#### ステップ 2：Linux ARM64 バイナリの実行
```java
File myBinary = new File(context.getFilesDir(), "bin/gomodel");

PRootConfig config = new PRootConfig.Builder()
        .setExecutable(myBinary)
        .addArg("--config", "/app/config.yaml")
        .setWorkDir("/app")
        .setFakeRoot(true) // uid=0 (root 権限) をエミュレート
        .addEnv("PORT", "8080")
        .addEnv("GODEBUG", "netdns=go")
        .build();

PRootProcess process = engine.launch(config);
Log.i("PRoot", "プロセスが起動しました (PID: " + process.getPid() + ")");

// 標準出力ログのリアルタイム読み出し
BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
String line;
while ((line = reader.readLine()) != null) {
    Log.d("PRootLog", line);
}

// プロセスの安全な終了（プロセスツリー全体を強制終了）
process.destroyProcessTree();
```

---

### 5. プロジェクト構成
- `core/`：コアライブラリ（AAR）。ネイティブバイナリ（`libproot.so` 等）、Glibc アセット、Java/Kotlin 制御プレーンを包含。
- `app/`：端末上で `uname -a` やカスタムバイナリの実行を直感的に検証できる最小限のデモアプリ。

---

### 6. ビルド方法

#### 環境要件
- **JDK**：17+
- **Android SDK**：API 34 (Build-Tools 34.0.0+)
- **NDK**：**不要**（`arm64-v8a` 向けネイティブ SO バイナリはコンパイル済みで同梱されています）。

#### ビルドコマンド
```bash
# コア AAR ライブラリのビルド (出力先: core/build/outputs/aar/)
./gradlew :core:assembleRelease

# サンプルアプリのビルド (出力先: app/build/outputs/apk/debug/)
./gradlew :app:assembleDebug

# 接続端末へのインストール (ADB)
./gradlew :app:installDebug
```

> **Note**：Android 端末上（Termux や外部ストレージ）で直接ビルドする場合、権限制限を回避するために `bash ./gradlew ...` を使用してください。

---
<br/>

### 📄 License
Released under the [MIT License](LICENSE).
