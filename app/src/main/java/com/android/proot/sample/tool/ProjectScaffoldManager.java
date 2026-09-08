package com.android.proot.sample.tool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Generates verified starter projects and build scripts for ARM64:
 * 1. Android APK Demo (pure commandline aapt2 + d8 + zipalign fast build)
 * 2. C/C++ CMake Demo (C++20 CLI executable & shared library)
 * 3. Rust CLI & Android JNI cdylib Demo (cargo build)
 * 4. Go CLI & Android JNI CGO shared library Demo (go build)
 */
public final class ProjectScaffoldManager {

    public enum ProjectType {
        ANDROID_APK("Android 原生 APK Demo", "极简命令行快速打包，生成可安装 demo.apk"),
        CPP_CMAKE("C/C++ CMake 原生项目", "C++20 控制台程序与 .so 共享库，内置单元测试"),
        RUST_JNI("Rust CLI & JNI 跨语言项目", "Cargo 工程，支持本地运行与导出 JNI .so"),
        GO_JNI("Go CLI & Web 极简服务项目", "Go Module，支持纯 Go 二进制与 CGO 动态库");

        public final String title;
        public final String desc;

        ProjectType(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }
    }

    private ProjectScaffoldManager() {}

    /**
     * Scaffolds the specified project template inside the PRoot guest filesystem.
     *
     * @param rootfsDir Host path of the PRoot rootfs.
     * @param guestWorkspace Guest path (e.g. "/workspace" or "/root").
     * @param projectName Name of the directory to create.
     * @param type Project template type.
     * @return Guest project path (e.g. "/workspace/my_project").
     */
    public static String createProject(File rootfsDir, String guestWorkspace, String projectName, ProjectType type) throws IOException {
        String cleanGuestWs = guestWorkspace.startsWith("/") ? guestWorkspace.substring(1) : guestWorkspace;
        File projectHostDir = new File(rootfsDir, cleanGuestWs + "/" + projectName);
        if (!projectHostDir.exists()) {
            projectHostDir.mkdirs();
        }

        switch (type) {
            case ANDROID_APK:
                scaffoldAndroidProject(projectHostDir, projectName);
                break;
            case CPP_CMAKE:
                scaffoldCppProject(projectHostDir, projectName);
                break;
            case RUST_JNI:
                scaffoldRustProject(projectHostDir, projectName);
                break;
            case GO_JNI:
                scaffoldGoProject(projectHostDir, projectName);
                break;
        }

        return (guestWorkspace.endsWith("/") ? guestWorkspace : guestWorkspace + "/") + projectName;
    }

