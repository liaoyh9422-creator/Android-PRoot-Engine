# Android-PRoot-Engine: 常用命令速查手册 (Cheatsheet)

本文档整理了项目开发、编译构建、签名校验、安装运行及日志排查的完整常用命令集。

---

## 一、Gradle 构建命令

> **特别提示**：若在 Android 设备本机环境（Termux / PRoot）且项目位于外部存储（`/storage/emulated/0`），请使用 `bash ./gradlew` 调用，以规避 FUSE 挂载的 `noexec` 权限限制。

### 1. 编译核心 AAR 类库 (`:core`)
```bash
# 构建 Release 版本 AAR 库 (输出: core/build/outputs/aar/core-release.aar)
bash ./gradlew :core:assembleRelease

# 构建 Debug 版本 AAR 库
bash ./gradlew :core:assembleDebug
```

### 2. 编译测试演示应用 (`:app`)
```bash
# 构建正式版签名 Release APK (使用内置 proot.keystore 自动签名)
bash ./gradlew :app:assembleRelease
# 产物路径: app/build/outputs/apk/release/app-release.apk

# 构建开发测试版 Debug APK
bash ./gradlew :app:assembleDebug
# 产物路径: app/build/outputs/apk/debug/app-debug.apk
```

### 3. 一键编译全部模块与清理
```bash
# 同时编译核心库与正式版应用
bash ./gradlew :core:assembleRelease :app:assembleRelease

# 清理所有模块构建产物
bash ./gradlew clean
```

---

## 二、构建产物检查与签名校验

### 1. 查看 AAR 类库内部结构
```bash
# 验证 AAR 内是否包含 libproot.so 与 glibc 离线切片
unzip -l core/build/outputs/aar/core-release.aar
```

### 2. 校验 Release APK 签名完整性
```bash
# 使用 Android SDK apksigner 验证 V1 / V2 签名状态
/root/android-sdk/build-tools/34.0.0/apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

### 3. 生成新的签名密钥库 (Keytool)
```bash
# 生成有效期为 10000 天的 RSA 2048 位发布密钥库
keytool -genkeypair -v \
  -keystore app/proot.keystore \
  -alias proot \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass proot123 \
  -keypass proot123 \
  -dname "CN=Android PRoot Engine, OU=OpenSource, O=Android-PRoot, L=Beijing, ST=Beijing, C=CN"
```

---

## 三、ADB 设备调试与安装

### 1. 安装与启动
```bash
# 覆盖安装 Release APK 至连接的手机设备
adb install -r app/build/outputs/apk/release/app-release.apk

# 通过 ADB 自动启动测试应用界面
adb shell am start -n com.android.proot.sample/.MainActivity
```

### 2. 实时日志排查 (Logcat)
```bash
# 仅过滤 PRoot 引擎与测试应用的核心标签
adb logcat -s PRootEngine PRoot:ProcessUtil MainActivity

# 查看所有与 proot 相关的系统错误日志
adb logcat *:E | grep -i proot
```

### 3. 检查设备端解压出的物理原生库 (验证 W^X 机制)
```bash
# 查看宿主安装目录下的实际动态库路径
adb shell run-as com.android.proot.sample ls -la lib/
```
