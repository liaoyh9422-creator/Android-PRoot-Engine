package com.android.proot.sample;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Java-based internationalization manager for Chinese, English, and Japanese.
 * Does not rely on Android XML resource localization.
 */
public class I18n {
    public enum Language {
        ZH_CN("zh", "🇨🇳 简体中文"),
        EN("en", "🇺🇸 English"),
        JA("ja", "🇯🇵 日本語");

        private final String code;
        private final String displayName;

        Language(String code, String displayName) {
            this.code = code;
            this.displayName = displayName;
        }

        public String getCode() { return code; }
        public String getDisplayName() { return displayName; }

        public static Language fromCode(String code) {
            for (Language l : values()) {
                if (l.code.equalsIgnoreCase(code)) return l;
            }
            return ZH_CN;
        }
    }

    public enum Key {
        APP_TITLE,
        STATUS_LABEL_PREFIX,
        STATUS_UNINITIALIZED,
        STATUS_INITIALIZING,
        STATUS_READY,
        STATUS_INIT_FAILED,
        STATUS_RUNNING,
        STATUS_STOPPED,
        STATUS_IDLE,
        STATUS_ERROR,
        BTN_INIT,
        BTN_RUN_UNAME,
        BTN_RUN_SCRIPT,
        BTN_STOP,
        BTN_CLEAR,
        BTN_COPY,
        BTN_EXEC,
        SECTION_CONTROL,
        SECTION_TERMINAL,
        HINT_CUSTOM_CMD,
        TOAST_LOG_COPIED,
        BADGE_LINES,
        CONSOLE_TITLE,
        LOG_INIT_START,
        LOG_INIT_SUCCESS,
        LOG_INIT_FAIL,
        LOG_STOPPING,
        LOG_STARTED,
        LOG_EXITED,
        LOG_KILLING_TREE,
        LOG_TREE_KILLED,
        LOG_NO_PROCESS,
        LOG_LANG_SWITCHED
    }

    private static final Map<Language, Map<Key, String>> STRINGS = new HashMap<>();
    private static final String PREF_NAME = "proot_ui_prefs";
    private static final String PREF_LANG = "selected_lang";

    private static Language currentLanguage = Language.ZH_CN;

