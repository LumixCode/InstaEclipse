package ps.reso.instaeclipse.utils.toast;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;

/**
 * The module's toast: a rounded card in the IeDialog palette (dark / light) with the InstaEclipse
 * mark on the left, faded and slid up as it appears. Drop-in for Toast.makeText(…).show(), safe
 * to call from any thread and from both Instagram's process and the companion app.
 */
public final class IeToast {

    static final int BADGE_DP = 28;
    static final int BADGE_GAP_DP = 10;

    private final Context ctx;
    private final CharSequence text;
    private final int duration;

    private IeToast(Context ctx, CharSequence text, int duration) {
        this.ctx = ctx;
        this.text = text;
        this.duration = duration;
    }

    public static IeToast makeText(Context ctx, CharSequence text, int duration) {
        return new IeToast(ctx, text, duration);
    }

    public static IeToast makeText(Context ctx, int resId, int duration) {
        CharSequence t;
        // Inside Instagram ctx.getText would read Instagram's table, where our ids mean nothing.
        try { t = ModuleResources.get(ctx).getText(resId); } catch (Throwable e) { t = ""; }
        return new IeToast(ctx, t, duration);
    }

    public void show() {
        if (ctx == null || text == null || text.length() == 0) return;
        Runnable r = () -> {
            try {
                if (inForeground(ctx)) showNow();
                else Toast.makeText(ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx,
                        text, duration).show();
            } catch (Throwable t) {
                ModuleLog.detail("(IE|Toast) fallback: " + t);
                try { Toast.makeText(ctx, text, duration).show(); } catch (Throwable ignored) {}
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else new Handler(Looper.getMainLooper()).post(r);
    }

    private void showNow() {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        float d = app.getResources().getDisplayMetrics().density;
        Activity top = UIHookManager.topActivity();
        IePalette palette = IePalette.of(top != null ? top : ctx);
        int fg = palette.text;

        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int) (12 * d), (int) (10 * d), (int) (18 * d), (int) (10 * d));
        card.setBackground(cardShape(palette, d));
        card.setElevation(6 * d);

        int size = (int) (BADGE_DP * d);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(size, size);
        mlp.setMarginEnd((int) (BADGE_GAP_DP * d));
        card.addView(badge(app, palette, d), mlp);

        TextView tv = new TextView(app);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setMaxLines(4);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth((int) (app.getResources().getDisplayMetrics().widthPixels * 0.78f));
        card.addView(tv);

        // A little room so the elevation shadow isn't clipped by the toast window.
        LinearLayout root = new LinearLayout(app);
        int m = (int) (8 * d);
        root.setPadding(m, m, m, m);
        root.addView(card);

        Toast toast = new Toast(app);
        toast.setView(root);
        toast.setDuration(duration == Toast.LENGTH_LONG ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, (int) (96 * d));
        card.setAlpha(0f);
        card.setTranslationY(14 * d);
        card.animate().alpha(1f).translationY(0f).setDuration(220).start();
        toast.show();
    }

    static GradientDrawable cardShape(IePalette palette, float d) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(IePalette.withAlpha(palette.dialog, palette.dark ? 0xF2 : 0xF7));
        shape.setCornerRadius(22 * d);
        shape.setStroke(Math.max(1, (int) d), palette.line);
        return shape;
    }

    static ImageView badge(Context app, IePalette palette, float d) {
        ImageView mark = new ImageView(app);
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(palette.accent);
        mark.setBackground(badge);
        Drawable logo = ModuleResources.drawable(app, R.drawable.ic_logo);
        if (logo != null) mark.setImageDrawable(logo);
        int pad = (int) (5 * d);
        mark.setPadding(pad, pad, pad, pad);
        return mark;
    }

    /**
     * Android 11+ silently drops custom-view toasts from a process that isn't in the foreground
     * (e.g. the companion app's DownloadSaveService while Instagram is on screen), so those get a
     * plain text toast instead.
     */
    private static boolean inForeground(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true;
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        } catch (Throwable t) {
            return true;
        }
    }
}
