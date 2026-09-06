# Android-PRoot-Engine: 设计规范与避坑指南

本文档汇集了在 Android 系统上运行 Linux 原生用户态虚拟化时所必须遵循的设计准则与经典技术陷阱解决方案。

---

## 一、核心避坑指南 (Critical Gotchas)

### 坑 1：Android 10+ W^X 内存安全绞杀与执行限制
- **现象**：将 Linux 可执行文件直接解压到 `context.getFilesDir()` 并试图 `execve()` 时，在 Android 10 (API 29) 及以上设备会抛出 `Permission denied`，即便 `chmod 777` 也无法执行。
- **根因**：Android 10 启用了严格的 SELinux 策略与 W^X（Write XOR Execute）安全机制，普通应用私有数据目录 (`/data/data/<pkg>/files`) 被挂载或配置为禁止直接执行原生二进制。
- **解决方案**：
  1. 将核心执行器（`libproot.so`, `libloader.so` 等）伪装成 `.so` 动态库命名，放入 `jniLibs/arm64-v8a/`；
  2. 在 `build.gradle` 中配置：
     ```groovy
     packagingOptions {
         jniLibs { useLegacyPackaging = true }
     }
     ```
  3. 系统安装 APK 时会强制将这些 `.so` 物理提取到系统的 `nativeLibraryDir`（例如 `/data/app/.../lib/arm64`），该目录具备合规的执行权限；
  4. 由 `libproot.so` 作为合法受信任的主进程启动，并通过 `ptrace` 系统调用劫持与重定向，在用户态模拟执行任何目标可执行程序，**彻底规避 SELinux 报警**。

---

### 坑 2：外部存储 (`/storage/emulated/0`) 的 `noexec` 挂载限制
- **现象**：在 Android 本机环境（如 Termux、PRoot 容器）中执行 `./gradlew` 时，系统直接提示 `bash: ./gradlew: Permission denied`。
- **根因**：`/storage/emulated/0` 是 Android FUSE / sdcardfs 文件系统，底层挂载选项固定带有 `noexec` 标志，任何文件都无法拥有原生执行权限位。
- **解决方案**：
  - 永远不要直接 `./gradlew`，而是通过明确的 Shell 解释器调度：
    ```bash
    bash ./gradlew :core:assembleRelease
    ```

---

### 坑 3：C 运行库断层（Bionic vs Glibc）动态链接失败
- **现象**：执行标准的 Linux ARM64 可执行程序时，报 `No such file or directory`。
- **根因**：Android 采用自研轻量级 Bionic C 库（链接器为 `/system/bin/linker64`），而标准 Linux 发行版编译的二进制依赖 GNU Glibc 动态链接器（`/lib/ld-linux-aarch64.so.1`）。Android 根目录下根本不存在该链接器路径。
- **解决方案**：
  - PRoot 将预编译的动态链接器通过 `-b <path>:/lib/ld-linux-aarch64.so.1` 强行挂载映射，并将内置的裁剪版 `libc.so.6`, `libm.so.6` 等映射到 `/usr/lib64` 与 `/lib64`，使目标 Linux 程序如同运行在标准的 Debian/Ubuntu 64位系统上。

---

### 坑 4：DNS 解析失效与 HTTPS 证书链校验中断
- **现象**：Linux Go 或 Rust 编写的网络程序（如 API 代理、网关）在沙箱内发起外联请求时，报 `dial tcp: lookup xxx: no such host` 或 `x509: certificate signed by unknown authority`。
- **根因**：
  1. Android 没有 `/etc/resolv.conf`，系统依赖 `netd` 本地 IPC 守护进程；
  2. Linux 依赖 `/etc/ssl/certs/ca-certificates.crt` 标准 PEM 文件，而 Android 将证书分散保存在 `/system/etc/security/cacerts/*.0` 二进制格式中。
- **解决方案**：
  1. 自动化合成 `/etc/resolv.conf`，并注入纯 Go 解析器环境变量：
     ```bash
     GODEBUG=netdns=go
     ```
  2. 自动化扫描 Android 系统的 `/system/etc/security/cacerts`，合成标准 PEM 证书文件并绑定至 `/etc/ssl/certs/ca-certificates.crt`；
  3. 注入环境变量：
     ```bash
     SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
     SSL_CERT_DIR=/etc/ssl/certs
     ```

---

### 坑 5：后台进程残留与僵尸孤儿进程泄露
- **现象**：调用 `Process.destroy()` 之后，后台服务器（如监听 8080 端口的守护进程）依然存活，导致下次启动时端口冲突。
- **根因**：Java 的 `Process.destroy()` 只能向其直接派生的父进程（`proot`）发送信号，PRoot 在内部虚拟化派生的多层级 Linux 子进程会脱壳变为孤儿进程。
- **解决方案**：
  - 使用 [`ProcessUtil.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/ProcessUtil.java)：
    1. 反射提取 ART 底层物理 PID；
    2. 向整个进程组发送 SIGKILL：`kill -9 -PID`；
    3. 递归查杀子进程：`pgrep -P PID | xargs kill -9`；
    4. 兜底扫描 `/proc` 清理特定组件残留。

---

## 二、架构设计规范 (Design Specifications)

1. **绝对业务解耦规范**：
   - `:core` 模块内**严禁**出现任何具体业务应用逻辑（如 AI 渠道、代理端口、特定配置键值、SharedPreferences 等）；
   - 所有执行参数必须通过泛化配置类 [`PRootConfig`](file:///storage/emulated/0/projects/Android-PRoot-Engine/core/src/main/java/com/android/proot/PRootConfig.java) 进行动态装配。

2. **纯 Java 代码级国际化规范**：
   - 演示应用 UI 国际化**严禁**使用 Android XML 资源目录（避免 `values-zh`, `values-ja` 等冗余目录）；
   - 必须通过纯 Java 枚举与内存字典（如 [`I18n.java`](file:///storage/emulated/0/projects/Android-PRoot-Engine/app/src/main/java/com/android/proot/sample/I18n.java)）实现，确保状态文本可实时格式化并即时热生效。

3. **原生动态库打包规范**：
   - 所有供 PRoot 调用的原生二进制必须命名为 `lib<name>.so` 格式，放置在 `jniLibs/arm64-v8a/` 目录；
   - 必须配置 `useLegacyPackaging = true` 强制解压到物理文件系统。
