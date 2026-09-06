# Android-PRoot-Engine (日本語)

<div align="center">

**Android (ARM64) 向け ルート不要の軽量 Linux ユーザ空間仮想化エンジン＆サンドボックス SDK**

[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-arm64--v8a%20(aarch64)-0078D7?style=flat-square&logo=arm)](https://arm.com)
[![Root Required](https://img.shields.io/badge/Root-%E3%83%AB%E3%83%BC%E3%83%88%E6%A8%A9%E9%99%90%E4%B8%8D%E8%A6%81-brightgreen?style=flat-square)](#)
[![W^X Safe](https://img.shields.io/badge/Android%2010%2B-W%5EX%20%E9%81%A9%E5%90%88-blueviolet?style=flat-square)](#)
[![License](https://img.shields.io/badge/License-MIT-orange?style=flat-square)](LICENSE)

---

### [ 🌐 Switch to English ](README.md#english) • [ 🇨🇳 简体中文 ](README.zh-CN.md) • [ 🤖 AI ナビゲーション ](AI_INDEX.md)

---

</div>

<br/>

### 1. 概要
**Android-PRoot-Engine** は、ルート権限（Root）を一切必要とせず、通常の Android 端末上で標準的な GNU Linux ARM64 (aarch64) ELF バイナリを実行可能にするモジュール型 Android ライブラリ（AAR）です。

Android 特有の以下のアーキテクチャ障壁を根本から解決します：
- **C ランタイムの非互換性（Bionic vs Glibc）**：Android は独自の Bionic C ライブラリを採用しているため、通常の Linux（Glibc 依存）バイナリを実行できません。本エンジンは 64bit Glibc 動的リンカー（`/lib/ld-linux-aarch64.so.1`）と依存ライブラリをサンドボックス内に動的マッピングします。
- **Android 10+ の W^X セキュリティ制限**：ネイティブライブラリの読み込み規則と ptrace によるシステムコールフックを活用し、SELinux 違反を起こさず安全にバイナリを実行します。
- **CA ルート証明書と DNS の自動補完**：Android 端末内の CA 証明書を標準的な PEM 形式（`/etc/ssl/certs/ca-certificates.crt`）に自動統合し、セキュアな HTTPS 通信を可能にします。

---

### 2. 主な機能
- 🚀 **ルート権限不要（Zero-Root）**：Linux カーネルの `PTRACE_SYSCALL` を利用した純粋なユーザ空間エミュレーション。
- 📦 **完全自己完结型**：4.8MB の軽量 Glibc ARM64 ランタイムを内蔵。初回起動時もオフラインで即時利用可能。
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

#### 1. 環境要件
- **JDK**：17+
- **Android SDK**：API 34 (Build-Tools 34.0.0+)
- **NDK**：**不要**（`arm64-v8a` 向けネイティブ SO バイナリはコンパイル済みで同梱されています）。

#### 2. ビルドコマンド
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

### 📄 ライセンス
本プロジェクトは [MIT License](LICENSE) の下で公開されています。
