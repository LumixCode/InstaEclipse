package ps.reso.instaeclipse.utils.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IeColors;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;

public class IeDialog {

    public final Context ctx;
    public final float d;
    public final boolean dark;
    public final int bg, field, text, sub, accent, border;
    private final int onAccent, accentText;

    private final Dialog dialog;
    private final LinearLayout card, content, buttons;
    private final TextView titleView;
    private TextView messageView;
    private boolean hasButtons;
    private Runnable onDismiss;
    private Runnable onClose;

    public IeDialog(Context c) {
        Context a = c;
        while (!(a instanceof Activity) && a instanceof android.content.ContextWrapper w && w.getBaseContext() != null)
            a = w.getBaseContext();
        ctx = a instanceof Activity ? a : c;
        d = ctx.getResources().getDisplayMetrics().density;
        IePalette palette = IePalette.of(ctx);
        dark = palette.dark;
        bg = palette.dialog;
        field = palette.field;
        text = palette.text;
        sub = palette.sub;
        accent = palette.accent;
        border = palette.line;
        onAccent = palette.onAccent;
        accentText = IePalette.readable(accent, field, IePalette.TEXT);

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        card.setPadding(pad, dp(9), pad, dp(14));
        card.setBackground(round(bg, 22));

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMarginEnd(-dp(13));
        titleView = new TextView(ctx);
        titleView.setTextColor(text);
        titleView.setTextSize(19);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View close = icon(ctx, "instagram_x_outline_24", "✕", 18, sub);
        close.setContentDescription(I18n.t(ctx, R.string.ig_dialog_close));
        close.setOnClickListener(v -> { dismiss(); if (onClose != null) onClose.run(); });
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(header, hlp);

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(content);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        slp.topMargin = dp(2);
        card.addView(scroll, slp);

        buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        card.addView(buttons, blp);
        buttons.setVisibility(View.GONE);
    }

    public int dp(float v) { return Math.round(v * d); }

    @SuppressLint("UseCompatLoadingForDrawables")
    public static Drawable igIcon(Context ctx, String name, int tint) {
        int id = ResIds.drawable(ctx, name);
        if (id == 0) return null;
        try {
            Drawable icon = ctx.getDrawable(id);
            if (icon == null) return null;
            icon = icon.mutate();
            icon.setTint(tint);
            return icon;
        } catch (Throwable t) {
            return null;
        }
    }

    public static View icon(Context ctx, String name, String fallback, float fallbackSp, int tint) {
        Drawable icon = igIcon(ctx, name, tint);
        if (icon != null) {
            ImageView image = new ImageView(ctx);
            image.setImageDrawable(icon);
            image.setScaleType(ImageView.ScaleType.CENTER);
            return image;
        }
        TextView glyph = new TextView(ctx);
        glyph.setText(fallback);
        glyph.setTextSize(fallbackSp);
        glyph.setTextColor(tint);
        glyph.setGravity(Gravity.CENTER);
        return glyph;
    }