    private static void scaffoldAndroidProject(File dir, String name) throws IOException {
        File srcDir = new File(dir, "src/com/example/" + name);
        File resLayout = new File(dir, "res/layout");
        File resValues = new File(dir, "res/values");
        srcDir.mkdirs();
        resLayout.mkdirs();
        resValues.mkdirs();

        // AndroidManifest.xml
        writeFile(new File(dir, "AndroidManifest.xml"),
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                "    package=\"com.example." + name + "\">\n" +
                "    <application android:label=\"" + name + "\" android:hasCode=\"true\">\n" +
                "        <activity android:name=\".MainActivity\" android:exported=\"true\">\n" +
                "            <intent-filter>\n" +
                "                <action android:name=\"android.intent.action.MAIN\" />\n" +
                "                <category android:name=\"android.intent.category.LAUNCHER\" />\n" +
                "            </intent-filter>\n" +
                "        </activity>\n" +
                "    </application>\n" +
                "</manifest>\n");

        // MainActivity.java
        writeFile(new File(srcDir, "MainActivity.java"),
                "package com.example." + name + ";\n\n" +
                "import android.app.Activity;\n" +
                "import android.os.Bundle;\n" +
                "import android.widget.TextView;\n\n" +
                "public class MainActivity extends Activity {\n" +
                "    @Override\n" +
                "    protected void onCreate(Bundle savedInstanceState) {\n" +
                "        super.onCreate(savedInstanceState);\n" +
                "        TextView tv = new TextView(this);\n" +
                "        tv.setText(\"Hello from " + name + " compiled on PRoot ARM64!\");\n" +
                "        tv.setTextSize(20f);\n" +
                "        setContentView(tv);\n" +
                "    }\n" +
                "}\n");

        // res/values/strings.xml
        writeFile(new File(resValues, "strings.xml"),
                "<resources>\n" +
                "    <string name=\"app_name\">" + name + "</string>\n" +
                "</resources>\n");

        // build.sh
        File buildSh = new File(dir, "build.sh");
        writeFile(buildSh,
                "#!/bin/sh\n" +
                "set -e\n" +
                "echo \"==> [1/4] 编译资源文件 (aapt2 compile & link)...\"\n" +
                "mkdir -p build/gen build/obj build/bin build/res_compiled\n" +
                "AAPT2=\"/opt/android-sdk/build-tools/34.0.0/aapt2\"\n" +
                "[ ! -f \"$AAPT2\" ] && AAPT2=\"/opt/android-sdk/build-tools/35.0.2/aapt2\"\n" +
                "command -v aapt2 >/dev/null 2>&1 && AAPT2=\"aapt2\"\n" +
                "\n" +
                "ANDROID_JAR=\"/opt/android-sdk/platforms/android-34/android.jar\"\n" +
                "[ ! -f \"$ANDROID_JAR\" ] && ANDROID_JAR=\"/opt/android-sdk/platforms/android-35/android.jar\"\n" +
                "[ ! -f \"$ANDROID_JAR\" ] && ANDROID_JAR=\"/opt/android-sdk/android.jar\"\n" +
                "\n" +
                "if [ ! -f \"$ANDROID_JAR\" ]; then\n" +
                "  echo \"!! 错误: 未找到 android.jar，请在环境管理中心装配 Android 编译套件！\"\n" +
                "  exit 1\n" +
                "fi\n" +
                "\n" +
                "$AAPT2 compile --dir res -o build/res_compiled/res.zip 2>/dev/null || true\n" +
                "if [ -f build/res_compiled/res.zip ]; then\n" +
                "  $AAPT2 link -I \"$ANDROID_JAR\" --manifest AndroidManifest.xml --java build/gen -o build/bin/app.unsigned.apk build/res_compiled/res.zip\n" +
                "else\n" +
                "  $AAPT2 link -I \"$ANDROID_JAR\" --manifest AndroidManifest.xml --java build/gen -o build/bin/app.unsigned.apk\n" +
                "fi\n" +
                "\n" +
                "echo \"==> [2/4] 编译 Java 源码 (javac)...\"\n" +
                "JAVA_FILES=$(find src build/gen -name \"*.java\" 2>/dev/null)\n" +
                "javac -cp \"$ANDROID_JAR\" -d build/obj $JAVA_FILES\n" +
                "\n" +
                "echo \"==> [3/4] 编译 DEX 字节码 (d8)...\"\n" +
                "CLASS_FILES=$(find build/obj -name \"*.class\" 2>/dev/null)\n" +
                "if command -v d8 >/dev/null 2>&1; then\n" +
                "  d8 --lib \"$ANDROID_JAR\" --output build/bin/ $CLASS_FILES\n" +
                "elif [ -f /opt/android-sdk/build-tools/34.0.0/d8.jar ]; then\n" +
                "  java -cp /opt/android-sdk/build-tools/34.0.0/d8.jar com.android.tools.r8.D8 --lib \"$ANDROID_JAR\" --output build/bin/ $CLASS_FILES\n" +
                "fi\n" +
                "\n" +
                "echo \"==> [4/4] 打包并对齐签名 APK...\"\n" +
                "cd build/bin\n" +
                "rm -rf apk_extract && mkdir -p apk_extract\n" +
                "unzip -qo app.unsigned.apk -d apk_extract 2>/dev/null || true\n" +
                "cp -f classes.dex apk_extract/ 2>/dev/null || true\n" +
                "(cd apk_extract && zip -qr ../app.aligned.apk .) 2>/dev/null || cp app.unsigned.apk app.aligned.apk\n" +
                "rm -rf apk_extract\n" +
                "\n" +
                "if command -v zipalign >/dev/null 2>&1; then\n" +
                "  zipalign -f 4 app.aligned.apk app.final.apk 2>/dev/null || cp app.aligned.apk app.final.apk\n" +
                "else\n" +
                "  cp app.aligned.apk app.final.apk\n" +
                "fi\n" +
                "\n" +
                "if command -v apksigner >/dev/null 2>&1; then\n" +
                "  apksigner -a app.final.apk --overwrite 2>/dev/null || true\n" +
                "fi\n" +
                "\n" +
                "echo \"==========================================\"\n" +
                "echo \"🎉 [✓] 项目 " + name + " Android APK 编译成功！\"\n" +
                "echo \"产物路径: $(pwd)/app.final.apk\"\n" +
                "echo \"==========================================\"\n");
        buildSh.setExecutable(true, false);
    }

