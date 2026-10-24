package ps.reso.instaeclipse.utils.toast;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;

public class CustomToast {

    public static boolean toastShown = false;

    private static final int AMBER = 0xFFFF9F0A;
    private static final int MAX_NAMES = 3;
    private static final int MAX_LINES = 3;
    private static final int MARGIN_DP = 8;

    public static void showCustomToast(Context context, String message) {
        if (context == null) {
            ModuleLog.detail("(IE|Toast) CustomToast: Context is null");
            return;
        }

        IeToast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show();
    }

    public static void showFeatureGrid(Context context, String title,
                                       java.util.LinkedHashMap<String, Boolean> status) {
        if (context == null || status == null || status.isEmpty()) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                float d = app.getResources().getDisplayMetrics().density;
                Activity top = UIHookManager.topActivity();
                View card = buildCard(app, IePalette.of(top != null ? top : context), title, status);
                FrameLayout wrap = new FrameLayout(app);
                int m = (int) (MARGIN_DP * d);
                wrap.setPadding(m, m, m, m);
                wrap.addView(card);

                Toast toast = new Toast(app);
                toast.setView(wrap);
                toast.setDuration(Toast.LENGTH_LONG);
                toast.setGravity(Gravity.BOTTOM, 0, (int) (80 * d));
                toast.show();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Toast) Could not show the list of active features", t);
            }
        });
    }

    public static View buildCard(Context context, Map<String, Boolean> status) {
        return buildCard(context, IePalette.of(context), I18n.t(context, R.string.ig_toast_features_loaded), status);
    }

    private static View buildCard(Context context, IePalette p, String title, Map<String, Boolean> status) {
        float d = context.getResources().getDisplayMetrics().density;
        int active = 0;
        int pending = 0;
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : status.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) active++;
            else if (FeatureStatusTracker.isBroken(e.getKey())) broken.add(FeatureStatusTracker.getLabel(context, e.getKey()));
            else pending++;
        }
        Collections.sort(broken);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (12 * d), (int) (10 * d), (int) (16 * d), (int) (12 * d));
        card.setBackground(IeToast.cardShape(p, d));
        card.setElevation(6 * d);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int size = (int) (IeToast.BADGE_DP * d);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(size, size);
        badgeLp.setMarginEnd((int) (IeToast.BADGE_GAP_DP * d));
        head.addView(IeToast.badge(context, p, d), badgeLp);
        TextView titleView = text(context, title, p.text, 15, true);
        head.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        int chipColor = active == status.size() ? p.success : AMBER;
        TextView chip = text(context, I18n.t(context, R.string.menu_chip_active, active, status.size()),
                IePalette.readable(chipColor, p.dialog, IePalette.TEXT), 13, true);
        chip.setPaddingRelative((int) (8 * d), 0, 0, 0);
        head.addView(chip);
        card.addView(head);

        int indent = (int) ((IeToast.BADGE_DP + IeToast.BADGE_GAP_DP) * d);
        int muted = IePalette.readable(p.sub, p.dialog, IePalette.TEXT);
        if (!broken.isEmpty()) {
            card.addView(line(context, notWorking(context, broken), IePalette.readable(p.danger, p.dialog, IePalette.TEXT), indent));
        }
        if (pending > 0) card.addView(line(context, I18n.t(context, R.string.ie_toast_starting, pending), muted, indent));
        if (broken.isEmpty() && pending == 0) {
            card.addView(line(context, I18n.t(context, R.string.menu_inactive_all_good), muted, indent));
        }

        int width = context.getResources().getDisplayMetrics().widthPixels - (int) (2 * MARGIN_DP * d);
        card.setLayoutParams(new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private static String notWorking(Context context, List<String> labels) {
        List<String> shown = labels.subList(0, Math.min(MAX_NAMES, labels.size()));
        String names = String.join(", ", shown);
        int more = labels.size() - shown.size();
        if (more > 0) names += " " + I18n.t(context, R.string.ie_toast_more, more);
        return I18n.t(context, R.string.ie_toast_not_working, names);
    }

    private static TextView line(Context context, String value, int color, int indent) {
        TextView view = text(context, value, color, 13, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(indent);
        lp.topMargin = (int) (4 * context.getResources().getDisplayMetrics().density);
        view.setLayoutParams(lp);
        return view;
    }

    private static TextView text(Context context, String value, int color, int sp, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setMaxLines(MAX_LINES);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }
}