    public GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        return g;
    }

    public GradientDrawable cardBg() {
        GradientDrawable g = round(field, 14);
        g.setStroke(Math.max(1, dp(1)), border);
        return g;
    }

    public IeDialog title(CharSequence t) { titleView.setText(t); return this; }

    public IeDialog message(CharSequence m) {
        if (messageView == null) {
            messageView = new TextView(ctx);
            messageView.setTextColor(text);
            messageView.setTextSize(15);
            messageView.setLineSpacing(3 * d, 1f);
            content.addView(messageView, 0);
        }
        messageView.setText(m);
        return this;
    }

    public IeDialog view(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = content.getChildCount() > 0 ? dp(10) : 0;
        content.addView(v, lp);
        return this;
    }

    public IeDialog choices(CharSequence[] labels, int checked, java.util.function.IntConsumer onPick) {
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == checked;
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(13), dp(14), dp(13));
            GradientDrawable g = cardBg();
            if (on) g.setStroke(Math.max(1, dp(1.5f)), accent);
            row.setBackground(g);
            TextView t = new TextView(ctx);
            t.setText(labels[i]);
            t.setTextColor(on ? accentText : text);
            t.setTextSize(15);
            if (on) t.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (on) {
                TextView tick = new TextView(ctx);
                tick.setText("✓");
                tick.setTextColor(accentText);
                tick.setTextSize(16);
                tick.setTypeface(Typeface.DEFAULT_BOLD);
                tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(tick);
            }
            row.setOnClickListener(v -> { dismiss(); onPick.accept(idx); });
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            CharSequence spoken = labels[i];
            row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(on);
                    info.setText(spoken);
                }

                @Override
                public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
                    super.onInitializeAccessibilityEvent(host, event);
                    event.setClassName(RadioButton.class.getName());
                    event.setChecked(on);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = content.getChildCount() > 0 ? dp(8) : 0;
            content.addView(row, lp);
        }
        return this;
    }

    public TextView quote(CharSequence t, int maxLines) {
        TextView q = new TextView(ctx);
        q.setText(t);
        q.setTextColor(text);
        q.setTextSize(14);
        q.setMaxLines(maxLines);
        q.setEllipsize(android.text.TextUtils.TruncateAt.END);
        q.setPadding(dp(14), dp(12), dp(14), dp(12));
        q.setBackground(round(field, 14));
        view(q);
        return q;
    }

    public EditText input(CharSequence hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextColor(text);
        et.setHintTextColor(sub);
        et.setTextSize(16);
        et.setBackground(round(field, 14));
        et.setPadding(dp(16), dp(13), dp(16), dp(13));
        view(et);
        return et;
    }

    public android.widget.CheckBox checkBox(CharSequence label, boolean checked) {
        android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setTextColor(text);
        cb.setTextSize(15);
        cb.setButtonTintList(ColorStateList.valueOf(accent));
        cb.setPadding(dp(6), dp(6), 0, dp(6));
        view(cb);
        return cb;
    }

    public TextView primary(CharSequence label, Runnable action) { return button(label, action, true, false); }

    public TextView primaryKeepOpen(CharSequence label, Runnable action) { return button(label, action, true, true); }

    public TextView secondary(CharSequence label, Runnable action) { return button(label, action, false, false); }

    public TextView destructive(CharSequence label, Runnable action) { return destructive(label, action, false); }

    public TextView destructiveKeepOpen(CharSequence label, Runnable action) { return destructive(label, action, true); }

    private TextView destructive(CharSequence label, Runnable action, boolean keepOpen) {
        boolean first = !hasButtons;
        TextView b = button(label, action, false, keepOpen);
        b.setTextColor(IeColors.destructive(ctx, bg));
        if (first) buttons.addView(new View(ctx), new LinearLayout.LayoutParams(0, 0, 1));
        return b;
    }

    private TextView button(CharSequence label, Runnable action, boolean filled, boolean keepOpen) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setTextColor(filled ? onAccent : sub);
        if (filled) b.setBackground(round(accent, 20));
        b.setOnClickListener(v -> {
            if (!keepOpen) dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (hasButtons) lp.setMarginStart(dp(8));
        buttons.addView(b, lp);
        buttons.setVisibility(View.VISIBLE);
        hasButtons = true;
        return b;
    }

    public IeDialog cancelable(boolean c) {
        dialog.setCancelable(c);
        dialog.setCanceledOnTouchOutside(c);
        return this;
    }

    public IeDialog onDismiss(Runnable r) { onDismiss = r; return this; }

    public IeDialog onClose(Runnable r) { onClose = r; return this; }

    public Dialog show() {
        if (ctx instanceof Activity a && (a.isFinishing() || a.isDestroyed())) return dialog;
        dialog.setContentView(card);
        dialog.setOnCancelListener(x -> { if (onClose != null) onClose.run(); });
        dialog.setOnDismissListener(x -> { if (onDismiss != null) onDismiss.run(); });
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException e) {
            ModuleLog.warn("(IE|Dialog) The window to show a dialog on is gone", e);
            return dialog;
        }
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    public void dismiss() {
        try { if (dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) {}
    }
}
