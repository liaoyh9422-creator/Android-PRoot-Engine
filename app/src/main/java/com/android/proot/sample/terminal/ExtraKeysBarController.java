package com.android.proot.sample.terminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import com.android.proot.sample.R;
import com.android.proot.sample.ui.UiTheme;
import com.termux.terminal.TerminalSession;

/**
 * Manages the two-row PC-standard virtual terminal extra keys bar.
 * Row 1 (8 keys): [ESC]  [TAB]  [ / ]  [ - ]  [ ~ ]  [HOME] [ ▲ ]  [END]
 * Row 2 (8 keys): [CTRL] [ALT]  [ | ]  [ ^C ] [ ^D ] [ ◀ ]  [ ▼ ]  [ ▶ ]
 * Features classic PC inverted-T arrow keys with HOME/END overhead and tactile feedback.
 */
public final class ExtraKeysBarController {
    public interface SessionProvider {
        TerminalSession getCurrentSession();
    }

    private final Context context;
    private final TerminalBridge terminalBridge;
    private final SessionProvider sessionProvider;

    // Row 1 Keys
    private TextView keyEsc;
    private TextView keyTab;
    private TextView keySlash;
    private TextView keyDash;
    private TextView keyTilde;
    private TextView keyHome;
    private TextView keyUp;
    private TextView keyEnd;

    // Row 2 Keys
    private TextView keyCtrl;
    private TextView keyAlt;
    private TextView keyPipe;
    private TextView keySigint;
    private TextView keyEof;
    private TextView keyLeft;
    private TextView keyDown;
    private TextView keyRight;

    public ExtraKeysBarController(Context context, TerminalBridge terminalBridge, SessionProvider sessionProvider) {
        this.context = context;
        this.terminalBridge = terminalBridge;
        this.sessionProvider = sessionProvider;
    }

    public void bindViews(View rootView) {
        if (rootView == null) return;
        // Row 1
        keyEsc = rootView.findViewById(R.id.key_esc);
        keyTab = rootView.findViewById(R.id.key_tab);
        keySlash = rootView.findViewById(R.id.key_slash);
        keyDash = rootView.findViewById(R.id.key_dash);
        keyTilde = rootView.findViewById(R.id.key_tilde);
        keyHome = rootView.findViewById(R.id.key_home);
        keyUp = rootView.findViewById(R.id.key_up);
        keyEnd = rootView.findViewById(R.id.key_end);

        // Row 2
        keyCtrl = rootView.findViewById(R.id.key_ctrl);
        keyAlt = rootView.findViewById(R.id.key_alt);
        keyPipe = rootView.findViewById(R.id.key_pipe);
        keySigint = rootView.findViewById(R.id.key_sigint);
        keyEof = rootView.findViewById(R.id.key_eof);
        keyLeft = rootView.findViewById(R.id.key_left);
        keyDown = rootView.findViewById(R.id.key_down);
        keyRight = rootView.findViewById(R.id.key_right);
    }

    public void applyUiTheme() {
        TextView[] allKeys = new TextView[]{
                keyEsc, keyTab, keySlash, keyDash, keyTilde, keyHome, keyUp, keyEnd,
                keyCtrl, keyAlt, keyPipe, keySigint, keyEof, keyLeft, keyDown, keyRight
        };

        for (TextView k : allKeys) {
            if (k != null) {
                styleKeyButton(k);
            }
        }
        updateKeyModifierStyles();
    }

    private void styleKeyButton(TextView keyView) {
        keyView.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        keyView.setTextSize(10f);
        keyView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        keyView.setBackground(UiTheme.createTactileKeyDrawable(context, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, UiTheme.C_BORDER_SUB, 1, 4));
        keyView.setPadding(UiTheme.dp(context, 4), UiTheme.dp(context, 4), UiTheme.dp(context, 4), UiTheme.dp(context, 4));
        keyView.setGravity(Gravity.CENTER);
        keyView.setClickable(true);
        keyView.setFocusable(true);
        keyView.setIncludeFontPadding(false);
        UiTheme.applyTactileFeedback(keyView);
    }

