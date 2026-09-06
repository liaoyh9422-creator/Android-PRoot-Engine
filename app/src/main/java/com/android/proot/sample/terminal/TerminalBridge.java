package com.android.proot.sample.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.nio.charset.StandardCharsets;

/**
 * TerminalBridge connects Termux TerminalView & TerminalSession with the Android-PRoot-Engine host.
 * Implements TerminalSessionClient and TerminalViewClient, providing gesture handling, PTY lifecycle,
 * virtual control keys, and clipboard interaction.
 */
public class TerminalBridge implements TerminalSessionClient, TerminalViewClient {
    private static final String TAG = "TerminalBridge";

    public interface SessionCallback {
        void onTitleChanged(String title);
        void onSessionFinished(int exitCode);
    }

    private final Context context;
    private TerminalView terminalView;
    private SessionCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean virtualControlActive = false;
    private boolean virtualAltActive = false;

    public TerminalBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setTerminalView(TerminalView view) {
        this.terminalView = view;
        if (view != null) {
            view.setTerminalViewClient(this);
            view.setTextSize(12);
        }
    }

    public void setCallback(SessionCallback callback) {
        this.callback = callback;
    }

    // ==========================================
    // Virtual Keybar Handlers
    // ==========================================

    public void toggleControlKey() {
        this.virtualControlActive = !this.virtualControlActive;
    }

    public boolean isControlKeyActive() {
        return virtualControlActive;
    }

    public void toggleAltKey() {
        this.virtualAltActive = !this.virtualAltActive;
    }

    public boolean isAltKeyActive() {
        return virtualAltActive;
    }

    public void sendBytes(TerminalSession session, byte[] bytes) {
        if (session != null && session.isRunning() && bytes != null && bytes.length > 0) {
            session.write(bytes, 0, bytes.length);
        }
    }

    public void sendString(TerminalSession session, String str) {
        if (str != null) {
            sendBytes(session, str.getBytes(StandardCharsets.UTF_8));
        }
    }

    public void sendEscape(TerminalSession session) {
        sendBytes(session, new byte[]{27});
    }

    public void sendTab(TerminalSession session) {
        sendBytes(session, new byte[]{9});
    }

    public void sendSigInt(TerminalSession session) {
        sendBytes(session, new byte[]{3}); // Ctrl+C
    }

    public void sendEof(TerminalSession session) {
        sendBytes(session, new byte[]{4}); // Ctrl+D
    }

    public void sendArrowUp(TerminalSession session) {
        sendBytes(session, new byte[]{27, '[', 'A'});
    }

    public void sendArrowDown(TerminalSession session) {
        sendBytes(session, new byte[]{27, '[', 'B'});
    }

    public void sendArrowRight(TerminalSession session) {
        sendBytes(session, new byte[]{27, '[', 'C'});
    }

    public void sendArrowLeft(TerminalSession session) {
        sendBytes(session, new byte[]{27, '[', 'D'});
    }

    // ==========================================
    // TerminalSessionClient Implementation
    // ==========================================

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        if (terminalView != null) {
            terminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        if (callback != null && changedSession != null) {
            final String title = changedSession.getTitle();
            mainHandler.post(() -> callback.onTitleChanged(title));
        }
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        if (callback != null && finishedSession != null) {
            final int exitCode = finishedSession.getExitStatus();
            mainHandler.post(() -> callback.onSessionFinished(exitCode));
        }
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("Terminal", text);
            cm.setPrimaryClip(clip);
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        if (session == null || !session.isRunning()) return;
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(context);
                if (text != null && text.length() > 0) {
                    sendString(session, text.toString());
                }
            }
        }
    }

    @Override
    public void onBell(TerminalSession session) {
        // Bell event (optional audio/haptic alert)
    }

    @Override
    public void onColorsChanged(TerminalSession session) {
        if (terminalView != null) {
            terminalView.onScreenUpdated();
        }
    }

    @Override
    public void onTerminalCursorStateChange(boolean state) {
        // Cursor blink state change
    }

    @Override
    public Integer getTerminalCursorStyle() {
        return null; // default block cursor
    }

    @Override
    public void logError(String tag, String message) {
        Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Log.e(tag, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Log.e(tag, "TerminalSession trace", e);
    }

    // ==========================================
    // TerminalViewClient Implementation
    // ==========================================

    @Override
    public float onScale(float scale) {
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        if (terminalView != null) {
            terminalView.requestFocus();
            InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
            }
        }
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        return false;
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        return false;
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession currentSession) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        boolean active = virtualControlActive;
        virtualControlActive = false; // single-shot modifier
        return active;
    }

    @Override
    public boolean readAltKey() {
        boolean active = virtualAltActive;
        virtualAltActive = false; // single-shot modifier
        return active;
    }

    @Override
    public boolean readShiftKey() {
        return false;
    }

    @Override
    public boolean readFnKey() {
        return false;
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
    }
}
