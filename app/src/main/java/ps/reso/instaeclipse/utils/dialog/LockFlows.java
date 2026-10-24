package ps.reso.instaeclipse.utils.dialog;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.LockDirectMessagesHook;
import ps.reso.instaeclipse.utils.core.PasscodeRules;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.ui.IeColors;
import ps.reso.instaeclipse.utils.ui.IePalette;

public final class LockFlows {

    private static boolean askedThisStart;

    private LockFlows() {}

    public static boolean hasPasscode() {
        return SettingsManager.passcodeSet(FeatureFlags.lockDirectPasscode);
    }

    public static boolean authenticate(Activity activity, String title, String gate, Runnable onSuccess) {
        return LockDirectMessagesHook.authenticate(activity, title, gate, onSuccess);
    }

    public static void removePasscode() {
        PasscodeRules.removePasscode();
    }

    public static void setPasscode(Activity activity, Runnable onSaved, Runnable onCancel) {
        if (activity == null) return;
        if (hasPasscode()) {
            authenticate(activity, I18n.t(activity, R.string.ig_lock_settings_prompt), "settings",
                    () -> newPasscode(activity, true, onSaved, onCancel));
            return;
        }
        newPasscode(activity, false, onSaved, onCancel);
    }

    private static void newPasscode(Activity activity, boolean verified, Runnable onSaved, Runnable onCancel) {
        try {
            IeDialog dlg = new IeDialog(activity).title(I18n.t(activity, R.string.ig_lock_set_passcode))
                    .message(I18n.t(activity, R.string.ig_lock_passcode_rule));
            EditText first = pinInput(dlg, I18n.t(activity, R.string.ig_lock_new_passcode));
            EditText second = pinInput(dlg, I18n.t(activity, R.string.ig_lock_repeat_passcode));
            TextView error = new TextView(dlg.ctx);
            error.setTextColor(IeColors.destructive(dlg.ctx, dlg.bg));
            error.setTextSize(13);
            error.setVisibility(View.GONE);
            dlg.view(error);
            dlg.onClose(onCancel);
            dlg.secondary(activity.getString(android.R.string.cancel), onCancel);
            dlg.primaryKeepOpen(I18n.t(activity, R.string.ig_lock_save), () -> {
                PasscodeRules.Result result = PasscodeRules.checkNew(first.getText().toString(), second.getText().toString());
                if (result != PasscodeRules.Result.OK) {
                    error.setText(I18n.t(activity, errorText(result)));
                    error.setVisibility(View.VISIBLE);
                    if (result == PasscodeRules.Result.MISMATCH) second.setText("");
                    return;
                }
                Runnable save = () -> {
                    LockDirectMessagesHook.newSalt();
                    FeatureFlags.lockDirectPasscode = LockDirectMessagesHook.hashPass(first.getText().toString());
                    SettingsManager.saveAllFlags();
                    IeToast.makeText(activity, I18n.t(activity, R.string.ig_dialog_misc_lock_dms_saved), Toast.LENGTH_SHORT).show();
                    dlg.dismiss();
                    if (onSaved != null) onSaved.run();
                };
                if (!verified && hasPasscode()) {
                    authenticate(activity, I18n.t(activity, R.string.ig_lock_settings_prompt), "settings", save);
                } else {
                    save.run();
                }
            });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Lock) Could not open the new passcode prompt", t);
        }
    }

    public static void askAtResume(Activity activity) {
        if (activity == null) return;
        SharedPreferences prefs = SettingsManager.prefs();
        String muted = prefs != null ? prefs.getString(PasscodeRules.MUTED, null) : null;
        if (!PasscodeRules.askAtResume(askedThisStart, muted)) return;
        askedThisStart = true;
        try {
            IeDialog dlg = new IeDialog(activity).title(I18n.t(activity, R.string.ig_lock_needs_passcode_title))
                    .message(I18n.t(activity, R.string.ig_lock_needs_passcode_body));
            LinearLayout column = new LinearLayout(dlg.ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(choice(dlg, I18n.t(activity, R.string.ig_lock_set_passcode), true, () ->
                    setPasscode(activity, null, null)));
            column.addView(choice(dlg, I18n.t(activity, R.string.ig_lock_turn_locks_off), false, () -> {
                PasscodeRules.turnLocksOff();
                SettingsManager.saveAllFlags();
            }));
            column.addView(choice(dlg, I18n.t(activity, R.string.ig_lock_not_now), false, () -> {
                SharedPreferences p = SettingsManager.prefs();
                if (p != null) p.edit().putString(PasscodeRules.MUTED, PasscodeRules.lockSet()).apply();
            }));
            dlg.view(column);
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Lock) Could not ask for a passcode", t);
        }
    }

    public static Activity activity(Context context) {
        Context c = context;
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    private static int errorText(PasscodeRules.Result result) {
        switch (result) {
            case EMPTY: return R.string.ig_lock_error_empty;
            case NOT_DIGITS: return R.string.ig_lock_error_digits;
            case TOO_SHORT: return R.string.ig_lock_error_short;
            default: return R.string.ig_lock_error_mismatch;
        }
    }

    private static EditText pinInput(IeDialog dlg, String hint) {
        EditText e = dlg.input(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(20);
        e.setLetterSpacing(0.2f);
        return e;
    }

    private static TextView choice(IeDialog dlg, String label, boolean filled, Runnable action) {
        IePalette p = IePalette.of(dlg.ctx);
        TextView b = new TextView(dlg.ctx);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dlg.dp(44));
        b.setPadding(dlg.dp(16), dlg.dp(10), dlg.dp(16), dlg.dp(10));
        b.setTextColor(filled ? p.onAccent : IePalette.readable(dlg.accent, dlg.bg, IePalette.TEXT));
        b.setBackground(filled ? dlg.round(dlg.accent, 22) : dlg.cardBg());
        b.setOnClickListener(v -> {
            dlg.dismiss();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dlg.dp(8);
        b.setLayoutParams(lp);
        return b;
    }
}
