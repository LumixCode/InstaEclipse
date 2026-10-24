package ps.reso.instaeclipse.utils.dialog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;

final class IeRows {

    static final int R_CARD = 9;

    private IeRows() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int hairline(Context context) {
        return Math.max(1, Math.round(context.getResources().getDisplayMetrics().density / 3f));
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    static Drawable icon(Context context, int iconRes, int tintColor) {
        try {
            Drawable icon = ModuleResources.get(context).getDrawable(iconRes, null);
            icon = icon.mutate();
            icon.setColorFilter(new android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN));
            return icon;
        } catch (Exception e) {
            ModuleLog.warn("(IE|Resources) An icon could not be loaded (0x" + Integer.toHexString(iconRes) + ")", e);
            return null;
        }
    }

    static Drawable ripple(Context context, int cornerDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(context, cornerDp));
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(IePalette.of(context).pressed), null, mask);
    }

    private static StateListDrawable pressedBackground(Context context) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(IePalette.of(context).pressed));
        bg.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return bg;
    }

    static TextView sectionHeader(Context context, String text) {
        TextView header = new TextView(context);
        header.setText(text == null ? "" : text.toUpperCase(java.util.Locale.getDefault()));
        header.setTextColor(IePalette.of(context).sub);
        header.setTextSize(12);
        header.setTypeface(null, Typeface.BOLD);
        header.setLetterSpacing(0.08f);
        header.setPadding(dp(context, 7), dp(context, 7), dp(context, 5), dp(context, 3));
        return header;
    }

    static LinearLayout card(Context context, int topMarginDp) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(IePalette.of(context).card);
        bg.setCornerRadius(dp(context, R_CARD));
        group.setBackground(bg);
        int pad = dp(context, 2);
        group.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, topMarginDp);
        group.setLayoutParams(lp);
        return group;
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 8)));
        divider.setBackgroundColor(Color.TRANSPARENT);
        return divider;
    }

    static TextView helpText(Context context, CharSequence text) {
        TextView help = new TextView(context);
        help.setText(text);
        help.setTextSize(13);
        help.setTextColor(IePalette.of(context).sub);
        help.setPadding(dp(context, 13), dp(context, 8), dp(context, 11), dp(context, 8));
        return help;
    }

    static View valueRow(Context context, String label, String value) {
        IePalette p = IePalette.of(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(context, 13), dp(context, 8), dp(context, 11), dp(context, 8));
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(17);
        labelView.setTextColor(p.text);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(context);
        valueView.setText(value);
        valueView.setTextSize(13);
        valueView.setTextColor(p.sub);
        valueView.setMaxLines(1);
        valueView.setEllipsize(TextUtils.TruncateAt.START);
        valueView.setPadding(dp(context, 5), 0, 0, 0);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(labelView);
        row.addView(valueView);
        return row;
    }

    static View iconChip(Context context, int iconRes, String accentHex) {
        ImageView iconView = new ImageView(context);
        int accent = Color.parseColor(accentHex);
        Drawable icon = icon(context, iconRes, accent);
        if (icon != null) iconView.setImageDrawable(icon);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int iconPad = dp(context, 8);
        iconView.setPadding(iconPad, iconPad, iconPad, iconPad);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor((accent & 0x00FFFFFF) | 0x33000000);
        chipBg.setCornerRadius(dp(context, 4));
        iconView.setBackground(chipBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36));
        iconLp.rightMargin = dp(context, 14);
        iconView.setLayoutParams(iconLp);
        return iconView;
    }

    static View actionRow(Context context, int iconRes, String label, String accentHex, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(context, 8), dp(context, 11), dp(context, 10), dp(context, 11));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(ripple(context, 5));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(IePalette.of(context).text);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(iconChip(context, iconRes, accentHex));
        row.addView(labelView);
        row.setOnClickListener(onClick);
        return row;
    }

    static LinearLayout enableAllContainer(Context context, ToggleRow enableAllRow) {
        enableAllRow.makeBold();
        int accent = IePalette.of(context).accent;

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(context, 2), dp(context, 1), dp(context, 2), dp(context, 1));

        GradientDrawable background = new GradientDrawable();
        background.setColor(IePalette.withAlpha(accent, 0x24));
        background.setCornerRadius(dp(context, R_CARD));
        background.setStroke(hairline(context), IePalette.withAlpha(accent, 0x40));
        container.setBackground(background);

        container.addView(enableAllRow);
        return container;
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    static final class ToggleRow extends LinearLayout {
        private final Switch toggle;
        private final TextView labelView;
        private final TextView subtitleView;
        private CompoundButton.OnCheckedChangeListener primary;
        private final List<CompoundButton.OnCheckedChangeListener> observers = new ArrayList<>();
        private boolean locked;

        ToggleRow(Context context, String label, boolean checked) {
            this(context, 0, null, label, checked);
        }

        ToggleRow(Context context, int iconRes, String accentHex, String label, boolean checked) {
            super(context);
            IePalette p = IePalette.of(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(context, 3), dp(context, 1), dp(context, 3), dp(context, 1));
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 48));
            setClickable(true);
            setFocusable(true);
            setBackground(pressedBackground(context));

            if (iconRes != 0) {
                addView(iconChip(context, iconRes, accentHex));
            }

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            texts.setPadding(0, dp(context, 7), dp(context, 5), dp(context, 7));
            texts.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            texts.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(p.text);
            labelView.setTextSize(16);
            texts.addView(labelView);

            subtitleView = new TextView(context);
            subtitleView.setTextColor(p.sub);
            subtitleView.setTextSize(13);
            subtitleView.setPadding(0, dp(context, 2), 0, 0);
            subtitleView.setVisibility(GONE);
            texts.addView(subtitleView);

            toggle = new Switch(context);
            toggle.setChecked(checked);
            toggle.setThumbTintList(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.parseColor("#555555"), p.accent, Color.parseColor("#FFFFFF")}));
            toggle.setTrackTintList(new ColorStateList(
                    new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Color.parseColor("#777777"), IePalette.withAlpha(p.accent, 0x66), Color.parseColor("#CFD8DC")}));
            toggle.setClickable(false);
            toggle.setFocusable(false);
            toggle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            toggle.setOnCheckedChangeListener((b, c) -> {
                if (primary != null) primary.onCheckedChanged(b, c);
                for (CompoundButton.OnCheckedChangeListener o : observers) o.onCheckedChanged(b, c);
            });

            addView(texts);
            addView(toggle);
            setOnClickListener(v -> { if (isEnabled() && !locked) toggle.setChecked(!toggle.isChecked()); });
            setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Switch.class.getName());
                    info.setCheckable(true);
                    info.setChecked(toggle.isChecked());
                    info.setText(spokenText());
                }

                @Override
                public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
                    super.onInitializeAccessibilityEvent(host, event);
                    event.setClassName(Switch.class.getName());
                    event.setChecked(toggle.isChecked());
                }
            });
        }

        private CharSequence spokenText() {
            CharSequence sub = subtitleView.getVisibility() == VISIBLE ? subtitleView.getText() : null;
            return TextUtils.isEmpty(sub) ? labelView.getText() : TextUtils.concat(labelView.getText(), ", ", sub);
        }

        boolean isChecked() { return toggle.isChecked(); }
        void setChecked(boolean checked) { toggle.setChecked(checked); }

        void setSubtitle(CharSequence subtitle) {
            subtitleView.setText(subtitle);
            subtitleView.setVisibility(TextUtils.isEmpty(subtitle) ? GONE : VISIBLE);
        }

        void setLocked(CharSequence reason) {
            locked = reason != null;
            setSubtitle(reason);
            setClickable(!locked);
            if (locked) setEnabled(true);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            toggle.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.38f);
        }

        void setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener l) {
            primary = l;
        }

        void addOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener l) {
            observers.add(l);
        }

        void makeBold() {
            labelView.setTypeface(null, Typeface.BOLD);
            labelView.setTextSize(17);
        }
    }

    static final class RadioRow extends LinearLayout {
        private final TextView labelView;
        private final TextView checkmark;

        RadioRow(Context context, String label, boolean checked) {
            super(context);
            IePalette p = IePalette.of(context);
            setOrientation(HORIZONTAL);
            setPadding(dp(context, 3), dp(context, 1), dp(context, 3), dp(context, 1));
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 48));
            setClickable(true);
            setFocusable(true);
            setBackground(pressedBackground(context));

            labelView = new TextView(context);
            labelView.setText(label);
            labelView.setTextColor(p.text);
            labelView.setTextSize(16);
            labelView.setPadding(0, dp(context, 7), dp(context, 5), dp(context, 7));
            labelView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            labelView.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            checkmark = new TextView(context);
            checkmark.setText("✓");
            checkmark.setTextColor(IePalette.readable(p.accent, p.card, IePalette.TEXT));
            checkmark.setTextSize(18);
            checkmark.setTypeface(null, Typeface.BOLD);
            checkmark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            checkmark.setVisibility(checked ? VISIBLE : INVISIBLE);

            addView(labelView);
            addView(checkmark);
            setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(isChecked());
                    info.setText(labelView.getText());
                }

                @Override
                public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
                    super.onInitializeAccessibilityEvent(host, event);
                    event.setClassName(RadioButton.class.getName());
                    event.setChecked(isChecked());
                }
            });
        }

        boolean isChecked() {
            return checkmark.getVisibility() == VISIBLE;
        }

        void setChecked(boolean checked) {
            checkmark.setVisibility(checked ? VISIBLE : INVISIBLE);
        }
    }
}