    public void updateKeyModifierStyles() {
        if (keyCtrl != null) {
            if (terminalBridge != null && terminalBridge.isControlKeyActive()) {
                keyCtrl.setBackground(UiTheme.roundRect(context, UiTheme.C_BLUE_BG, UiTheme.C_BLUE, 1, 4));
                keyCtrl.setTextColor(Color.parseColor(UiTheme.C_BLUE));
            } else {
                keyCtrl.setBackground(UiTheme.createTactileKeyDrawable(context, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, UiTheme.C_BORDER_SUB, 1, 4));
                keyCtrl.setTextColor(Color.parseColor(UiTheme.C_TEXT));
            }
        }

        if (keyAlt != null) {
            if (terminalBridge != null && terminalBridge.isAltKeyActive()) {
                keyAlt.setBackground(UiTheme.roundRect(context, UiTheme.C_PURPLE_BG, UiTheme.C_PURPLE, 1, 4));
                keyAlt.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
            } else {
                keyAlt.setBackground(UiTheme.createTactileKeyDrawable(context, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, UiTheme.C_BORDER_SUB, 1, 4));
                keyAlt.setTextColor(Color.parseColor(UiTheme.C_TEXT));
            }
        }
    }

    public void setupListeners() {
        if (terminalBridge == null) return;

        // Row 1
        if (keyEsc != null) keyEsc.setOnClickListener(v -> terminalBridge.sendEscape(sessionProvider.getCurrentSession()));
        if (keyTab != null) keyTab.setOnClickListener(v -> terminalBridge.sendTab(sessionProvider.getCurrentSession()));
        if (keySlash != null) keySlash.setOnClickListener(v -> terminalBridge.sendString(sessionProvider.getCurrentSession(), "/"));
        if (keyDash != null) keyDash.setOnClickListener(v -> terminalBridge.sendString(sessionProvider.getCurrentSession(), "-"));
        if (keyTilde != null) keyTilde.setOnClickListener(v -> terminalBridge.sendString(sessionProvider.getCurrentSession(), "~"));
        if (keyHome != null) keyHome.setOnClickListener(v -> terminalBridge.sendHome(sessionProvider.getCurrentSession()));
        if (keyUp != null) keyUp.setOnClickListener(v -> terminalBridge.sendArrowUp(sessionProvider.getCurrentSession()));
        if (keyEnd != null) keyEnd.setOnClickListener(v -> terminalBridge.sendEnd(sessionProvider.getCurrentSession()));

        // Row 2
        if (keyCtrl != null) keyCtrl.setOnClickListener(v -> {
            terminalBridge.toggleControlKey();
            updateKeyModifierStyles();
        });
        if (keyAlt != null) keyAlt.setOnClickListener(v -> {
            terminalBridge.toggleAltKey();
            updateKeyModifierStyles();
        });
        if (keyPipe != null) keyPipe.setOnClickListener(v -> terminalBridge.sendString(sessionProvider.getCurrentSession(), "|"));
        if (keySigint != null) keySigint.setOnClickListener(v -> terminalBridge.sendSigInt(sessionProvider.getCurrentSession()));
        if (keyEof != null) keyEof.setOnClickListener(v -> terminalBridge.sendEof(sessionProvider.getCurrentSession()));
        if (keyLeft != null) keyLeft.setOnClickListener(v -> terminalBridge.sendArrowLeft(sessionProvider.getCurrentSession()));
        if (keyDown != null) keyDown.setOnClickListener(v -> terminalBridge.sendArrowDown(sessionProvider.getCurrentSession()));
        if (keyRight != null) keyRight.setOnClickListener(v -> terminalBridge.sendArrowRight(sessionProvider.getCurrentSession()));
    }
}
