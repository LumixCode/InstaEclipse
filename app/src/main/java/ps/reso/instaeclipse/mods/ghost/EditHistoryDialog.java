package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.TimestampFormat;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * The "Edited" history: one card per version, oldest first, each headed by what it is (original,
 * earlier version, current) and when it appeared, in the "Show exact dates" format. Times this
 * phone only noticed (the edit arrived while the module watched) say so.
 */
final class EditHistoryDialog {

    private EditHistoryDialog() {}

    /** Shows the rows on the resumed activity; {@code missing} edits have no saved version. */
    static void show(List<EditHistoryTimeline.Row> rows, int missing) {
        Activity host = resumedActivity();
        if (host == null) {
            ModuleLog.warn("(IE|EditHistory) Could not show the edit history: no open screen found");
            return;
        }
        IeDialog dlg = new IeDialog(host).title(I18n.t(host, R.string.ig_edit_history_title));
        for (EditHistoryTimeline.Row row : rows) dlg.view(card(dlg, host, row));
        if (missing > 0) {
            TextView note = new TextView(dlg.ctx);
            note.setText(I18n.t(host, R.string.ig_edit_history_missing, missing));
            note.setTextColor(dlg.sub);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            dlg.view(note);
        }
        dlg.show();
    }

    /** Says plainly that nothing earlier was saved for this message. */
    static void none() {
        Context ctx = resumedActivity();
        if (ctx == null) ctx = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        if (ctx == null) return;
        try {
            IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_edit_history_none), Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private static LinearLayout card(IeDialog dlg, Context ctx, EditHistoryTimeline.Row row) {
        boolean current = row.kind == EditHistoryTimeline.Kind.CURRENT;
        LinearLayout box = new LinearLayout(dlg.ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dlg.dp(16), dlg.dp(10), dlg.dp(14), dlg.dp(11));
        android.graphics.drawable.GradientDrawable bg = dlg.cardBg();
        if (current) bg.setStroke(Math.max(1, dlg.dp(1.5f)), dlg.accent);
        box.setBackground(bg);
        TextView label = new TextView(dlg.ctx);
        label.setText(label(ctx, row));
        label.setTextColor(current ? dlg.accent : dlg.sub);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, 0, 0, dlg.dp(3));
        TextView text = new TextView(dlg.ctx);
        text.setText(row.text);
        text.setTextColor(dlg.text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setLineSpacing(2 * dlg.d, 1f);
        text.setTextIsSelectable(true);
        box.addView(label);
        box.addView(text);
        return box;
    }

    private static String label(Context ctx, EditHistoryTimeline.Row row) {
        int kind = switch (row.kind) {
            case ORIGINAL -> R.string.ig_edit_history_original;
            case EARLIER -> R.string.ig_edit_history_earlier;
            case CURRENT -> R.string.ig_edit_history_current;
        };
        String when;
        if (row.time <= 0) {
            when = I18n.t(ctx, R.string.ig_edit_history_time_unknown);
        } else {
            when = TimestampFormat.format(FeatureFlags.exactTimestampFormat, new Date(row.time), Locale.getDefault());
            if (row.noticed) when = I18n.t(ctx, R.string.ig_edit_history_noticed, when);
        }
        return I18n.t(ctx, kind) + "  ·  " + when;
    }

    /** This process's resumed activity (a chat can sit in its own activity above the main one). */
    private static Activity resumedActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field records = at.getDeclaredField("mActivities");
            records.setAccessible(true);
            Map<?, ?> all = (Map<?, ?>) records.get(thread);
            if (all != null) {
                for (Object r : all.values()) {
                    java.lang.reflect.Field paused = r.getClass().getDeclaredField("paused");
                    paused.setAccessible(true);
                    if (paused.getBoolean(r)) continue;
                    java.lang.reflect.Field activity = r.getClass().getDeclaredField("activity");
                    activity.setAccessible(true);
                    if (activity.get(r) instanceof Activity a && !a.isFinishing()) return a;
                }
            }
        } catch (Throwable t) {
            ModuleLog.detail("(IE|EditHistory) resumed activity lookup failed: " + t);
        }
        Activity main = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        return main != null && !main.isFinishing() ? main : null;
    }
}
