package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.FancyText;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

/**
 * Edit profile: the bio's "Change font" button (an Instagram Plus upsell) opens the module's
 * fancy-text picker instead. The bio is rewritten with Unicode look-alike letters
 * ({@link FancyText}), so it is plain text that shows the same for everyone. Views are found by
 * their resource names, nothing obfuscated.
 */
public final class FancyBioFontHook {

    private static int bioId, cardId, labelId;
    static volatile Runnable rewire = FancyBioFontHook::rewireTopScreen;

    private FancyBioFontHook() {}

    public static void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("com.instagram.igds.components.form.IgFormField", loader,
                    "setupSecondRightAddOnButton", CharSequence.class, boolean.class, "kotlin.jvm.functions.Function0",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (!FeatureFlags.fancyBioFont || !(param.thisObject instanceof View field)) return;
                            if (UIHookManager.dialogHost(field.getContext()) instanceof Activity a && ids(a)) wire(a, field);
                        }
                    });
            FeatureStatusTracker.setHooked("FancyBioFont");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|FancyFont) The bio's font button keeps Instagram's own action", t);
            if (FeatureFlags.fancyBioFont) FeatureStatusTracker.setBroken("FancyBioFont");
        }
    }

    public static void switched() {
        if (FeatureFlags.fancyBioFont) rewire.run();
    }

    private static void rewireTopScreen() {
        Activity a = UIHookManager.topActivity();
        if (a == null || a.isFinishing() || !ids(a)) return;
        View bio = a.getWindow().getDecorView().findViewById(bioId);
        if (bio != null) wire(a, bio);
    }

    private static boolean ids(Activity a) {
        if (bioId == 0 || cardId == 0) {
            String pkg = a.getPackageName();
            bioId = a.getResources().getIdentifier("bio", "id", pkg);
            cardId = a.getResources().getIdentifier("form_field_second_right_add_on_button_card", "id", pkg);
            labelId = a.getResources().getIdentifier("form_field_second_right_add_on_button_text", "id", pkg);
        }
        return bioId != 0 && cardId != 0;
    }

    private static void wire(Activity a, View bio) {
        View card = bio.findViewById(cardId);
        EditText input = input(bio);
        if (card == null || input == null) return;
        String label = I18n.t(a, R.string.ig_fancy_font_button);
        TextView text = labelId != 0 ? card.findViewById(labelId) : null;
        if (text != null && text.getText().toString().startsWith(label) && card.getTag(R.id.ie_fancy_font) != null) return;
        if (text != null) text.setText(label);
        hideIcons(card);
        View.OnClickListener open = v -> pick(a, input);
        card.setOnClickListener(open);
        if (text != null) text.setOnClickListener(open);
        card.setTag(R.id.ie_fancy_font, Boolean.TRUE);
    }

    /** The bio's text field (the form field around it reports itself as an EditText too). */
    private static EditText input(View v) {
        if (v instanceof EditText e && !(v instanceof ViewGroup)) return e;
        if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText e = input(g.getChildAt(i));
                if (e != null) return e;
            }
        return null;
    }

    /** The upsell's gem icon: it's no longer a paid feature. */
    private static void hideIcons(View v) {
        if (v instanceof ImageView) v.setVisibility(View.GONE);
        else if (v instanceof TextView t) {
            t.setCompoundDrawablesRelative(null, null, null, null);
            t.setCompoundDrawables(null, null, null, null);
        }
        else if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) hideIcons(g.getChildAt(i));
    }

    /** The style new typing gets, per bio field (0 = normal). */
    private static final Map<EditText, Integer> STYLE = Collections.synchronizedMap(new WeakHashMap<>());

    public static void pick(Activity a, EditText input) {
        String[] names = names(a);
        CharSequence[] rows = new CharSequence[FancyText.STYLE_COUNT];
        for (int i = 0; i < rows.length; i++)
            rows[i] = names != null ? FancyText.convert("Abc 123", i) + "   ·  " + names[i] : FancyText.convert("Abc 123", i);
        Integer current = STYLE.get(input);
        IeDialog dlg = new IeDialog(a).title(I18n.t(a, R.string.ig_fancy_font_title));
        dlg.message(I18n.t(a, R.string.ig_fancy_font_note));
        dlg.choices(rows, current != null ? current : 0, i -> {
            if (!STYLE.containsKey(input)) input.addTextChangedListener(new Typing(input));
            STYLE.put(input, i);
            View bio = a.getWindow().getDecorView().findViewById(bioId);
            TextView label = bio != null && labelId != 0 ? bio.findViewById(labelId) : null;
            if (label != null) label.setText(i == 0 || names == null ? I18n.t(a, R.string.ig_fancy_font_button)
                    : I18n.t(a, R.string.ig_fancy_font_button) + ": " + names[i]);
            input.requestFocus();
        });
        dlg.show();
    }

    private static String[] names(Activity a) {
        try {
            String[] names = ModuleResources.get(a).getStringArray(R.array.ig_fancy_font_names);
            if (names.length == FancyText.STYLE_COUNT) return names;
            ModuleLog.warn("(IE|FancyFont) The font style names do not match the styles, so only examples are shown");
        } catch (Throwable t) {
            ModuleLog.warn("(IE|FancyFont) The font style names could not be loaded, so only examples are shown", t);
        }
        return null;
    }

    /**
     * Styles what's typed from now on; text already there keeps its look. Keyboards rewrite the
     * word being typed (the "composing" region) on every key, so that region is re-styled each
     * time and its composing span is put back over the styled text, or the keyboard would lose
     * track of it and repeat the word.
     */
    private static final class Typing implements android.text.TextWatcher {
        private final EditText input;
        private int start, count;
        private boolean busy;

        Typing(EditText input) { this.input = input; }

        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

        @Override
        public void onTextChanged(CharSequence s, int st, int before, int c) {
            start = st;
            count = c;
        }

        @Override
        public void afterTextChanged(android.text.Editable e) {
            Integer style = STYLE.get(input);
            if (busy || style == null || style == 0 || count <= 0 || start + count > e.length()) return;
            String typed = e.subSequence(start, start + count).toString();
            String styled = FancyText.convert(typed, style);
            if (styled.equals(typed)) return;
            Object[] spans = e.getSpans(start, start + count, Object.class);
            int[][] where = new int[spans.length][];
            for (int i = 0; i < spans.length; i++)
                where[i] = new int[]{e.getSpanStart(spans[i]), e.getSpanEnd(spans[i]), e.getSpanFlags(spans[i])};
            int delta = styled.length() - typed.length();
            busy = true;
            try {
                e.replace(start, start + count, styled);
                for (int i = 0; i < spans.length; i++) {
                    if ((where[i][2] & android.text.Spanned.SPAN_COMPOSING) == 0) continue;
                    int s0 = where[i][0], s1 = where[i][1] + (where[i][1] >= start + count ? delta : 0);
                    if (s0 >= 0 && s1 >= s0 && s1 <= e.length()) e.setSpan(spans[i], s0, s1, where[i][2]);
                }
            } finally {
                busy = false;
            }
        }
    }
}
