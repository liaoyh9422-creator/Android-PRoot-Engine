package com.android.proot.sample.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * UI Theme & Widget Factory referencing CLIProxyAPI's dark geek aesthetics.
 * Provides GitHub Dark palette colors, rounded card drawables, micro-capsule buttons, and badge widgets.
 */
public class UiTheme {
    // Background and Surface Palette
    public static final String C_BG          = "#0D1117";
    public static final String C_SURFACE     = "#161B22";
    public static final String C_SURFACE_ALT = "#21262D";
    public static final String C_BORDER      = "#30363D";
    public static final String C_BORDER_SUB  = "#21262D";
    public static final String C_TEXT        = "#E6EDF3";
    public static final String C_DIM         = "#8B949E";

    // Status and Accent Palette
    public static final String C_GREEN       = "#2EA043";
    public static final String C_GREEN_BG    = "#102B19";
    public static final String C_BLUE        = "#58A6FF";
    public static final String C_BLUE_BG     = "#16263D";
    public static final String C_PURPLE      = "#BC8CFF";
    public static final String C_PURPLE_BG   = "#1A102F";
    public static final String C_RED         = "#DA3633";
    public static final String C_RED_BG      = "#2C1517";
    public static final String C_CYAN        = "#39C5CF";
    public static final String C_CYAN_BG     = "#0A2328";
    public static final String C_YELLOW      = "#D29922";
    public static final String C_YELLOW_BG   = "#271E0B";

    /** dp to px conversion */
    public static int dp(Context ctx, int s) {
        return (int) (s * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Create rounded rectangular gradient drawable with optional stroke */
    public static GradientDrawable roundRect(Context ctx, String bgColor, String strokeColor, int strokeWidthDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        if (bgColor != null) {
            gd.setColor(Color.parseColor(bgColor));
        }
        if (strokeColor != null && strokeWidthDp > 0) {
            gd.setStroke(dp(ctx, strokeWidthDp), Color.parseColor(strokeColor));
        }
        gd.setCornerRadius(dp(ctx, radiusDp));
        return gd;
    }

    /** Create micro-capsule button */
    public static TextView createButton(Context ctx, String text, String textColor, String bgColor, String strokeColor, int radiusDp) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(12f);
        btn.setTextColor(Color.parseColor(textColor));
        btn.setBackground(roundRect(ctx, bgColor, strokeColor, 1, radiusDp > 0 ? radiusDp : 6));
        btn.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7));
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setIncludeFontPadding(false);
        return btn;
    }

    /** Create small status indicator dot */
    public static View createDot(Context ctx, String colorHex, int sizeDp) {
        View dot = new View(ctx);
        int px = dp(ctx, sizeDp > 0 ? sizeDp : 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px, px);
        lp.gravity = Gravity.CENTER_VERTICAL;
        dot.setLayoutParams(lp);

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(Color.parseColor(colorHex));
        dot.setBackground(gd);
        return dot;
    }

    /** Create badge widget */
    public static TextView createBadge(Context ctx, String text, String textColor, String bgColor, String strokeColor) {
        TextView badge = new TextView(ctx);
        badge.setText(text);
        badge.setTextSize(10f);
        badge.setTypeface(Typeface.MONOSPACE);
        badge.setTextColor(Color.parseColor(textColor));
        badge.setBackground(roundRect(ctx, bgColor, strokeColor, 1, 4));
        badge.setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2));
        badge.setGravity(Gravity.CENTER);
        badge.setIncludeFontPadding(false);
        return badge;
    }

    /** Setup immersive status bar and navigation bar */
    public static void setupImmersiveStatusBar(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = activity.getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor(C_BG));
            window.setNavigationBarColor(Color.parseColor(C_BG));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }
}
