package ps.reso.instaeclipse.utils.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.feature.RestartPromptRule;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;

public final class RestartPrompt {

    public static final String TAG = "ie_restart_prompt";

    private static final Set<String> SHOWN = new HashSet<>();
    private static List<String> waiting;
    private static List<String> visible;
    private static WeakReference<View> bar = new WeakReference<>(null);

    private RestartPrompt() {}

    public static synchronized Set<String> shown() {
        return new HashSet<>(SHOWN);
    }

    public static void show(Activity activity, List<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        synchronized (RestartPrompt.class) {
            if (!SHOWN.add(RestartPromptRule.signature(keys))) return;
        }
        List<String> copy = new ArrayList<>(keys);
        Runnable attach = () -> {
            if (usable(activity)) attach(activity, copy);
            else hold(copy);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) attach.run();
        else new Handler(Looper.getMainLooper()).post(attach);
    }

    public static void onMainResume(Activity activity) {
        List<String> keys;
        synchronized (RestartPrompt.class) {
            keys = waiting;
            waiting = null;
        }
        if (keys != null) attach(activity, keys);
    }

    public static synchronized void resetForTest() {
        SHOWN.clear();
        waiting = null;
        visible = null;
        bar = new WeakReference<>(null);
    }

    private static synchronized void hold(List<String> keys) {
        List<String> all = new ArrayList<>(keys);
        if (waiting != null) all.addAll(waiting);
        waiting = RestartPromptRule.order(all);
    }

    private static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed() && activity.getWindow() != null;
    }

    private static void attach(Activity activity, List<String> keys) {
        try {
            ViewGroup content = usable(activity) ? activity.findViewById(android.R.id.content) : null;
            if (content == null) {
                hold(keys);
                return;
            }
            List<String> all = new ArrayList<>(keys);
            View old = bar.get();
            if (old != null && old.getParent() instanceof ViewGroup) {
                if (visible != null) all.addAll(visible);
                ((ViewGroup) old.getParent()).removeView(old);
            }
            all = RestartPromptRule.order(all);
            View view = build(activity, all);
            content.addView(view, params(activity, content));
            bar = new WeakReference<>(view);
            visible = all;
        } catch (Throwable t) {
            ModuleLog.warn("(InstaEclipse) Could not show the restart prompt: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void dismiss(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        if (bar.get() == view) {
            bar = new WeakReference<>(null);
            visible = null;
        }
    }

    static String text(Context context, List<String> keys) {
        List<String> labels = new ArrayList<>();
        for (String key : keys) labels.add(label(context, RestartPromptRule.labelKey(key)));
        return I18n.t(context, R.string.ig_restart_prompt,
                RestartPromptRule.labels(labels, more -> I18n.t(context, R.string.ig_restart_prompt_more, more)));
    }

    private static String label(Context context, String key) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        return item != null ? I18n.t(context, item.label) : key;
    }

    @SuppressLint("ClickableViewAccessibility")
    private static View build(Activity activity, List<String> keys) {
        IePalette p = IePalette.of(activity);
        LinearLayout row = new LinearLayout(activity);
        row.setTag(TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 56));
        row.setPaddingRelative(dp(activity, 16), dp(activity, 6), dp(activity, 4), dp(activity, 6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(p.card);
        background.setCornerRadius(dp(activity, 14));
        background.setStroke(Math.max(1, dp(activity, 1)), p.line);
        row.setBackground(background);
        row.setElevation(dp(activity, 6));
        row.setOnTouchListener((v, event) -> true);

        TextView message = new TextView(activity);
        message.setText(text(activity, keys));
        message.setTextColor(p.text);
        message.setTextSize(14);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = new TextView(activity);
        action.setText(I18n.t(activity, R.string.ig_restart_bar_action));
        action.setTextColor(p.onAccent);
        action.setTextSize(14);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(activity, 48));
        action.setMinWidth(dp(activity, 48));
        action.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(p.accent);
        pill.setCornerRadius(dp(activity, 20));
        action.setBackground(pill);
        action.setOnClickListener(v -> DialogUtils.restart(activity, false));
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.setMarginStart(dp(activity, 8));
        row.addView(action, actionLp);

        View close = IeDialog.icon(activity, "instagram_x_outline_24", "✕", 18, p.sub);
        close.setContentDescription(I18n.t(activity, R.string.ig_dialog_close));
        close.setOnClickListener(v -> dismiss(row));
        row.addView(close, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        return row;
    }

    private static FrameLayout.LayoutParams params(Activity activity, View content) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int margin = dp(activity, 10);
        lp.setMargins(margin, 0, margin, margin + navigationOverlap(content));
        return lp;
    }

    @SuppressWarnings("deprecation")
    private static int navigationOverlap(View content) {
        WindowInsets insets = content.getRootWindowInsets();
        if (insets == null) return 0;
        int[] at = new int[2];
        content.getLocationInWindow(at);
        int below = content.getRootView().getHeight() - (at[1] + content.getHeight());
        return Math.max(0, insets.getSystemWindowInsetBottom() - Math.max(0, below));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