    static {
        // --- 1. 简体中文 (ZH_CN) ---
        Map<Key, String> zh = new HashMap<>();
        zh.put(Key.APP_TITLE, "Android PRoot 虚拟化引擎");
        zh.put(Key.STATUS_LABEL_PREFIX, "状态: ");
        zh.put(Key.STATUS_UNINITIALIZED, "未初始化");
        zh.put(Key.STATUS_INITIALIZING, "正在初始化环境...");
        zh.put(Key.STATUS_READY, "已就绪 (可运行)");
        zh.put(Key.STATUS_INIT_FAILED, "初始化失败");
        zh.put(Key.STATUS_RUNNING, "正在运行: %s");
        zh.put(Key.STATUS_STOPPED, "已停止");
        zh.put(Key.STATUS_IDLE, "空闲 (上次退出码: %d)");
        zh.put(Key.STATUS_ERROR, "异常: %s");
        zh.put(Key.BTN_INIT, "初始化引擎");
        zh.put(Key.BTN_RUN_UNAME, "测试 uname");
        zh.put(Key.BTN_RUN_SCRIPT, "运行测试脚本");
        zh.put(Key.BTN_STOP, "强杀进程");
        zh.put(Key.BTN_CLEAR, "清空");
        zh.put(Key.BTN_COPY, "复制");
        zh.put(Key.BTN_EXEC, "执行");
        zh.put(Key.SECTION_CONTROL, "控制面板");
        zh.put(Key.SECTION_TERMINAL, "终端输出");
        zh.put(Key.HINT_CUSTOM_CMD, "输入 Linux 命令, 如: uname -a, id, ls -la / ...");
        zh.put(Key.TOAST_LOG_COPIED, "日志已复制到剪贴板");
        zh.put(Key.BADGE_LINES, "%d 行");
        zh.put(Key.CONSOLE_TITLE, "控制台实时日志输出:");
        zh.put(Key.LOG_INIT_START, "[系统] 正在初始化 PRoot 虚拟化引擎...");
        zh.put(Key.LOG_INIT_SUCCESS, "[系统] PRoot 引擎初始化成功！");
        zh.put(Key.LOG_INIT_FAIL, "[错误] PRoot 引擎初始化失败，请查看 logcat。");
        zh.put(Key.LOG_STOPPING, "[系统] 正在终止已有进程...");
        zh.put(Key.LOG_STARTED, "[系统] Linux 进程已启动，PID: ");
        zh.put(Key.LOG_EXITED, "[系统] 进程已退出，退出码: ");
        zh.put(Key.LOG_KILLING_TREE, "[系统] 正在清理进程树 PID=");
        zh.put(Key.LOG_TREE_KILLED, "[系统] 进程树清理完毕。");
        zh.put(Key.LOG_NO_PROCESS, "[系统] 当前无正在运行的进程。");
        zh.put(Key.LOG_LANG_SWITCHED, "[系统] 界面语言已切换为：简体中文");
        STRINGS.put(Language.ZH_CN, zh);

        // --- 2. English (EN) ---
        Map<Key, String> en = new HashMap<>();
        en.put(Key.APP_TITLE, "Android PRoot Engine");
        en.put(Key.STATUS_LABEL_PREFIX, "Status: ");
        en.put(Key.STATUS_UNINITIALIZED, "Uninitialized");
        en.put(Key.STATUS_INITIALIZING, "Initializing environment...");
        en.put(Key.STATUS_READY, "Ready (Idle)");
        en.put(Key.STATUS_INIT_FAILED, "Init Failed");
        en.put(Key.STATUS_RUNNING, "Running: %s");
        en.put(Key.STATUS_STOPPED, "Stopped");
        en.put(Key.STATUS_IDLE, "Idle (Last exit: %d)");
        en.put(Key.STATUS_ERROR, "Error: %s");
        en.put(Key.BTN_INIT, "Init Engine");
        en.put(Key.BTN_RUN_UNAME, "Run uname");
        en.put(Key.BTN_RUN_SCRIPT, "Run Script");
        en.put(Key.BTN_STOP, "Stop Process");
        en.put(Key.BTN_CLEAR, "Clear");
        en.put(Key.BTN_COPY, "Copy");
        en.put(Key.BTN_EXEC, "Run");
        en.put(Key.SECTION_CONTROL, "CONTROL PANEL");
        en.put(Key.SECTION_TERMINAL, "TERMINAL CONSOLE");
        en.put(Key.HINT_CUSTOM_CMD, "Enter Linux command, e.g.: uname -a, id, ls -la / ...");
        en.put(Key.TOAST_LOG_COPIED, "Logs copied to clipboard");
        en.put(Key.BADGE_LINES, "%d lines");
        en.put(Key.CONSOLE_TITLE, "Console Real-time Output:");
        en.put(Key.LOG_INIT_START, "[System] Initializing PRoot virtualization engine...");
        en.put(Key.LOG_INIT_SUCCESS, "[System] PRoot Engine initialized successfully!");
        en.put(Key.LOG_INIT_FAIL, "[Error] Failed to initialize PRoot Engine. Check logcat.");
        en.put(Key.LOG_STOPPING, "[System] Stopping existing process...");
        en.put(Key.LOG_STARTED, "[System] Linux process started with PID: ");
        en.put(Key.LOG_EXITED, "[System] Process exited with code: ");
        en.put(Key.LOG_KILLING_TREE, "[System] Terminating process tree PID=");
        en.put(Key.LOG_TREE_KILLED, "[System] Process tree terminated.");
        en.put(Key.LOG_NO_PROCESS, "[System] No active process running.");
        en.put(Key.LOG_LANG_SWITCHED, "[System] UI Language switched to: English");
        STRINGS.put(Language.EN, en);

        // --- 3. 日本語 (JA) ---
        Map<Key, String> ja = new HashMap<>();
        ja.put(Key.APP_TITLE, "Android PRoot 仮想化エンジン");
        ja.put(Key.STATUS_LABEL_PREFIX, "ステータス: ");
        ja.put(Key.STATUS_UNINITIALIZED, "未初期化");
        ja.put(Key.STATUS_INITIALIZING, "環境を初期化中...");
        ja.put(Key.STATUS_READY, "準備完了");
        ja.put(Key.STATUS_INIT_FAILED, "初期化失敗");
        ja.put(Key.STATUS_RUNNING, "実行中: %s");
        ja.put(Key.STATUS_STOPPED, "停止しました");
        ja.put(Key.STATUS_IDLE, "待機中 (終了コード: %d)");
        ja.put(Key.STATUS_ERROR, "エラー: %s");
        ja.put(Key.BTN_INIT, "初期化");
        ja.put(Key.BTN_RUN_UNAME, "uname 実行");
        ja.put(Key.BTN_RUN_SCRIPT, "スクリプト実行");
        ja.put(Key.BTN_STOP, "強制終了");
        ja.put(Key.BTN_CLEAR, "消去");
        ja.put(Key.BTN_COPY, "コピー");
        ja.put(Key.BTN_EXEC, "実行");
        ja.put(Key.SECTION_CONTROL, "コントロールパネル");
        ja.put(Key.SECTION_TERMINAL, "ターミナルコンソール");
        ja.put(Key.HINT_CUSTOM_CMD, "Linuxコマンドを入力 (例: uname -a, id, ls -la / ...)");
        ja.put(Key.TOAST_LOG_COPIED, "ログをクリップボードにコピーしました");
        ja.put(Key.BADGE_LINES, "%d 行");
        ja.put(Key.CONSOLE_TITLE, "コンソール出力 (リアルタイム):");
        ja.put(Key.LOG_INIT_START, "[システム] PRoot 仮想化エンジンを初期化しています...");
        ja.put(Key.LOG_INIT_SUCCESS, "[システム] PRoot エンジンの初期化が完了しました！");
        ja.put(Key.LOG_INIT_FAIL, "[エラー] 初期化に失敗しました。logcat を確認してください。");
        ja.put(Key.LOG_STOPPING, "[システム] 既存のプロセスを終了しています...");
        ja.put(Key.LOG_STARTED, "[システム] プロセスが起動しました (PID: ");
        ja.put(Key.LOG_EXITED, "[システム] プロセスが終了しました (終了コード: ");
        ja.put(Key.LOG_KILLING_TREE, "[システム] プロセスツリーを強制終了中 PID=");
        ja.put(Key.LOG_TREE_KILLED, "[システム] プロセスツリーが終了しました。");
        ja.put(Key.LOG_NO_PROCESS, "[システム] 実行中のプロセスはありません。");
        ja.put(Key.LOG_LANG_SWITCHED, "[システム] 言語を日本語に切り替えました。");
        STRINGS.put(Language.JA, ja);
    }

    public static void init(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String savedCode = sp.getString(PREF_LANG, null);
        if (savedCode != null) {
            currentLanguage = Language.fromCode(savedCode);
        } else {
            String defaultLang = Locale.getDefault().getLanguage().toLowerCase();
            if (defaultLang.startsWith("zh")) {
                currentLanguage = Language.ZH_CN;
            } else if (defaultLang.startsWith("ja")) {
                currentLanguage = Language.JA;
            } else {
                currentLanguage = Language.EN;
            }
        }
    }

    public static Language getLanguage() {
        return currentLanguage;
    }

    public static void setLanguage(Context context, Language language) {
        currentLanguage = language;
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(PREF_LANG, language.getCode()).apply();
    }

    public static String get(Key key) {
        Map<Key, String> map = STRINGS.get(currentLanguage);
        if (map != null && map.containsKey(key)) {
            return map.get(key);
        }
        return STRINGS.get(Language.EN).get(key);
    }

    public static String format(Key key, Object... args) {
        return String.format(get(key), args);
    }
}