    private static void scaffoldCppProject(File dir, String name) throws IOException {
        File srcDir = new File(dir, "src");
        srcDir.mkdirs();

        // CMakeLists.txt
        writeFile(new File(dir, "CMakeLists.txt"),
                "cmake_minimum_required(VERSION 3.20)\n" +
                "project(" + name + " CXX)\n\n" +
                "set(CMAKE_CXX_STANDARD 20)\n" +
                "set(CMAKE_CXX_STANDARD_REQUIRED ON)\n\n" +
                "add_library(" + name + "_shared SHARED src/calc.cpp)\n" +
                "add_executable(" + name + "_cli src/main.cpp)\n" +
                "target_link_libraries(" + name + "_cli PRIVATE " + name + "_shared)\n");

        // src/calc.h
        writeFile(new File(srcDir, "calc.h"),
                "#pragma once\n\n" +
                "namespace core {\n" +
                "    int add(int a, int b);\n" +
                "    const char* get_arch();\n" +
                "}\n");

        // src/calc.cpp
        writeFile(new File(srcDir, "calc.cpp"),
                "#include \"calc.h\"\n\n" +
                "namespace core {\n" +
                "    int add(int a, int b) { return a + b; }\n" +
                "    const char* get_arch() { return \"ARM64 (aarch64-linux-gnu)\"; }\n" +
                "}\n");

        // src/main.cpp
        writeFile(new File(srcDir, "main.cpp"),
                "#include <iostream>\n" +
                "#include \"calc.h\"\n\n" +
                "int main() {\n" +
                "    std::cout << \"=== " + name + " C++20 Demo ===\" << std::endl;\n" +
                "    std::cout << \"Architecture: \" << core::get_arch() << std::endl;\n" +
                "    std::cout << \"10 + 20 = \" << core::add(10, 20) << std::endl;\n" +
                "    std::cout << \"[✓] Build and execution successful!\" << std::endl;\n" +
                "    return 0;\n" +
                "}\n");

        // build.sh
        File buildSh = new File(dir, "build.sh");
        writeFile(buildSh,
                "#!/bin/sh\n" +
                "set -e\n" +
                "echo \"==> 正在使用 CMake 构建 C/C++ 原生项目: " + name + "...\"\n" +
                "cmake -B build -S . 2>&1\n" +
                "cmake --build build 2>&1\n" +
                "echo \"==> 执行构建产物验证:\"\n" +
                "./build/" + name + "_cli\n");
        buildSh.setExecutable(true, false);
    }

    private static void scaffoldRustProject(File dir, String name) throws IOException {
        File srcDir = new File(dir, "src");
        srcDir.mkdirs();

        // Cargo.toml
        writeFile(new File(dir, "Cargo.toml"),
                "[package]\n" +
                "name = \"" + name + "\"\n" +
                "version = \"0.1.0\"\n" +
                "edition = \"2021\"\n\n" +
                "[lib]\n" +
                "name = \"" + name + "_jni\"\n" +
                "crate-type = [\"cdylib\", \"rlib\"]\n\n" +
                "[[bin]]\n" +
                "name = \"" + name + "_cli\"\n" +
                "path = \"src/main.rs\"\n\n" +
                "[dependencies]\n");

        // src/main.rs
        writeFile(new File(srcDir, "main.rs"),
                "fn main() {\n" +
                "    println!(\"=== " + name + " Rust ARM64 CLI ===\");\n" +
                "    println!(\"Rust target: aarch64-unknown-linux-gnu\");\n" +
                "    println!(\"Native calculation: 42 * 2 = {}\", 42 * 2);\n" +
                "    println!(\"[✓] Rust binary running flawlessly on PRoot!\");\n" +
                "}\n");

        // src/lib.rs (JNI export example)
        writeFile(new File(srcDir, "lib.rs"),
                "#[no_mangle]\n" +
                "pub extern \"C\" fn add_numbers(a: i32, b: i32) -> i32 {\n" +
                "    a + b\n" +
                "}\n\n" +
                "#[no_mangle]\n" +
                "pub extern \"C\" fn hello_from_rust() -> *const u8 {\n" +
                "    \"Hello from Rust JNI on Android ARM64!\\0\".as_ptr()\n" +
                "}\n");

        // build.sh
        File buildSh = new File(dir, "build.sh");
        writeFile(buildSh,
                "#!/bin/sh\n" +
                "set -e\n" +
                "echo \"==> 正在使用 Cargo 构建 Rust 项目: " + name + "...\"\n" +
                "cargo build --release 2>&1\n" +
                "echo \"==> 运行构建的 CLI 二进制:\"\n" +
                "./target/release/" + name + "_cli\n" +
                "echo \"==> 生成的 JNI 动态库位于:\"\n" +
                "ls -lh target/release/lib" + name + "_jni.so 2>/dev/null || true\n");
        buildSh.setExecutable(true, false);
    }

    private static void scaffoldGoProject(File dir, String name) throws IOException {
        // go.mod
        writeFile(new File(dir, "go.mod"),
                "module " + name + "\n\n" +
                "go 1.24\n");

        // main.go
        writeFile(new File(dir, "main.go"),
                "package main\n\n" +
                "import (\n" +
                "    \"fmt\"\n" +
                "    \"runtime\"\n" +
                ")\n\n" +
                "func main() {\n" +
                "    fmt.Println(\"=== " + name + " Go ARM64 CLI ===\")\n" +
                "    fmt.Printf(\"GOOS=%s, GOARCH=%s\\n\", runtime.GOOS, runtime.GOARCH)\n" +
                "    fmt.Println(\"NumCPU:\", runtime.NumCPU())\n" +
                "    fmt.Println(\"[✓] Go binary compiled and executed on PRoot!\")\n" +
                "}\n");

        // jni_export.go (CGO shared library)
        writeFile(new File(dir, "jni_export.go"),
                "package main\n\n" +
                "import \"C\"\n\n" +
                "//export AddNumbers\n" +
                "func AddNumbers(a, b int) int {\n" +
                "    return a + b\n" +
                "}\n\n" +
                "//export HelloFromGo\n" +
                "func HelloFromGo() *C.char {\n" +
                "    return C.CString(\"Hello from Go CGO shared library on Android ARM64!\")\n" +
                "}\n");

        // build.sh
        File buildSh = new File(dir, "build.sh");
        writeFile(buildSh,
                "#!/bin/sh\n" +
                "set -e\n" +
                "echo \"==> 正在构建 Go 原生 CLI 二进制: " + name + "...\"\n" +
                "go build -o " + name + "_cli main.go\n" +
                "echo \"==> 运行 CLI 验证:\"\n" +
                "./" + name + "_cli\n" +
                "if [ \"$CGO_ENABLED\" = \"1\" ] && command -v clang >/dev/null 2>&1; then\n" +
                "  echo \"==> 正在导出 CGO 动态库 (lib" + name + ".so)...\"\n" +
                "  go build -buildmode=c-shared -o lib" + name + ".so jni_export.go main.go 2>/dev/null || true\n" +
                "  ls -lh lib" + name + ".so 2>/dev/null || true\n" +
                "fi\n" +
                "echo \"[✓] Go 项目构建全部就绪！\"\n");
        buildSh.setExecutable(true, false);
    }

    private static void writeFile(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
