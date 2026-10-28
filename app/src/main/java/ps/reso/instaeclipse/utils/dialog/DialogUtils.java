package ps.reso.instaeclipse.utils.dialog;

import ps.reso.instaeclipse.utils.feature.ExtrasFeatures;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.Xposed.SettingsEvents;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.location.LocationPickerActivity;
import ps.reso.instaeclipse.mods.reels.DailyReelLimit;
import ps.reso.instaeclipse.mods.reels.NightReelSchedule;
import ps.reso.instaeclipse.mods.ui.StartupTabPolicy;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.ExtremeRules;
import ps.reso.instaeclipse.utils.core.ModuleActivityLauncher;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.core.PasscodeRules;
import ps.reso.instaeclipse.utils.core.SettingsGuard;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.feature.RestartPending;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.dialog.IeRows.RadioRow;
import ps.reso.instaeclipse.utils.dialog.IeRows.ToggleRow;

import static ps.reso.instaeclipse.utils.dialog.IeRows.R_CARD;
import static ps.reso.instaeclipse.utils.dialog.IeRows.dp;
import static ps.reso.instaeclipse.utils.dialog.IeRows.hairline;

public class DialogUtils {

    private static final SaveBatcher SAVES = new SaveBatcher(SettingsManager::saveAllFlags);
    public static final String MENU_REORGANISED_SEEN = "__menuReorganisedSeen";
    private static final String DESTRUCTIVE = "#FF453A";
    private static String cachedHostVersion;
    private static boolean unsentListStale;

    public static void showEclipseOptionsDialog(Context context) {
        SettingsManager.init(context);
        IePalette p = IePalette.of(context);
        java.util.List<Runnable> counts = new java.util.ArrayList<>();
        LinearLayout mainLayout = buildMainMenuLayout(context, counts);
        LinearLayout results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(mainLayout);
        content.addView(results);

        TextView closeButton = new TextView(context);
        closeButton.setText(I18n.t(context, R.string.ig_dialog_close));
        closeButton.setTextColor(IePalette.readable(p.sub, p.sheet, IePalette.TEXT));
        closeButton.setTextSize(16);
        closeButton.setMinHeight(dp(context, 48));
        closeButton.setPadding(dp(context, 13), dp(context, 7), dp(context, 13), dp(context, 7));
        closeButton.setGravity(Gravity.CENTER);
        closeButton.setTypeface(null, Typeface.BOLD);
        StateListDrawable closeStates = new StateListDrawable();
        closeStates.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(p.pressed));
        closeStates.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        closeButton.setBackground(closeStates);
        closeButton.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        closeButton.setOnClickListener(v -> IeSheet.close());

        Activity activity = UIHookManager.getCurrentActivity();
        if (activity != null) {
            GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
        }

        TextView health = healthChip(context);
        health.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        IeSheet.Page root = new IeSheet.Page(I18n.t(context, R.string.ig_dialog_title), content);
        SearchPanel search = new SearchPanel(context, root, mainLayout, results, counts);
        IeSheet.open(context, root
                .end(health)
                .pinned(search.box)
                .footer(closeButton)
                .onBack(search::clear)
                .onReturn(() -> {
                    for (Runnable count : counts) count.run();
                    search.refresh();
                }), null);
    }

    private static final class SearchPanel {
        final View box;
        private final Context context;
        private final IeSheet.Page page;
        private final View menu;
        private final LinearLayout results;
        private final java.util.List<Runnable> counts;
        private final java.util.List<Runnable> refresh = new java.util.ArrayList<>();
        private final android.widget.EditText field;
        private String shown = "";

        SearchPanel(Context context, IeSheet.Page page, View menu, LinearLayout results, java.util.List<Runnable> counts) {
            this.context = context;
            this.page = page;
            this.menu = menu;
            this.results = results;
            this.counts = counts;
            IePalette p = IePalette.of(context);
            field = new android.widget.EditText(context);
            field.setSingleLine(true);
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            field.setHint(I18n.t(context, R.string.menu_search_hint));
            field.setTextColor(p.text);
            field.setHintTextColor(p.sub);
            field.setTextSize(16);
            field.setMinHeight(dp(context, 48));
            field.setGravity(Gravity.CENTER_VERTICAL);
            Drawable glass = IeRows.icon(context, R.drawable.ic_search, p.sub);
            if (glass != null) {
                glass.setBounds(0, 0, dp(context, 20), dp(context, 20));
                field.setCompoundDrawablesRelative(glass, null, null, null);
            }
            field.setCompoundDrawablePadding(dp(context, 10));
            field.setPaddingRelative(dp(context, 14), dp(context, 6), dp(context, 48), dp(context, 6));
            GradientDrawable background = new GradientDrawable();
            background.setColor(p.field);
            background.setCornerRadius(dp(context, 24));
            background.setStroke(hairline(context), p.line);
            field.setBackground(background);

            ImageView clear = new ImageView(context);
            Drawable cross = IeRows.icon(context, R.drawable.ic_close, p.sub);
            if (cross != null) clear.setImageDrawable(cross);
            clear.setScaleType(ImageView.ScaleType.CENTER);
            clear.setContentDescription(I18n.t(context, R.string.menu_search_clear));
            clear.setBackground(IeRows.ripple(context, 24));
            clear.setVisibility(View.GONE);
            clear.setOnClickListener(v -> field.setText(""));

            field.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override public void afterTextChanged(android.text.Editable s) {
                    clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                    run(s.toString());
                }
            });
            field.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
                hideKeyboard();
                return true;
            });

            android.widget.FrameLayout holder = new android.widget.FrameLayout(context);
            holder.setFocusableInTouchMode(true);
            holder.setPadding(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 6));
            holder.addView(field, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            holder.addView(clear, new android.widget.FrameLayout.LayoutParams(dp(context, 48), dp(context, 48),
                    Gravity.END | Gravity.CENTER_VERTICAL));
            box = holder;
        }

        boolean clear() {
            if (field.getText().length() == 0) return false;
            field.setText("");
            hideKeyboard();
            return true;
        }

        void refresh() {
            runAll(refresh);
        }

        private void hideKeyboard() {
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
            field.clearFocus();
        }

        private void run(String raw) {
            String folded = ps.reso.instaeclipse.utils.text.SearchText.fold(raw.trim());
            if (folded.equals(shown)) return;
            shown = folded;
            results.removeAllViews();
            refresh.clear();
            if (folded.isEmpty()) {
                results.setVisibility(View.GONE);
                menu.setVisibility(View.VISIBLE);
                runAll(counts);
                return;
            }
            menu.setVisibility(View.GONE);
            results.setVisibility(View.VISIBLE);
            java.util.List<MenuSearch.Group> groups = MenuSearch.find(raw, id -> I18n.t(context, id));
            if (groups.isEmpty()) results.addView(noResults(context));
            for (MenuSearch.Group group : groups) {
                results.addView(IeRows.sectionHeader(context, I18n.t(context, group.category.title)));
                String accent = accentHex(group.category.group);
                java.util.List<View> rows = new java.util.ArrayList<>();
                for (FeatureCatalog.Item item : group.items) {
                    rows.add(searchResult(context, group.category, item, accent, refresh, this::hideKeyboard));
                }
                results.addView(cardOf(context, rows.toArray(new View[0])));
            }
            runAll(refresh);
            if (page.scroll != null) page.scroll.scrollTo(0, 0);
        }
    }

    private static View searchResult(Context context, FeatureCatalog.Category category, FeatureCatalog.Item item,
                                     String accent, java.util.List<Runnable> refresh, Runnable beforeOpen) {
        if (MenuSearch.action(item) == MenuSearch.Action.TOGGLE) {
            ToggleRow row = catalogSwitch(context, item, accent, refresh);
            if ("spoofLocation".equals(item.key)) pickPlaceFirst(context, row);
            refresh.add(() -> {
                setQuietly(row, ExtrasFeatures.get(item.key));
                row.setEnabled(MenuSearch.editable(item, ExtrasFeatures::get, FeatureFlags.isExtremeMode));
                if (PasscodeRules.isLock(item.key) && ExtrasFeatures.get(item.key) && !LockFlows.hasPasscode()) {
                    row.setSubtitle(I18n.t(context, R.string.ig_lock_on_needs_passcode));
                }
            });
            return row;
        }
        LinearLayout row = detailRow(context, item.icon != 0 ? item.icon : category.icon, accent,
                searchLabel(context, item), v -> {
                    beforeOpen.run();
                    openResult(context, category, item);
                });
        row.setTag(item.key);
        refresh.add(() -> {
            boolean enabled = MenuSearch.requiresMet(item, ExtrasFeatures::get);
            setRowEnabled(row, enabled);
            setDetailSubtitle(row, enabled ? subtitleOf(context, item) : requirement(context, item));
        });
        return row;
    }

    private static CharSequence searchLabel(Context context, FeatureCatalog.Item item) {
        switch (item.key) {
            case "reelUsage":
                return reelUsageText(context);
            case "nightReelStatus":
                return nightReelStatusText(context);
            case "autoClearCacheSizeMb":
                return autoClearSizeLabel(context);
            default:
                return I18n.t(context, item.label);
        }
    }

    private static void openResult(Context context, FeatureCatalog.Category category, FeatureCatalog.Item item) {
        IeSheet before = IeSheet.showing(context);
        int depth = before == null ? 0 : before.depth();
        showCategory(context, category.key);
        IeSheet sheet = IeSheet.showing(context);
        if (sheet != null && sheet.depth() > depth) sheet.reveal(item.key);
    }

    private static View noResults(Context context) {
        IePalette p = IePalette.of(context);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(context, 16), dp(context, 32), dp(context, 16), dp(context, 32));
        TextView title = new TextView(context);
        title.setText(I18n.t(context, R.string.menu_search_empty));
        title.setTextColor(p.text);
        title.setTextSize(16);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView hint = new TextView(context);
        hint.setText(I18n.t(context, R.string.menu_search_try_another));
        hint.setTextColor(p.sub);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(context, 4), 0, 0);
        box.addView(hint);
        return box;
    }

    public static void showSimpleDialog(Context context, String title, String message) {
        try {
            IeDialog dlg = new IeDialog(context).title(title).message(message);
            dlg.primary(I18n.t(context, R.string.ig_dialog_ok), null);
            dlg.show();
        } catch (Exception e) {
            // handle UI crash fallback
        }
    }

    public static void showCategory(Context context, String key) {
        switch (key) {
            case "lock" -> showLockOptions(context);
            case "focus" -> showDistractionOptions(context);
            case "theme" -> showThemeOptions(context);
            case "location" -> showLocationOptions(context);
            case "restart" -> showRestartSection(context);
            case "about" -> showAboutDialog(context);
            case "qt" -> showGhostQuickToggleOptions(context);
            default -> {
                FeatureCatalog.Category cat = FeatureCatalog.category(key);
                if (cat != null) showSectionDialog(context, I18n.t(context, cat.title), categoryLayout(context, cat));
            }
        }
    }

    private static LinearLayout buildMainMenuLayout(Context context, java.util.List<Runnable> counts) {
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        if (ps.reso.instaeclipse.utils.feature.SpoofLastSeenNotice.shouldShow(SettingsManager.prefs())) {
            mainLayout.addView(spoofLastSeenNotice(context));
        }
        android.content.SharedPreferences prefs = SettingsManager.prefs();
        if (prefs != null && !prefs.getBoolean(MENU_REORGANISED_SEEN, false)) {
            mainLayout.addView(menuReorganisedNotice(context));
        }
        FeatureCatalog.Values values = new FlagValues();
        for (FeatureCatalog.Group group : FeatureCatalog.Group.values()) {
            LinearLayout card = null;
            for (FeatureCatalog.Category cat : FeatureCatalog.CATEGORIES) {
                if (cat.hidden || cat.group != group) continue;
                if (card == null) {
                    mainLayout.addView(IeRows.sectionHeader(context, I18n.t(context, group.title)));
                    card = IeRows.card(context, 0);
                    mainLayout.addView(card);
                }
                java.util.function.Supplier<String> count = countable(cat)
                        ? () -> countText(context, FeatureCatalog.countOn(cat, values)) : null;
                View row = createMenuRow(context, cat.icon, I18n.t(context, cat.title), accentHex(group),
                        () -> showCategory(context, cat.key), count, counts);
                row.setTag(cat.key);
                card.addView(row);
            }
        }
        return mainLayout;
    }

    private static View spoofLastSeenNotice(Context context) {
        return notice(context, null, I18n.t(context, R.string.ie_spoof_last_seen_removed), () -> {
            FeatureFlags.spoofLastSeen = false;
            ps.reso.instaeclipse.utils.feature.SpoofLastSeenNotice.dismiss(SettingsManager.prefs());
            SettingsManager.saveAllFlags();
        });
    }

    private static View menuReorganisedNotice(Context context) {
        return notice(context, I18n.t(context, R.string.cat_menu_reorganised_title),
                I18n.t(context, R.string.cat_menu_reorganised_body), () -> {
                    android.content.SharedPreferences prefs = SettingsManager.prefs();
                    if (prefs != null) prefs.edit().putBoolean(MENU_REORGANISED_SEEN, true).apply();
                });
    }

    private static View notice(Context context, String title, String text, Runnable onOk) {
        IePalette p = IePalette.of(context);
        LinearLayout box = IeRows.card(context, 5);
        if (title != null) {
            TextView heading = new TextView(context);
            heading.setText(title);
            heading.setTextColor(p.text);
            heading.setTextSize(15);
            heading.setTypeface(null, Typeface.BOLD);
            heading.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), 0);
            box.addView(heading);
        }
        TextView message = new TextView(context);
        message.setText(text);
        message.setTextColor(p.text);
        message.setTextSize(14);
        message.setPadding(dp(context, 14), dp(context, title != null ? 4 : 12), dp(context, 14), dp(context, 4));
        box.addView(message);
        TextView ok = reelLimitAction(context, I18n.t(context, R.string.ig_dialog_ok), true);
        LinearLayout.LayoutParams okLp = (LinearLayout.LayoutParams) ok.getLayoutParams();
        okLp.gravity = Gravity.END;
        okLp.setMargins(0, dp(context, 4), dp(context, 8), dp(context, 8));
        box.addView(ok);
        ok.setOnClickListener(v -> {
            onOk.run();
            if (box.getParent() instanceof ViewGroup) ((ViewGroup) box.getParent()).removeView(box);
        });
        return box;
    }

    private static final class FlagValues implements FeatureCatalog.Values {
        @Override public boolean bool(String key, boolean def) {
            Object v = flag(key);
            return v instanceof Boolean ? (Boolean) v : def;
        }

        @Override public int integer(String key, int def) {
            Object v = flag(key);
            return v instanceof Integer ? (Integer) v : def;
        }

        @Override public String string(String key, String def) {
            Object v = flag(key);
            return v instanceof String ? (String) v : def;
        }

        private static Object flag(String key) {
            try {
                return FeatureFlags.class.getField(key).get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static boolean countable(FeatureCatalog.Category cat) {
        for (FeatureCatalog.Item item : cat.items()) {
            if (item.kind == FeatureCatalog.Kind.SWITCH && item.counts) return true;
            if (item.kind == FeatureCatalog.Kind.PICKER && item.countWhen != null) return true;
        }
        return false;
    }

    private static String countText(Context context, int on) {
        return on > 0 ? I18n.t(context, R.string.feat_count_on, on) : I18n.t(context, R.string.feat_count_off);
    }

    private static String accentHex(FeatureCatalog.Group group) {
        return String.format(java.util.Locale.ROOT, "#%08X", group.accent);
    }

    private static String labelOf(Context context, String key) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        return item != null ? I18n.t(context, item.label) : key;
    }

    private static String describe(Context context, FeatureCatalog.Item item) {
        String desc = item.desc != 0 ? I18n.t(context, item.desc) : null;
        if (item.restartOn == FeatureCatalog.Restart.NONE) return desc;
        return lines(desc, I18n.t(context, item.restartOn == FeatureCatalog.Restart.BOTH
                ? R.string.ig_restart_to_apply_both : R.string.ig_restart_to_apply_hint));
    }

    private static String subtitleOf(Context context, FeatureCatalog.Item item) {
        String text = describe(context, item);
        if (item.overriddenBy != null && ExtrasFeatures.get(item.overriddenBy)) {
            text = lines(text, I18n.t(context, R.string.cat_also_on_with, labelOf(context, item.overriddenBy)));
        }
        return text;
    }

    private static String requirement(Context context, FeatureCatalog.Item item) {
        return I18n.t(context, R.string.cat_requires, labelOf(context, item.requires[0]));
    }

    private static String lines(String first, String second) {
        if (first == null) return second;
        return second == null ? first : first + "\n" + second;
    }

    private static boolean anyOn(String[] keys) {
        if (keys.length == 0) return true;
        for (String key : keys) if (ExtrasFeatures.get(key)) return true;
        return false;
    }

    private static void runAll(java.util.List<Runnable> actions) {
        for (Runnable action : actions) action.run();
    }

    private static ToggleRow flagSwitch(Context context, int iconRes, String accentHex, int labelRes, String flag, Runnable after) {
        ToggleRow row = createSwitch(context, iconRes, accentHex, I18n.t(context, labelRes), ExtrasFeatures.get(flag));
        row.setOnCheckedChangeListener((b, checked) -> {
            if (suppressLockToggle) return;
            if (!SettingsGuard.request(context, flag, checked, () -> {
                setQuietly(row, checked);
                java.util.List<String> changed = applyWrites(flag, checked);
                SAVES.request();
                SettingsEvents.afterChange(changed);
                if (after != null) after.run();
            })) setQuietly(row, !checked);
        });
        return row;
    }

    private static java.util.List<String> applyWrites(String flag, boolean checked) {
        java.util.List<String> changed = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Boolean> write
                : MenuSearch.writes(flag, checked, FeatureFlags.isExtremeMode).entrySet()) {
            String key = write.getKey();
            boolean value = write.getValue();
            if (!key.equals(flag) && ExtrasFeatures.get(key) == value) continue;
            ExtrasFeatures.set(key, value);
            changed.add(key);
        }
        return changed;
    }

    private static ToggleRow catalogSwitch(Context context, FeatureCatalog.Item item, String accentHex,
                                           java.util.List<Runnable> refresh) {
        ToggleRow row = flagSwitch(context, item.icon, accentHex, item.label, item.key, () -> runAll(refresh));
        row.setTag(item.key);
        refresh.add(() -> setDependentToggle(context, row, item));
        return row;
    }

    private static ToggleRow catalogRow(Context context, String key, String accentHex, boolean checked) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        ToggleRow row = createSwitch(context, item.icon, accentHex, I18n.t(context, item.label), checked);
        row.setSubtitle(describe(context, item));
        row.setTag(key);
        return row;
    }

    private static void setDependentToggle(Context context, ToggleRow row, FeatureCatalog.Item item) {
        boolean enabled = anyOn(item.requires);
        row.setEnabled(enabled);
        row.setSubtitle(enabled ? subtitleOf(context, item) : requirement(context, item));
    }

    private static LinearLayout detailRow(Context context, int iconRes, String accentHex, CharSequence label,
                                          View.OnClickListener onClick) {
        IePalette p = IePalette.of(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(context, 8), dp(context, 11), dp(context, 10), dp(context, 11));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(IeRows.ripple(context, 5));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(p.text);
        texts.addView(labelView);
        TextView sub = new TextView(context);
        sub.setTextSize(13);
        sub.setTextColor(p.sub);
        sub.setPadding(0, dp(context, 2), 0, 0);
        sub.setVisibility(View.GONE);
        texts.addView(sub);

        row.addView(IeRows.iconChip(context, iconRes, accentHex));
        row.addView(texts);
        row.setOnClickListener(onClick);
        return row;
    }

    private static TextView detailLabel(View row) {
        return (TextView) ((ViewGroup) ((ViewGroup) row).getChildAt(1)).getChildAt(0);
    }

    private static void setDetailSubtitle(View row, CharSequence text) {
        TextView sub = (TextView) ((ViewGroup) ((ViewGroup) row).getChildAt(1)).getChildAt(1);
        sub.setText(text);
        sub.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static View catalogAction(Context context, FeatureCatalog.Item item, String accentHex,
                                      java.util.List<Runnable> refresh, View.OnClickListener onClick) {
        return catalogDetail(context, item, accentHex, I18n.t(context, item.label), refresh, onClick);
    }

    private static View catalogDetail(Context context, FeatureCatalog.Item item, String accentHex, CharSequence text,
                                      java.util.List<Runnable> refresh, View.OnClickListener onClick) {
        LinearLayout row = detailRow(context, item.icon, accentHex, text, onClick);
        row.setTag(item.key);
        refresh.add(() -> {
            boolean enabled = anyOn(item.requires);
            setRowEnabled(row, enabled);
            setDetailSubtitle(row, enabled ? subtitleOf(context, item) : requirement(context, item));
        });
        return row;
    }

    private static View infoRow(Context context, FeatureCatalog.Item item, CharSequence text,
                                java.util.List<Runnable> refresh) {
        TextView info = IeRows.helpText(context, text);
        info.setTag(item.key);
        if (item.requires.length > 0) refresh.add(() -> info.setVisibility(anyOn(item.requires) ? View.VISIBLE : View.GONE));
        return info;
    }

    private static LinearLayout categoryLayout(Context context, FeatureCatalog.Category cat) {
        LinearLayout layout = createSwitchLayout(context);
        java.util.List<Runnable> refresh = new java.util.ArrayList<>();
        String accent = accentHex(cat.group);
        boolean first = true;
        for (FeatureCatalog.Section section : cat.sections) {
            if (section.header != 0) layout.addView(IeRows.sectionHeader(context, I18n.t(context, section.header)));
            else if (!first) layout.addView(IeRows.divider(context));
            first = false;
            FeatureCatalog.Item master = null;
            java.util.List<View> rows = new java.util.ArrayList<>();
            for (FeatureCatalog.Item item : section.items) {
                if (item.kind == FeatureCatalog.Kind.MASTER) {
                    master = item;
                    continue;
                }
                View row = itemRow(context, item, accent, refresh);
                if (row != null) rows.add(row);
            }
            if (master != null) addMasterGroup(context, layout, master, childSwitches(master, rows));
            if (!rows.isEmpty()) layout.addView(cardOf(context, rows.toArray(new View[0])));
        }
        runAll(refresh);
        return layout;
    }

    private static ToggleRow[] childSwitches(FeatureCatalog.Item master, java.util.List<View> rows) {
        java.util.List<String> children = java.util.Arrays.asList(master.children);
        java.util.List<ToggleRow> out = new java.util.ArrayList<>();
        for (View row : rows) {
            if (row instanceof ToggleRow && children.contains(row.getTag())) out.add((ToggleRow) row);
        }
        return out.toArray(new ToggleRow[0]);
    }

    private static View itemRow(Context context, FeatureCatalog.Item item, String accent, java.util.List<Runnable> refresh) {
        switch (item.key) {
            case "quickToggle":
                return catalogAction(context, item, accent, refresh, v -> showGhostQuickToggleOptions(context));
            case "customShareDomain":
                return catalogDetail(context, item, accent, shareDomainLabel(context), refresh,
                        v -> showShareDomainDialog(context, detailLabel(v)));
            case "exactTimestampFormat":
                return catalogDetail(context, item, accent, timestampFormatLabel(context), refresh,
                        v -> showTimestampFormatDialog(context, detailLabel(v)));
            case "feedCornerRadius":
                return catalogDetail(context, item, accent, feedCornerLabel(context), refresh,
                        v -> showFeedCornerDialog(context, detailLabel(v)));
            case "storyTrayScale":
                return catalogDetail(context, item, accent, storyTraySizeLabel(context), refresh,
                        v -> showStoryTraySizeDialog(context, detailLabel(v)));
            case "storyRingColor":
                return catalogDetail(context, item, accent, storyRingColorLabel(context), refresh,
                        v -> showStoryRingColorDialog(context, detailLabel(v)));
            case "navTabOrder":
                return catalogAction(context, item, accent, refresh, v -> showTabOrderDialog(context));
            case "startupTab":
                return catalogDetail(context, item, accent, startupTabLabel(context), refresh,
                        v -> showStartupTabDialog(context, detailLabel(v)));
            case "forceReelQuality":
                return catalogDetail(context, item, accent, reelQualityLabel(context), refresh,
                        v -> showReelQualityDialog(context, detailLabel(v)));
            case "spoofDevice":
                return catalogDetail(context, item, accent, spoofDeviceLabel(context), refresh,
                        v -> showSpoofDeviceDialog(context, detailLabel(v)));
            case "qualityPointer": {
                FeatureCatalog.Category downloads = FeatureCatalog.category("downloads");
                LinearLayout row = detailRow(context, downloads.icon, accentHex(downloads.group),
                        I18n.t(context, item.label), v -> showCategory(context, downloads.key));
                row.setTag(item.key);
                return row;
            }
            case "downloaderUseOriginal":
                return catalogDetail(context, item, accent, downloadQualityRowLabel(context, item), refresh,
                        v -> showDownloadQuality(context, item, detailLabel(v), () -> runAll(refresh)));
            case "downloaderCompatibility":
            case "downloaderAllowFallback": {
                ToggleRow row = catalogSwitch(context, item, accent, refresh);
                refresh.add(() -> row.setVisibility(FeatureFlags.downloaderUseOriginal ? View.GONE : View.VISIBLE));
                return row;
            }
            case "downloadFolder":
                return downloadFolderBlock(context, item);
            case "storyCacheHours":
                return storyCacheDurationRow(context, item, refresh);
            case "cachedStories":
                return catalogAction(context, item, accent, refresh, v -> showCachedStories(context));
            case "unsentLog":
                return catalogAction(context, item, accent, refresh, v -> showUnsentMessages(context));
            case "watchlist":
                return catalogAction(context, item, accent, refresh,
                        v -> ps.reso.instaeclipse.mods.misc.ProfileAnalyzer.showWatchlist(context));
            case "profileSpoofEdit":
                return catalogAction(context, item, accent, refresh, v -> showProfileSpoofDialog(context));
            case "profilePhoto":
                return infoRow(context, item, I18n.t(context, R.string.ig_profile_spoofer_photo_in_app), refresh);
            case "autoClearCacheSizeMb":
                return catalogDetail(context, item, accent, autoClearSizeLabel(context), refresh,
                        v -> showAutoClearSizeDialog(context, detailLabel(v)));
            case "rescan":
                return catalogAction(context, item, accent, refresh, v -> showRescan(context));
            case "settingsBackup":
                return catalogAction(context, item, accent, refresh, v -> backupSettings(context));
            case "settingsRestore":
                return catalogAction(context, item, accent, refresh, v -> restoreSettings(context));
            case "dataBackup":
                return catalogAction(context, item, accent, refresh, v -> backupData(context));
            case "dataRestore":
                return catalogAction(context, item, accent, refresh, v -> restoreData(context));
            case "devImport":
                return catalogAction(context, item, accent, refresh, v -> importDevConfig(context));
            case "devExport":
                return catalogAction(context, item, accent, refresh, v -> exportDevConfig(context));
            case "devReset":
                return catalogAction(context, item, accent, refresh, v -> restoreDefaultDevConfig(context));
            default:
                break;
        }
        if (item.side == FeatureCatalog.Side.COMPANION_ONLY) return null;
        if (item.kind == FeatureCatalog.Kind.SWITCH) return catalogSwitch(context, item, accent, refresh);
        if (item.kind == FeatureCatalog.Kind.INFO) return infoRow(context, item, I18n.t(context, item.label), refresh);
        return null;
    }

    private static void addMasterGroup(Context context, LinearLayout layout, FeatureCatalog.Item item, ToggleRow... rows) {
        ToggleRow master = createSwitch(context, I18n.t(context, item.label), areAllEnabled(rows));
        CompoundButton.OnCheckedChangeListener flipAll = (b, checked) -> SAVES.batch(() -> {
            for (ToggleRow r : rows) r.setChecked(checked);
        });
        master.setOnCheckedChangeListener(flipAll);
        for (ToggleRow r : rows) {
            r.addOnCheckedChangeListener((b, checked) -> {
                master.setOnCheckedChangeListener(null);
                master.setChecked(areAllEnabled(rows));
                master.setOnCheckedChangeListener(flipAll);
            });
        }
        LinearLayout container = IeRows.enableAllContainer(context, master);
        container.setTag(item.key);
        layout.addView(container);
        layout.addView(IeRows.divider(context));
    }

    private static LinearLayout cardOf(Context context, View... rows) {
        LinearLayout group = IeRows.card(context, 5);
        for (View r : rows) group.addView(r);
        return group;
    }

    private static String moduleVersion() {
        return "v" + ps.reso.instaeclipse.BuildConfig.VERSION_NAME;
    }

    private static String hostVersion(Context context) {
        if (cachedHostVersion != null) return cachedHostVersion;
        try {
            cachedHostVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
        return cachedHostVersion;
    }

    private static View buildStatusChips(Context context) {
        IePalette p = IePalette.of(context);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 14));

        LinearLayout versions = new LinearLayout(context);
        versions.setOrientation(LinearLayout.HORIZONTAL);
        versions.setGravity(Gravity.CENTER);
        versions.addView(statusChip(context, "InstaEclipse " + moduleVersion(), p.sub, null));
        versions.addView(statusChip(context, I18n.t(context, R.string.menu_chip_instagram, hostVersion(context)), p.sub, null));
        box.addView(versions);

        TextView health = healthChip(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 6);
        health.setLayoutParams(lp);
        box.addView(health);
        return box;
    }

    private static TextView healthChip(Context context) {
        IePalette p = IePalette.of(context);
        TextView health = statusChip(context, "", p.sub, v -> {
            Health now = health(context);
            StringBuilder msg = new StringBuilder();
            section(msg, I18n.t(context, R.string.menu_health_broken), now.broken);
            section(msg, I18n.t(context, R.string.menu_health_restart), now.restart);
            section(msg, I18n.t(context, R.string.menu_health_locks), now.locks);
            section(msg, I18n.t(context, R.string.menu_health_unconfirmed), now.unconfirmed);
            if (msg.length() == 0) msg.append(I18n.t(context, R.string.menu_inactive_all_good));
            try {
                IeDialog dlg = new IeDialog(context)
                        .title(I18n.t(context, R.string.menu_chip_active, now.active, now.total))
                        .message(msg.toString());
                if (!now.broken.isEmpty()) dlg.secondary(I18n.t(context, R.string.cat_rescan), () -> showRescan(context));
                dlg.primary(I18n.t(context, R.string.ig_dialog_ok), null);
                dlg.show();
            } catch (Exception e) {
                ModuleLog.warn("(IE|Menu) Could not open the feature status", e);
            }
        });
        renderHealth(context, health);
        boolean[] queued = {false};
        Runnable onChange = () -> {
            synchronized (queued) {
                if (queued[0]) return;
                queued[0] = true;
            }
            health.post(() -> {
                synchronized (queued) {
                    queued[0] = false;
                }
                renderHealth(context, health);
            });
        };
        health.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                FeatureStatusTracker.addListener(onChange);
                renderHealth(context, health);
            }

            @Override public void onViewDetachedFromWindow(View v) {
                FeatureStatusTracker.removeListener(onChange);
            }
        });
        return health;
    }

    private static void section(StringBuilder msg, String title, java.util.List<String> labels) {
        if (labels.isEmpty()) return;
        if (msg.length() > 0) msg.append("\n\n");
        msg.append(title);
        java.util.Collections.sort(labels);
        for (String label : labels) msg.append("\n•  ").append(label);
    }

    private static void renderHealth(Context context, TextView health) {
        Health now = health(context);
        IePalette p = IePalette.of(context);
        health.setVisibility(now.total > 0 ? View.VISIBLE : View.GONE);
        health.setText("●  " + I18n.t(context, R.string.menu_chip_active, now.active, now.total));
        int color = !now.broken.isEmpty() || !now.locks.isEmpty() ? 0xFFFF9F0A
                : now.active == now.total ? p.success : p.sub;
        health.setTextColor(IePalette.readable(color, p.card, IePalette.TEXT));
    }

    private static final class Health {
        int active;
        int total;
        final java.util.List<String> broken = new java.util.ArrayList<>();
        final java.util.List<String> restart = new java.util.ArrayList<>();
        final java.util.List<String> locks = new java.util.ArrayList<>();
        final java.util.List<String> unconfirmed = new java.util.ArrayList<>();
    }

    private static Health health(Context context) {
        Health out = new Health();
        java.util.Map<String, Boolean> status = FeatureStatusTracker.getStatus();
        java.util.Set<String> pending = restartPending();
        boolean noCode = !LockFlows.hasPasscode();
        out.total = status.size();
        for (java.util.Map.Entry<String, Boolean> e : status.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                out.active++;
                continue;
            }
            String label = FeatureStatusTracker.getLabel(context, e.getKey());
            java.util.List<String> items = itemsWithStatus(e.getKey());
            if (FeatureStatusTracker.isBroken(e.getKey())) out.broken.add(label);
            else if (!java.util.Collections.disjoint(items, pending)) out.restart.add(label);
            else if (noCode && anyLock(items)) out.locks.add(label);
            else out.unconfirmed.add(label);
        }
        return out;
    }

    private static java.util.List<String> itemsWithStatus(String statusKey) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (FeatureCatalog.Category c : FeatureCatalog.CATEGORIES) {
            for (FeatureCatalog.Item item : c.items()) {
                for (String key : item.statusKeys) if (key.equals(statusKey)) out.add(item.key);
            }
        }
        return out;
    }

    private static boolean anyLock(java.util.List<String> keys) {
        for (String key : keys) if (PasscodeRules.isLock(key)) return true;
        return false;
    }

    private static java.util.Map<String, Boolean> restartRules;

    static java.util.Set<String> restartPending() {
        java.util.Map<String, Boolean> rules = restartRules;
        if (rules == null) {
            rules = new java.util.LinkedHashMap<>();
            for (FeatureCatalog.Category c : FeatureCatalog.CATEGORIES) {
                for (FeatureCatalog.Item item : c.items()) {
                    if (item.restartOn != FeatureCatalog.Restart.NONE) {
                        rules.put(item.key, item.restartOn == FeatureCatalog.Restart.ON_ONLY);
                    }
                }
            }
            rules.put("customFontPath", false);
            rules.put("customEmojiPath", false);
            restartRules = rules;
        }
        return RestartPending.pending(rules);
    }

    private static TextView statusChip(Context context, String text, int textColor, View.OnClickListener onClick) {
        IePalette p = IePalette.of(context);
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextColor(textColor);
        chip.setTextSize(12);
        chip.setSingleLine(true);
        chip.setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.card);
        bg.setCornerRadius(dp(context, 14));
        bg.setStroke(hairline(context), p.line);
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 3);
        lp.rightMargin = dp(context, 3);
        chip.setLayoutParams(lp);
        if (onClick != null) {
            chip.setClickable(true);
            chip.setOnClickListener(onClick);
        }
        return chip;
    }

    private static View createMenuRow(Context context, int iconRes, String label, String accentHex,
                                      Runnable onClick, java.util.function.Supplier<String> count,
                                      java.util.List<Runnable> counts) {
        IePalette p = IePalette.of(context);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 9), dp(context, 10), dp(context, 9));
        row.setMinimumHeight(dp(context, 48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(IeRows.ripple(context, 5));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(16);
        labelView.setTextColor(p.text);
        View labelBlock = labelView;
        if (count != null) {
            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(labelView);
            TextView sub = new TextView(context);
            sub.setTextSize(12);
            Runnable render = () -> {
                String text = count.get();
                sub.setText(text);
                boolean off = text.equals(I18n.t(context, R.string.feat_count_off));
                sub.setTextColor(off ? p.sub : IePalette.readable(Color.parseColor(accentHex), p.card, IePalette.TEXT));
            };
            render.run();
            if (counts != null) counts.add(render);
            texts.addView(sub);
            labelBlock = texts;
        }
        labelBlock.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.ImageView chevron = new android.widget.ImageView(context);
        Drawable chev = IeRows.icon(context, R.drawable.ic_chevron_right, p.sub);
        if (chev != null) chevron.setImageDrawable(chev);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20));
        chevLp.rightMargin = dp(context, 4);
        chevron.setLayoutParams(chevLp);

        row.addView(IeRows.iconChip(context, iconRes, accentHex));
        row.addView(labelBlock);
        row.addView(chevron);
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private static GradientDrawable roundedColor(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp);
        return d;
    }


    private static void showGhostQuickToggleOptions(Context context) {
        FeatureCatalog.Category cat = FeatureCatalog.category("qt");
        LinearLayout layout = categoryLayout(context, cat);
        layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_quick_toggle_hint)));
        showSectionDialog(context, I18n.t(context, cat.title), layout);
    }

    static Runnable exit = () -> Runtime.getRuntime().exit(0);

    public static void restart(Context context, boolean clearCache) {
        try {
            String packageName = context.getPackageName();
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (clearCache) {
                    android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                    ps.reso.instaeclipse.utils.core.CacheAutoClear.clearNow(context,
                            () -> main.post(() -> relaunch(context, launchIntent)));
                } else {
                    relaunch(context, launchIntent);
                }
            } else {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_restart_not_found), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            restartFailed(context, e);
        }
    }

    private static void relaunch(Context context, Intent launchIntent) {
        try {
            waitForQueuedPreferenceWrites();
            context.startActivity(launchIntent);
            exit.run();
        } catch (Exception e) {
            restartFailed(context, e);
        }
    }

    private static void waitForQueuedPreferenceWrites() {
        android.content.SharedPreferences prefs = SettingsManager.prefs();
        if (prefs == null) return;
        prefs.edit().apply();
        prefs.edit().commit();
    }

    private static void restartFailed(Context context, Exception e) {
        String packageName = context.getPackageName();
        ModuleLog.error("(InstaEclipse): Could not restart " + packageName + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_restart_failed, e.getMessage()), Toast.LENGTH_LONG).show();
    }


    // ==== SECTIONS ====

    private static void importDevConfig(Context context) {
        Bundle extras = new Bundle();
        extras.putString("target_package", context.getPackageName());
        openModuleScreen(context, ps.reso.instaeclipse.utils.ipc.BrokerPayload.IMPORT, extras);
    }

    private static void exportDevConfig(Context context) {
        Activity instagramActivity = UIHookManager.getCurrentActivity();
        if (instagramActivity != null && !instagramActivity.isFinishing()) {
            try {
                File source = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
                if (!source.exists()) {
                    showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_mc_overrides_not_found));
                    return;
                }
                String json;
                try (java.io.InputStream input = new java.io.FileInputStream(source)) {
                    json = ps.reso.instaeclipse.utils.ipc.BrokerPayload.readConfig(input);
                }
                Bundle extras = new Bundle();
                extras.putString("json_content", json);
                openModuleScreen(context, ps.reso.instaeclipse.utils.ipc.BrokerPayload.EXPORT, extras);
            } catch (Exception e) {
                showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_failed_read_config, e.getMessage()));
            }
        } else {
            showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
        }
    }

    private static void restoreDefaultDevConfig(Context context) {
        IeDialog dlg = new IeDialog(context)
                .title(I18n.t(context, R.string.ig_dialog_dev_restore_default_config))
                .message(I18n.t(context, R.string.ig_dialog_dev_restore_default_config_confirm));
        dlg.secondary(I18n.t(context, R.string.ig_dialog_cancel), null);
        dlg.primary(I18n.t(context, R.string.ig_dialog_yes), () ->
                ConfigManager.restoreDefaultConfig(context, Module.moduleSourceDir));
        dlg.show();
    }

    private static void openModuleScreen(Context context, String screen, Bundle extras) {
        Activity activity = UIHookManager.getCurrentActivity();
        if (activity == null || activity.isFinishing()) {
            showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
        } else if (!ModuleActivityLauncher.launch(context, screen, extras)) {
            ModuleLog.warn("(IE|Menu) Could not open " + screen.substring(screen.lastIndexOf('.') + 1));
            showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_unable_open_ui));
        }
    }

    static String[] spoofDeviceNames(Context context) {
        String[] ids = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES;
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            String n = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.deviceName(ids[i]);
            names[i] = n != null ? n : I18n.t(context, R.string.ig_dialog_extras_share_domain_off);
        }
        return names;
    }

    private static String spoofDeviceLabel(Context context) {
        int i = java.util.Arrays.asList(ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES).indexOf(FeatureFlags.spoofDevice);
        return I18n.t(context, R.string.cat_spoofDevice) + "  ·  " + spoofDeviceNames(context)[Math.max(i, 0)];
    }

    /** Which device Instagram sees (FeatureFlags.spoofDevice); applies on the next start. */
    private static void showSpoofDeviceDialog(Context context, TextView label) {
        String[] ids = ps.reso.instaeclipse.mods.misc.DeviceSpoofHook.DEVICES;
        int current = Math.max(java.util.Arrays.asList(ids).indexOf(FeatureFlags.spoofDevice), 0);
        new IeDialog(context)
                .title(I18n.t(context, R.string.cat_spoofDevice))
                .message(I18n.t(context, R.string.ig_spoof_device_note))
                .choices(spoofDeviceNames(context), current, which -> {
                    FeatureFlags.spoofDevice = ids[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("spoofDevice");
                    if (label != null) label.setText(spoofDeviceLabel(context));
                })
                .show();
    }

    /** Styled per-thread Unsent Messages viewer, opened from the in-thread bin button. */
    public static void showThreadUnsent(Context context, String threadId, String chatName) {
        showThreadUnsent(context, threadId, chatName, null);
    }

    /** {@code onBack}: where Back goes (e.g. the chat's own menu) instead of the main menu. */
    public static void showThreadUnsent(Context context, String threadId, String chatName, Runnable onBack) {
        IeSheet.Page page = threadUnsentPage(context, threadId, chatName, onBack);
        if (onBack != null) IeSheet.open(context, page, onBack);
        else showSection(context, page);
    }

    private static IeSheet.Page threadUnsentPage(Context context, String threadId, String chatName, Runnable onBack) {
        LinearLayout layout = createSwitchLayout(context);
        IePalette p = IePalette.of(context);

        // Never widen to every chat: an unknown thread (null) shows only the entries that were
        // logged without a chat, so one chat's unsent messages can't appear inside another.
        final java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries =
                ps.reso.instaeclipse.utils.ghost.UnsentLog.getForThread(context, threadId != null ? threadId : "");

        final String chat = chatName != null && !chatName.trim().isEmpty() ? chatName.trim() : null;
        addSetAsideNotice(context, layout);

        if (!entries.isEmpty()) {
            layout.addView(IeRows.actionRow(context, R.drawable.ic_upload, I18n.t(context, R.string.ig_dialog_unsent_export), "#0A84FF",
                    v -> exportUnsent(context, chat, threadId, entries)));
            layout.addView(IeRows.actionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_unsent_clear_saved), "#FF453A", v -> {
                try {
                    IeDialog dlg = new IeDialog(context)
                            .title(I18n.t(context, R.string.ig_unsent_clear_saved))
                            .message(I18n.t(context, R.string.ig_unsent_clear_saved_confirm));
                    dlg.secondary(context.getString(android.R.string.cancel), null);
                    dlg.primary(I18n.t(context, R.string.ig_dialog_unsent_clear_yes), () -> {
                        if (!ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook.clearSaved(context, threadId != null ? threadId : "")) {
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_unsent_clear_failed), Toast.LENGTH_LONG).show();
                            return;
                        }
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_unsent_cleared_saved), Toast.LENGTH_SHORT).show();
                        reopenThreadUnsent(context, threadId, chatName, onBack);
                    });
                    dlg.show();
                } catch (Throwable ignored) {}
            }));
            layout.addView(IeRows.divider(context));
        }

        if (entries.isEmpty()) {
            layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_dialog_unsent_empty)));
        } else {
            // One bordered card per deleted message: "Unsent" tag (accent) and time on top, text below.
            for (int i = 0; i < entries.size(); i++) {
                ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e = entries.get(i);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(context, 14);
                row.setPadding(pad, dp(context, 11), pad, dp(context, 12));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(p.card);
                bg.setCornerRadius(dp(context, R_CARD));
                bg.setStroke(Math.max(1, dp(context, 1)), p.line);
                row.setBackground(bg);

                LinearLayout meta = new LinearLayout(context);
                meta.setGravity(Gravity.CENTER_VERTICAL);
                String tag = FeatureFlags.showUnsentIndicator
                        ? I18n.t(context, R.string.ig_unsent_indicator_label) : "";
                if (e.showsSender(chat)) tag = tag.isEmpty() ? e.senderLabel() : e.senderLabel() + " · " + tag;
                TextView sender = new TextView(context);
                sender.setText(tag);
                if (tag.isEmpty()) sender.setVisibility(View.GONE);
                sender.setTextColor(IePalette.readable(p.accent, p.card, IePalette.TEXT));
                sender.setTextSize(13);
                sender.setTypeface(Typeface.DEFAULT_BOLD);
                sender.setSingleLine(true);
                sender.setEllipsize(android.text.TextUtils.TruncateAt.END);
                meta.addView(sender, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                TextView when = new TextView(context);
                when.setText(shortDate(context, e.time));
                when.setTextColor(p.sub);
                when.setTextSize(12);
                when.setPadding(tag.isEmpty() ? 0 : dp(context, 8), 0, 0, 0);
                meta.addView(when);

                TextView body = new TextView(context);
                // A message without text shows what it was ("Reel by @user", "Voice message · 0:29"),
                // in italics so it never reads as words the sender wrote.
                body.setText(ps.reso.instaeclipse.utils.ghost.UnsentDetailText.body(context, e));
                if (ps.reso.instaeclipse.utils.ghost.UnsentDetailText.isDescription(e)) body.setTypeface(null, Typeface.ITALIC);
                body.setTextColor(p.text);
                body.setTextSize(15);
                body.setPadding(0, dp(context, 5), 0, 0);
                body.setTextIsSelectable(true);
                row.addView(meta);
                row.addView(body);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, i == 0 ? dp(context, 4) : dp(context, 8), 0, 0);
                layout.addView(row, lp);
            }
        }

        return new IeSheet.Page(chat != null ? chat : I18n.t(context, R.string.ig_dialog_unsent_unknown_chat), layout);
    }

    private static String shortDate(Context context, long time) {
        return android.text.format.DateUtils.formatDateTime(context, time,
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                        | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
    }

    private static TextView trailingLabel(Context context, String text, int color) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(IePalette.readable(color, IePalette.of(context).card, IePalette.TEXT));
        label.setSingleLine(true);
        label.setPaddingRelative(dp(context, 8), 0, dp(context, 4), 0);
        return label;
    }

    private static void exportUnsent(Context context, String chat, String threadId,
                                     java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries) {
        MenuWorker.execute(() -> {
            String message;
            try {
                message = I18n.t(context, R.string.ig_dialog_unsent_exported, exportUnsentJson(context, chat, threadId, entries));
            } catch (Throwable t) {
                message = I18n.t(context, R.string.ig_unsent_export_failed,
                        t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
            String text = message;
            MenuWorker.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, text, Toast.LENGTH_LONG).show());
        });
    }

    private static void reopenThreadUnsent(Context context, String threadId, String chatName, Runnable onBack) {
        unsentListStale = true;
        IeSheet sheet = IeSheet.showing(context);
        if (sheet != null) sheet.replaceTop(threadUnsentPage(context, threadId, chatName, onBack));
    }

    private static String exportUnsentJson(Context context, String chatName, String threadId,
                                           java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries) throws Exception {
        try {
            String fallback = threadId != null ? threadId : "unknown";
            String safe = ps.reso.instaeclipse.mods.media.DownloadFiles.safeSegment(chatName, fallback);
            String fileName = "unsent_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date()) + ".json";

            org.json.JSONObject root = new org.json.JSONObject();
            root.put("chat", chatName != null ? chatName : fallback);
            root.put("exported_at", System.currentTimeMillis());
            org.json.JSONArray arr = new org.json.JSONArray();
            java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            for (ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e : entries) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("time", e.time);
                o.put("time_iso", iso.format(new java.util.Date(e.time)));
                if (!e.senderLabel().isEmpty()) o.put("sender", e.senderLabel());
                o.put("text", ps.reso.instaeclipse.utils.ghost.UnsentDetailText.body(context, e));
                // Marks "text" as a description of a message without text, not its words.
                if (ps.reso.instaeclipse.utils.ghost.UnsentDetailText.isDescription(e)) {
                    o.put("kind", e.detail != null ? e.detail.kind.id : "unknown");
                }
                arr.put(o);
            }
            root.put("messages", arr);
            byte[] data = root.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String relDir = "Download/InstaEclipse/" + safe;

            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relDir);
                android.net.Uri uri = context.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (java.io.OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                        if (os != null) { os.write(data); os.flush(); }
                    }
                    return relDir + "/" + fileName;
                }
            }
            // Fallback: direct file into public Downloads.
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "InstaEclipse/" + safe);
            if (dir.exists() || dir.mkdirs()) {
                java.io.File out = new java.io.File(dir, fileName);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) { fos.write(data); }
                return out.getAbsolutePath();
            }
            throw new java.io.IOException(dir.getPath());
        } catch (Throwable t) {
            ModuleLog.error("(IE|UnsentExport) Could not export the unsent messages: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw t;
        }
    }

    private static void showUnsentMessages(Context context) {
        showSection(context, unsentListPage(context));
    }

    private static void addSetAsideNotice(Context context, LinearLayout layout) {
        if (ps.reso.instaeclipse.utils.ghost.UnsentLog.takeSetAsideNotice()) {
            layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ie_unsent_log_set_aside)));
        }
    }

    private static IeSheet.Page unsentListPage(Context context) {
        unsentListStale = false;
        LinearLayout layout = createSwitchLayout(context);

        java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry> entries =
                ps.reso.instaeclipse.utils.ghost.UnsentLog.getAll(context);
        addSetAsideNotice(context, layout);

        if (entries.isEmpty()) {
            layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_dialog_unsent_empty)));
            return new IeSheet.Page(I18n.t(context, R.string.ig_dialog_unsent_title), layout);
        }

        // Folder drill-down (like the story cache): one folder per chat/thread. Entries come
        // newest-first, so the first non-empty sender seen for a thread is its most recent label.
        java.util.LinkedHashMap<String, java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry>> byThread =
                new java.util.LinkedHashMap<>();
        java.util.HashMap<String, String> nameFor = new java.util.HashMap<>();
        for (ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry e : entries) {
            String key = (e.thread == null || e.thread.isEmpty()) ? "" : e.thread;
            byThread.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(e);
            if (!nameFor.containsKey(key) && e.sender != null && !e.sender.trim().isEmpty()) {
                nameFor.put(key, e.sender.trim());
            }
        }

        LinearLayout foldersCard = IeRows.card(context, 5);
        IePalette p = IePalette.of(context);
        java.util.Map<String, TextView> unresolved = new java.util.LinkedHashMap<>();
        java.util.Map<String, String[]> names = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.List<ps.reso.instaeclipse.utils.ghost.UnsentLog.Entry>> t : byThread.entrySet()) {
            final String threadId = t.getKey();
            String title = ps.reso.instaeclipse.mods.ghost.HideChatsHook.knownThreadTitle(threadId);
            boolean inboxTitle = title != null && !title.isEmpty();
            final String[] name = {inboxTitle ? title : nameFor.get(threadId)};
            LinearLayout row = (LinearLayout) IeRows.actionRow(context, R.drawable.ic_folder,
                    name[0] != null ? name[0] : I18n.t(context, R.string.ig_dialog_unsent_unknown_chat), "#5E5CE6",
                    v -> showThreadUnsent(context, threadId.isEmpty() ? null : threadId, name[0]));
            row.addView(trailingLabel(context, java.text.NumberFormat.getIntegerInstance().format(t.getValue().size()), p.sub));
            foldersCard.addView(row);
            if (!inboxTitle && !threadId.isEmpty()) {
                unresolved.put(threadId, (TextView) row.getChildAt(1));
                names.put(threadId, name);
            }
        }
        layout.addView(foldersCard);
        if (!unresolved.isEmpty()) {
            java.util.List<String> ids = new java.util.ArrayList<>(unresolved.keySet());
            MenuWorker.load(() -> ps.reso.instaeclipse.utils.ghost.ThreadNames.resolveAll(ids), resolved -> {
                if (resolved == null) return;
                for (java.util.Map.Entry<String, String> r : resolved.entrySet()) {
                    TextView label = unresolved.get(r.getKey());
                    String[] name = names.get(r.getKey());
                    if (label == null || name == null) continue;
                    name[0] = r.getValue();
                    label.setText(r.getValue());
                }
            });
        }

        layout.addView(IeRows.actionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_unsent_clear_saved), "#FF453A", v -> {
            try {
                IeDialog dlg = new IeDialog(context)
                        .title(I18n.t(context, R.string.ig_unsent_clear_saved))
                        .message(I18n.t(context, R.string.ig_unsent_clear_saved_confirm));
                dlg.secondary(context.getString(android.R.string.cancel), null);
                dlg.primary(I18n.t(context, R.string.ig_dialog_unsent_clear_yes), () -> {
                    // Also releases the copies kept in the chats, not only the log.
                    if (!ps.reso.instaeclipse.mods.ghost.KeepUnsentMessagesHook.clearSaved(context, null)) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_unsent_clear_failed), Toast.LENGTH_LONG).show();
                        return;
                    }
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_unsent_cleared_saved), Toast.LENGTH_SHORT).show();
                    replaceTop(context, unsentListPage(context));
                });
                dlg.show();
            } catch (Throwable ignored) {}
        }));

        return new IeSheet.Page(I18n.t(context, R.string.ig_dialog_unsent_title), layout).onReturn(() -> {
            if (unsentListStale) replaceTop(context, unsentListPage(context));
        });
    }

    private static void showDistractionOptions(Context context) {
        FeatureCatalog.Category cat = FeatureCatalog.category("focus");
        String accent = accentHex(cat.group);
        LinearLayout layout = createSwitchLayout(context);
        IePalette p = IePalette.of(context);
        int accentText = IePalette.readable(p.accent, p.card, IePalette.TEXT);
        Runnable refreshUsage = addDailyReelStatus(context, layout);
        ToggleRow feed = catalogRow(context, "disableFeed", accent, FeatureFlags.disableFeed);
        ToggleRow stories = catalogRow(context, "disableStories", accent, FeatureFlags.disableStories);
        ToggleRow reels = catalogRow(context, "disableReels", accent, FeatureFlags.disableReels);
        ToggleRow exceptDM = catalogRow(context, "disableReelsExceptDM", accent, FeatureFlags.disableReelsExceptDM);
        ToggleRow explore = catalogRow(context, "disableExplore", accent, FeatureFlags.disableExplore);
        ToggleRow comments = catalogRow(context, "disableComments", accent, FeatureFlags.disableComments);
        ToggleRow daily = catalogRow(context, "dailyReelLimitEnabled", accent, FeatureFlags.dailyReelLimitEnabled);
        ToggleRow dailyDM = catalogRow(context, "dailyReelLimitAllowDM", accent, FeatureFlags.dailyReelLimitAllowDM);
        ToggleRow night = catalogRow(context, "nightReelBlockEnabled", accent, FeatureFlags.nightReelBlockEnabled);
        ToggleRow nightDM = catalogRow(context, "nightReelBlockAllowDM", accent, FeatureFlags.nightReelBlockAllowDM);
        ToggleRow extreme = catalogRow(context, "isExtremeMode", DESTRUCTIVE, FeatureFlags.isExtremeMode);
        ToggleRow[] turnOff = {feed, stories, reels, explore, comments};
        ToggleRow[] switches = {feed, stories, reels, exceptDM, explore, comments, daily, night};
        ToggleRow master = createSwitch(context, I18n.t(context, R.string.cat_all), areAllEnabled(turnOff));
        LinearLayout amount = (LinearLayout) IeRows.valueRow(context,
                I18n.t(context, R.string.ig_reel_limit_allowance), String.valueOf(FeatureFlags.dailyReelLimit));
        amount.setTag("dailyReelLimit");
        TextView value = (TextView) amount.getChildAt(1);
        value.setTextColor(accentText);
        amount.setMinimumHeight(dp(context, 48));
        amount.setFocusable(true);
        amount.setOnClickListener(v -> showDailyReelLimitPicker(context, FeatureFlags.dailyReelLimit, limit -> {
            if (FeatureFlags.isExtremeMode || !DailyReelLimit.isActive()) return;
            FeatureFlags.dailyReelLimit = limit;
            SettingsManager.saveAllFlags();
            SettingsEvents.afterChange("dailyReelLimit");
            value.setText(String.valueOf(limit));
            refreshUsage.run();
        }));

        LinearLayout nightStart = (LinearLayout) IeRows.valueRow(context,
                I18n.t(context, R.string.ig_night_reels_start_label),
                formatNightReelTime(context, FeatureFlags.nightReelBlockStartMinutes));
        LinearLayout nightEnd = (LinearLayout) IeRows.valueRow(context,
                I18n.t(context, R.string.ig_night_reels_end_label),
                formatNightReelTime(context, FeatureFlags.nightReelBlockEndMinutes));
        nightStart.setTag("nightReelBlockStartMinutes");
        nightEnd.setTag("nightReelBlockEndMinutes");
        TextView nightStartValue = (TextView) nightStart.getChildAt(1);
        TextView nightEndValue = (TextView) nightEnd.getChildAt(1);
        nightStartValue.setTextColor(accentText);
        nightEndValue.setTextColor(accentText);
        nightStartValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        nightEndValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        nightStart.setMinimumHeight(dp(context, 48));
        nightEnd.setMinimumHeight(dp(context, 48));
        nightStart.setFocusable(true);
        nightEnd.setFocusable(true);
        nightStart.setBackground(IeRows.ripple(context, 5));
        nightEnd.setBackground(IeRows.ripple(context, 5));
        TextView nightStatus = new TextView(context);
        nightStatus.setTag("nightReelStatus");
        nightStatus.setTextColor(p.sub);
        nightStatus.setTextSize(13);
        nightStatus.setFontFeatureSettings("tnum");
        nightStatus.setPadding(dp(context, 13), 0, dp(context, 11), dp(context, 7));

        Runnable refreshNight = () -> {
            boolean visible = night.isChecked();
            boolean editable = !FeatureFlags.isExtremeMode && !reels.isChecked() && visible;
            nightStart.setVisibility(visible ? View.VISIBLE : View.GONE);
            nightEnd.setVisibility(visible ? View.VISIBLE : View.GONE);
            nightDM.setVisibility(visible ? View.VISIBLE : View.GONE);
            nightStatus.setVisibility(visible ? View.VISIBLE : View.GONE);
            nightStart.setEnabled(editable);
            nightEnd.setEnabled(editable);
            nightDM.setEnabled(visible && !reels.isChecked() && (!FeatureFlags.isExtremeMode
                    || ExtremeRules.canToggle("nightReelBlockAllowDM", nightDM.isChecked())));
            float alpha = editable ? 1f : 0.38f;
            nightStart.setAlpha(alpha);
            nightEnd.setAlpha(alpha);
            nightStatus.setAlpha(alpha);
            String start = formatNightReelTime(context, FeatureFlags.nightReelBlockStartMinutes);
            String end = formatNightReelTime(context, FeatureFlags.nightReelBlockEndMinutes);
            nightStartValue.setText(start);
            nightEndValue.setText(end);
            nightStatus.setText(nightReelStatusText(context));
        };
        nightStart.setOnClickListener(v -> showNightReelTimePicker(context,
                FeatureFlags.nightReelBlockStartMinutes, selected -> {
                    if (selected == FeatureFlags.nightReelBlockEndMinutes) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_night_reels_same_time), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FeatureFlags.nightReelBlockStartMinutes = selected;
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("nightReelBlockStartMinutes");
                    refreshNight.run();
                }));
        nightEnd.setOnClickListener(v -> showNightReelTimePicker(context,
                FeatureFlags.nightReelBlockEndMinutes, selected -> {
                    if (selected == FeatureFlags.nightReelBlockStartMinutes) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_night_reels_same_time), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FeatureFlags.nightReelBlockEndMinutes = selected;
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("nightReelBlockEndMinutes");
                    refreshNight.run();
                }));

        boolean[] updatingMaster = {false};
        Runnable refresh = () -> {
            updatingMaster[0] = true;
            master.setChecked(areAllEnabled(turnOff));
            updatingMaster[0] = false;
            boolean locked = FeatureFlags.isExtremeMode;
            boolean any = false;
            for (ToggleRow row : switches) {
                any |= row.isChecked();
                row.setEnabled(!locked || ExtremeRules.canToggle((String) row.getTag(), row.isChecked()));
            }
            master.setEnabled(!locked);
            if (locked) extreme.setLocked(I18n.t(context, R.string.ig_extreme_active));
            else extreme.setEnabled(any);
            exceptDM.setVisibility(reels.isChecked() ? View.VISIBLE : View.GONE);
            exceptDM.setEnabled(reels.isChecked()
                    && (!locked || ExtremeRules.canToggle("disableReelsExceptDM", exceptDM.isChecked())));
            daily.setEnabled(!reels.isChecked() && (!locked || ExtremeRules.canToggle("dailyReelLimitEnabled", daily.isChecked())));
            dailyDM.setVisibility(daily.isChecked() ? View.VISIBLE : View.GONE);
            dailyDM.setEnabled(daily.isChecked() && !reels.isChecked()
                    && (!locked || ExtremeRules.canToggle("dailyReelLimitAllowDM", dailyDM.isChecked())));
            night.setEnabled(!reels.isChecked() && (!locked || ExtremeRules.canToggle("nightReelBlockEnabled", night.isChecked())));
            amount.setVisibility(daily.isChecked() ? View.VISIBLE : View.GONE);
            amount.setEnabled(!locked && daily.isChecked() && !reels.isChecked());
            amount.setAlpha(amount.isEnabled() ? 1f : 0.38f);
            refreshUsage.run();
            refreshNight.run();
        };
        for (ToggleRow row : switches) {
            String key = (String) row.getTag();
            row.setOnCheckedChangeListener((button, checked) -> {
                ExtrasFeatures.set(key, checked);
                if (row == reels && !checked) exceptDM.setChecked(false);
                String exception = checked && FeatureFlags.isExtremeMode ? ExtremeRules.exceptionOf(key) : null;
                for (ToggleRow dm : new ToggleRow[]{exceptDM, dailyDM, nightDM}) {
                    if (dm.getTag().equals(exception) && dm.isChecked()) dm.setChecked(false);
                }
                if (!updatingMaster[0]) refresh.run();
                SAVES.request();
                SettingsEvents.afterChange(key);
            });
        }
        for (ToggleRow row : new ToggleRow[]{dailyDM, nightDM}) {
            String key = (String) row.getTag();
            row.setOnCheckedChangeListener((button, checked) -> {
                ExtrasFeatures.set(key, checked);
                SettingsManager.saveAllFlags();
                SettingsEvents.afterChange(key);
                if (FeatureFlags.isExtremeMode) refresh.run();
            });
        }
        master.setOnCheckedChangeListener((button, checked) -> {
            if (updatingMaster[0] || FeatureFlags.isExtremeMode) return;
            updatingMaster[0] = true;
            SAVES.batch(() -> {
                for (ToggleRow row : turnOff) row.setChecked(checked);
            });
            updatingMaster[0] = false;
            refresh.run();
        });
        extreme.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) return;
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_dialog_distraction_extreme_title))
                    .message(I18n.t(context, R.string.cat_extreme_message));
            Runnable cancelExtreme = () -> extreme.setChecked(false);
            dlg.secondary(I18n.t(context, R.string.ig_dialog_cancel), cancelExtreme);
            dlg.onClose(cancelExtreme);
            dlg.primary(I18n.t(context, R.string.ig_dialog_yes), () -> {
                FeatureFlags.isExtremeMode = true;
                SettingsManager.saveAllFlags();
                SettingsEvents.afterChange("isExtremeMode");
                refresh.run();
            });
            dlg.show();
        });
        refresh.run();

        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.cat_sec_turn_off)));
        LinearLayout masterBox = IeRows.enableAllContainer(context, master);
        masterBox.setTag("focusAll");
        layout.addView(masterBox);
        layout.addView(IeRows.divider(context));
        layout.addView(cardOf(context, feed, stories, reels, exceptDM, explore, comments));

        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.cat_sec_reel_limits)));
        layout.addView(cardOf(context, daily, amount, dailyDM, night, nightStart, nightEnd, nightStatus, nightDM));

        java.util.List<Runnable> clutterRefresh = new java.util.ArrayList<>();
        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.cat_sec_less_clutter)));
        layout.addView(cardOf(context,
                catalogSwitch(context, FeatureCatalog.item("removeMetaAI"), accent, clutterRefresh),
                catalogSwitch(context, FeatureCatalog.item("hideOnboardingPrompts"), accent, clutterRefresh),
                catalogSwitch(context, FeatureCatalog.item("hidePlusUpsells"), accent, clutterRefresh)));
        runAll(clutterRefresh);

        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.feat_danger_zone)));
        layout.addView(cardOf(context, extreme));
        showSectionDialog(context, I18n.t(context, cat.title), layout);
    }

    private static Runnable addDailyReelStatus(Context context, LinearLayout layout) {
        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.cat_sec_today)));
        LinearLayout group = IeRows.card(context, 5);
        TextView usage = new TextView(context);
        usage.setTag("reelUsage");
        usage.setTextColor(IePalette.of(context).text);
        usage.setTextSize(18);
        usage.setTypeface(null, Typeface.BOLD);
        usage.setFontFeatureSettings("tnum");
        usage.setPadding(dp(context, 13), dp(context, 8), dp(context, 11), dp(context, 8));
        usage.setMinimumHeight(dp(context, 48));
        usage.setFocusable(true);
        usage.setBackground(IeRows.ripple(context, 5));
        Runnable refreshUsage = () -> usage.setText(reelUsageText(context));
        refreshUsage.run();
        usage.setOnClickListener(v -> refreshUsage.run());
        group.addView(usage);

        layout.addView(group);
        return refreshUsage;
    }

    private static String reelUsageText(Context context) {
        int watched = DailyReelLimit.watchedToday();
        return FeatureFlags.dailyReelLimitEnabled
                ? I18n.t(context, R.string.ig_reel_limit_usage, watched, FeatureFlags.dailyReelLimit)
                : I18n.t(context, R.string.ig_reel_usage_count, watched);
    }

    private static String nightReelStatusText(Context context) {
        boolean active = NightReelSchedule.isActiveAt(NightReelSchedule.currentMinutes(),
                FeatureFlags.nightReelBlockStartMinutes, FeatureFlags.nightReelBlockEndMinutes);
        return I18n.t(context, active ? R.string.ig_night_reels_status_active : R.string.ig_night_reels_status_inactive,
                formatNightReelTime(context, active ? FeatureFlags.nightReelBlockEndMinutes
                        : FeatureFlags.nightReelBlockStartMinutes));
    }

    /** Also used by the companion app's Distraction-Free menu. */
    public static void showDailyReelLimitPicker(Context context, int current, java.util.function.IntConsumer save) {
        showAmountPicker(context, current, false, save);
    }

    /** Also used by the companion app's Downloader menu. */
    public static void showStoryCacheDurationPicker(Context context, int current, java.util.function.IntConsumer save) {
        showAmountPicker(context, ps.reso.instaeclipse.utils.media.StoryCacheRetention.normalize(current), true, save);
    }

    private static void showAmountPicker(Context context, int current, boolean durationHours,
                                         java.util.function.IntConsumer save) {
        final int minimum = 1;
        final int maximum = durationHours ? ps.reso.instaeclipse.utils.media.StoryCacheRetention.MAX_HOURS : 10_000;
        final int step = durationHours ? 1 : 5;
        final int initial = Math.max(minimum, Math.min(maximum, current));
        IePalette p = IePalette.of(context);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        int outerPadding = dp(context, 24);
        content.setPadding(outerPadding, dp(context, 22), outerPadding, dp(context, 18));
        content.setBackground(roundedColor(p.dialog, dp(context, 28)));

        TextView title = new TextView(context);
        title.setText(I18n.t(context, durationHours ? R.string.ig_story_cache_duration : R.string.ig_reel_limit_allowance));
        title.setTextColor(p.text);
        title.setTextSize(21);
        title.setTypeface(null, Typeface.BOLD);
        content.addView(title);

        TextView help = new TextView(context);
        help.setText(I18n.t(context, durationHours ? R.string.ig_story_cache_picker_help : R.string.ig_reel_limit_picker_help));
        help.setTextColor(p.sub);
        help.setTextSize(14);
        help.setPadding(0, dp(context, 4), 0, dp(context, 20));
        content.addView(help);

        LinearLayout allowanceCard = new LinearLayout(context);
        allowanceCard.setOrientation(LinearLayout.VERTICAL);
        allowanceCard.setGravity(Gravity.CENTER);
        allowanceCard.setPadding(dp(context, 16), dp(context, 18), dp(context, 16), dp(context, 16));
        GradientDrawable allowanceBackground = roundedColor(p.field, dp(context, 20));
        allowanceBackground.setStroke(dp(context, 1), p.line);
        allowanceCard.setBackground(allowanceBackground);

        LinearLayout stepper = new LinearLayout(context);
        stepper.setOrientation(LinearLayout.HORIZONTAL);
        stepper.setGravity(Gravity.CENTER);

        TextView decrement = reelLimitAction(context, "− " + step, false);
        android.widget.EditText input = new android.widget.EditText(context);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setSelectAllOnFocus(true);
        input.setContentDescription(title.getText());
        input.setText(String.valueOf(initial));
        input.setTextColor(p.text);
        input.setTextSize(32);
        input.setTypeface(null, Typeface.BOLD);
        input.setFontFeatureSettings("tnum");
        input.setGravity(Gravity.CENTER);
        input.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        input.setBackground(reelLimitInputBackground(context));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(context, 64), 1f);
        inputParams.leftMargin = dp(context, 12);
        inputParams.rightMargin = dp(context, 12);
        input.setLayoutParams(inputParams);
        TextView increment = reelLimitAction(context, "+ " + step, false);

        stepper.addView(decrement);
        stepper.addView(input);
        stepper.addView(increment);
        allowanceCard.addView(stepper);

        TextView unit = new TextView(context);
        unit.setText(I18n.t(context, durationHours ? R.string.ig_story_cache_picker_unit : R.string.ig_reel_limit_picker_unit));
        unit.setTextColor(p.sub);
        unit.setTextSize(12);
        unit.setGravity(Gravity.CENTER);
        unit.setPadding(0, dp(context, 8), 0, 0);
        allowanceCard.addView(unit);
        content.addView(allowanceCard);

        TextView quickLabel = new TextView(context);
        quickLabel.setText(I18n.t(context, R.string.ig_reel_limit_picker_quick));
        quickLabel.setTextColor(p.sub);
        quickLabel.setTextSize(12);
        quickLabel.setTypeface(null, Typeface.BOLD);
        quickLabel.setLetterSpacing(0.06f);
        quickLabel.setPadding(0, dp(context, 20), 0, dp(context, 8));
        content.addView(quickLabel);

        LinearLayout presets = new LinearLayout(context);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] presetValues = durationHours ? new int[]{6, 24, 72, 168} : new int[]{25, 50, 100, 200};
        TextView[] presetViews = new TextView[presetValues.length];
        for (int i = 0; i < presetValues.length; i++) {
            TextView preset = new TextView(context);
            preset.setText(String.valueOf(presetValues[i]));
            preset.setTextSize(14);
            preset.setTypeface(null, Typeface.BOLD);
            preset.setGravity(Gravity.CENTER);
            preset.setMinHeight(dp(context, 44));
            preset.setFocusable(true);
            LinearLayout.LayoutParams presetParams = new LinearLayout.LayoutParams(0, dp(context, 44), 1f);
            if (i > 0) presetParams.leftMargin = dp(context, 8);
            preset.setLayoutParams(presetParams);
            final int value = presetValues[i];
            preset.setOnClickListener(v -> input.setText(String.valueOf(value)));
            presetViews[i] = preset;
            presets.addView(preset);
        }
        content.addView(presets);

        Runnable refreshPresets = () -> {
            int selected = parseReelLimit(input.getText().toString(), -1);
            for (int i = 0; i < presetViews.length; i++) {
                boolean active = selected == presetValues[i];
                presetViews[i].setTextColor(active ? p.text : p.sub);
                presetViews[i].setBackground(reelLimitPresetBackground(context, active));
                presetViews[i].setSelected(active);
            }
        };
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable editable) {
                input.setError(null);
                refreshPresets.run();
            }
        });
        refreshPresets.run();

        decrement.setOnClickListener(v -> input.setText(String.valueOf(Math.max(minimum,
                (long) parseReelLimit(input.getText().toString(), initial) - step))));
        increment.setOnClickListener(v -> input.setText(String.valueOf(Math.min(maximum,
                (long) parseReelLimit(input.getText().toString(), initial) + step))));

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(context, 22), 0, 0);
        TextView cancel = reelLimitAction(context, I18n.t(context, R.string.ig_dialog_cancel), false);
        TextView confirm = reelLimitAction(context, I18n.t(context, R.string.ig_dialog_ok), true);
        LinearLayout.LayoutParams confirmParams = (LinearLayout.LayoutParams) confirm.getLayoutParams();
        confirmParams.leftMargin = dp(context, 8);
        actions.addView(cancel);
        actions.addView(confirm);
        content.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(context).setView(content).setCancelable(true).create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            int limit = parseReelLimit(input.getText().toString(), -1);
            if (limit < minimum || limit > maximum) {
                input.setError(I18n.t(context, durationHours ? R.string.ig_story_cache_invalid : R.string.ig_reel_limit_invalid));
                input.requestFocus();
                return;
            }
            save.accept(limit);
            dialog.dismiss();
        });
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(context, 420),
                    context.getResources().getDisplayMetrics().widthPixels - dp(context, 32));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        dialog.show();
    }

    private static int parseReelLimit(String text, int fallback) {
        try { return Integer.parseInt(text.trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static GradientDrawable reelLimitInputBackground(Context context) {
        IePalette p = IePalette.of(context);
        GradientDrawable background = roundedColor(p.pressed, dp(context, 14));
        background.setStroke(dp(context, 1), p.line);
        return background;
    }

    private static StateListDrawable reelLimitPresetBackground(Context context, boolean active) {
        IePalette p = IePalette.of(context);
        int accent = p.accent;
        GradientDrawable normal = roundedColor(active
                ? ((accent & 0x00FFFFFF) | 0x46000000) : p.field, dp(context, 12));
        normal.setStroke(dp(context, 1), active ? accent : p.line);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, roundedColor(p.pressed, dp(context, 12)));
        background.addState(new int[]{}, normal);
        return background;
    }

    private static TextView reelLimitAction(Context context, String label, boolean primary) {
        IePalette p = IePalette.of(context);
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? p.onAccent : IePalette.readable(p.accent, p.field, IePalette.TEXT));
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinWidth(dp(context, 72));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, roundedColor(p.pressed, dp(context, 14)));
        background.addState(new int[]{}, roundedColor(primary ? p.accent : p.field, dp(context, 14)));
        button.setBackground(background);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 48)));
        return button;
    }

    /** Also used by the companion app's Distraction-Free menu. */
    public static void showNightReelTimePicker(Context context, int currentMinutes,
                                                java.util.function.IntConsumer save) {
        int valid = NightReelSchedule.validMinutes(currentMinutes,
                NightReelSchedule.DEFAULT_START_MINUTES);
        new android.app.TimePickerDialog(context, (view, hour, minute) -> save.accept(hour * 60 + minute),
                valid / 60, valid % 60, android.text.format.DateFormat.is24HourFormat(context)).show();
    }

    public static String formatNightReelTime(Context context, int minutes) {
        int valid = NightReelSchedule.validMinutes(minutes, 0);
        java.util.Calendar time = java.util.Calendar.getInstance();
        time.set(java.util.Calendar.HOUR_OF_DAY, valid / 60);
        time.set(java.util.Calendar.MINUTE, valid % 60);
        return android.text.format.DateFormat.getTimeFormat(context).format(time.getTime());
    }

    static String[] startupTabNames(Context context) {
        String[] tabs = ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS;
        String[] names = {
                I18n.t(context, R.string.ig_startup_tab_default),
                I18n.t(context, R.string.ig_nav_name_home),
                I18n.t(context, R.string.ig_nav_name_reels),
                I18n.t(context, R.string.ig_nav_name_messages),
                I18n.t(context, R.string.ig_nav_name_search),
                I18n.t(context, R.string.ig_nav_name_profile),
                I18n.t(context, R.string.ig_nav_name_activity)};
        for (int i = 1; i < names.length; i++) {
            if (StartupTabPolicy.hidden(tabs[i], StartupTabPolicy::flagOn)) {
                names[i] = I18n.t(context, R.string.ig_startup_tab_hidden, names[i]);
            }
        }
        return names;
    }

    private static String startupTabLabel(Context context) {
        int i = java.util.Arrays.asList(ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS).indexOf(FeatureFlags.startupTab);
        return I18n.t(context, R.string.ig_dialog_extras_startup_tab) + "  ·  " + startupTabNames(context)[Math.max(i, 0)];
    }

    /** Which tab Instagram opens on (FeatureFlags.startupTab). */
    private static void showStartupTabDialog(Context context, TextView label) {
        String[] tabs = ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS;
        int current = Math.max(java.util.Arrays.asList(tabs).indexOf(FeatureFlags.startupTab), 0);
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_dialog_extras_startup_tab))
                .choices(startupTabNames(context), current, which -> {
                    FeatureFlags.startupTab = tabs[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("startupTab");
                    if (label != null) label.setText(startupTabLabel(context));
                })
                .show();
    }

    /** #238: reorder the bottom-nav tabs (FeatureFlags.navTabOrder). */
    private static void showTabOrderDialog(Context context) {
        final String[] ids = ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.ORDERABLE_TABS;
        final java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("feed_tab", I18n.t(context, R.string.ig_nav_name_home));
        labels.put("clips_tab", I18n.t(context, R.string.ig_nav_name_reels));
        labels.put("search_tab", I18n.t(context, R.string.ig_nav_name_search));
        labels.put("creation_tab", I18n.t(context, R.string.ig_nav_name_create));
        labels.put("direct_tab", I18n.t(context, R.string.ig_nav_name_messages));
        labels.put("news_tab", I18n.t(context, R.string.ig_nav_name_activity));
        labels.put("profile_tab", I18n.t(context, R.string.ig_nav_name_profile));
        final java.util.List<String> order = new java.util.ArrayList<>();
        for (String n : FeatureFlags.navTabOrder.split(","))
            if (java.util.Arrays.asList(ids).contains(n.trim()) && !order.contains(n.trim())) order.add(n.trim());
        for (String n : ids) if (!order.contains(n)) order.add(n);
        final IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_nav_tab_order));
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dlg.dp(16), dlg.dp(2), dlg.dp(6), dlg.dp(2));
                row.setBackground(dlg.cardBg());
                TextView name = new TextView(context);
                name.setText((i + 1) + ".  " + labels.get(order.get(i)));
                name.setTextColor(dlg.text);
                name.setTextSize(15);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                for (final int dir : new int[]{-1, 1}) {
                    TextView b = new TextView(context);
                    b.setText(dir < 0 ? "↑" : "↓");
                    b.setTextSize(20);
                    b.setGravity(Gravity.CENTER);
                    boolean on = dir < 0 ? idx > 0 : idx < order.size() - 1;
                    b.setTextColor(on ? dlg.accent : (dlg.sub & 0x00FFFFFF) | 0x55000000);
                    if (on) b.setOnClickListener(v -> {
                        java.util.Collections.swap(order, idx, idx + dir);
                        render[0].run();
                    });
                    row.addView(b, new LinearLayout.LayoutParams(dlg.dp(44), dlg.dp(44)));
                }
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dlg.dp(8);
                list.addView(row, rlp);
            }
        };
        render[0].run();
        dlg.view(list);
        TextView hint = new TextView(context);
        hint.setText(I18n.t(context, R.string.ig_nav_tab_order_hint));
        hint.setTextColor(dlg.sub);
        hint.setTextSize(13);
        dlg.view(hint);
        dlg.secondary(I18n.t(context, R.string.ig_nav_tab_order_reset), () -> saveTabOrder(""));
        dlg.secondary(context.getString(android.R.string.cancel), null);
        dlg.primary(context.getString(android.R.string.ok), () ->
                saveTabOrder(order.equals(java.util.Arrays.asList(ids)) ? "" : String.join(",", order)));
        dlg.show();
    }

    private static void saveTabOrder(String order) {
        FeatureFlags.navTabOrder = order;
        SettingsManager.saveAllFlags();
        SettingsEvents.afterChange("navTabOrder");
    }

    public static final int[] FEED_CORNER_RADII = {0, 8, 12, 16, 20, 24};

    static String feedCornerName(Context context, int dp) {
        return dp <= 0 ? I18n.t(context, R.string.ig_dialog_extras_share_domain_off) : dp + " dp";
    }

    private static String feedCornerLabel(Context context) {
        return I18n.t(context, R.string.ig_feed_corner_radius) + "  ·  " + feedCornerName(context, FeatureFlags.feedCornerRadius);
    }

    /** Rounded feed post corners (#276): applies to posts as they are laid out. */
    private static void showFeedCornerDialog(Context context, TextView label) {
        CharSequence[] rows = new CharSequence[FEED_CORNER_RADII.length];
        int checked = 0;
        for (int i = 0; i < rows.length; i++) {
            rows[i] = feedCornerName(context, FEED_CORNER_RADII[i]);
            if (FEED_CORNER_RADII[i] == FeatureFlags.feedCornerRadius) checked = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_feed_corner_radius))
                .choices(rows, checked, which -> {
                    FeatureFlags.feedCornerRadius = FEED_CORNER_RADII[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("feedCornerRadius");
                    if (label != null) label.setText(feedCornerLabel(context));
                })
                .show();
    }

    private static final int[] REEL_QUALITIES = {0, 360, 480, 720, 1080, Integer.MAX_VALUE};

    private static String[] reelQualityNames(Context context) {
        return new String[]{
                I18n.t(context, R.string.ig_dialog_quality_auto),
                I18n.t(context, R.string.ig_dialog_quality_360),
                I18n.t(context, R.string.ig_dialog_quality_480),
                I18n.t(context, R.string.ig_dialog_quality_720),
                I18n.t(context, R.string.ig_dialog_quality_1080),
                I18n.t(context, R.string.ig_dialog_quality_max)};
    }

    private static int reelQualityIndex() {
        for (int i = 0; i < REEL_QUALITIES.length; i++) if (REEL_QUALITIES[i] == FeatureFlags.forceReelQuality) return i;
        return 0;
    }

    private static String reelQualityLabel(Context context) {
        return I18n.t(context, R.string.ig_dialog_quality_force_reels) + "  ·  " + reelQualityNames(context)[reelQualityIndex()];
    }

    private static void showReelQualityDialog(Context context, TextView label) {
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_dialog_quality_force_reels))
                .choices(reelQualityNames(context), reelQualityIndex(), which -> {
                    FeatureFlags.forceReelQuality = REEL_QUALITIES[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("forceReelQuality");
                    if (label != null) label.setText(reelQualityLabel(context));
                })
                .show();
    }

    public static final int[] STORY_TRAY_SIZES = {100, 90, 80, 70, 60, 50};

    private static String storyTraySizeLabel(Context context) {
        return I18n.t(context, R.string.ig_story_tray_size) + "  ·  " + FeatureFlags.storyTrayScale + "%";
    }

    /** Story tray size (#283): applies at once, the tray resizes on its next layout. */
    private static void showStoryTraySizeDialog(Context context, TextView label) {
        CharSequence[] rows = new CharSequence[STORY_TRAY_SIZES.length];
        int checked = 0;
        for (int i = 0; i < rows.length; i++) {
            rows[i] = STORY_TRAY_SIZES[i] + "%";
            if (STORY_TRAY_SIZES[i] == FeatureFlags.storyTrayScale) checked = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.ig_story_tray_size))
                .choices(rows, checked, which -> {
                    FeatureFlags.storyTrayScale = STORY_TRAY_SIZES[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("storyTrayScale");
                    if (label != null) label.setText(storyTraySizeLabel(context));
                })
                .show();
    }

    /** A color value shown as swatch(es) and its hex. */
    public static CharSequence storyRingSwatch(String value) {
        int[] colors = ps.reso.instaeclipse.mods.ui.StoryTrayHook.parseColors(value);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (colors != null) for (int c : colors) {
            int start = sb.length();
            sb.append("\u25CF");
            sb.setSpan(new android.text.style.ForegroundColorSpan(c), start, sb.length(), 0);
        }
        return sb.append("  ").append(value.replace(",", " \u2192 "));
    }

    private static CharSequence storyRingColorLabel(Context context) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(
                I18n.t(context, R.string.ig_story_ring_color) + "  ·  ");
        return FeatureFlags.storyRingColor.isEmpty()
                ? sb.append(I18n.t(context, R.string.ig_story_ring_default))
                : sb.append(storyRingSwatch(FeatureFlags.storyRingColor));
    }

    /** Unseen story ring color (#283): Instagram's, one color, or a gradient of up to 4, with a live preview. */
    private static void showStoryRingColorDialog(Context context, TextView label) {
        try {
            IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_story_ring_color));
            String[] picked = {FeatureFlags.storyRingColor};
            dlg.view(RingColorPicker.build(context, FeatureFlags.storyRingColor,
                    new RingColorPicker.Palette(dlg.text, dlg.sub, dlg.field, dlg.accent), v -> picked[0] = v));
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> saveStoryRingColor(context, picked[0], label));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Menu) Could not open the story ring colour picker", t);
        }
    }

    private static void saveStoryRingColor(Context context, String value, TextView label) {
        FeatureFlags.storyRingColor = value;
        SettingsManager.saveAllFlags();
        SettingsEvents.afterChange("storyRingColor");
        if (label != null) label.setText(storyRingColorLabel(context));
    }


    private static final String[] PAGE_LOCKS = {"lockWholeApp", "lockDirectMessages", "lockSpecificMessages"};

    private static void addLockCards(Context context, LinearLayout layout, String accent, Runnable rebuild) {
        boolean hasCode = LockFlows.hasPasscode();
        java.util.List<Runnable> refresh = new java.util.ArrayList<>();
        Runnable changed = () -> {
            if (LockFlows.hasPasscode() != hasCode) rebuild.run();
            else runAll(refresh);
        };
        LinearLayout passcodeCard = IeRows.card(context, 5);
        FeatureCatalog.Item passcode = FeatureCatalog.item("lockPasscode");
        LinearLayout passcodeRow = detailRow(context, passcode.icon, accent,
                I18n.t(context, hasCode ? R.string.ig_lock_change_passcode : R.string.ig_lock_set_passcode),
                v -> LockFlows.setPasscode(activityOf(context), rebuild, null));
        passcodeRow.setTag(passcode.key);
        passcodeCard.addView(passcodeRow);
        if (hasCode) {
            LinearLayout removeRow = detailRow(context, R.drawable.ic_delete, accent,
                    I18n.t(context, R.string.ig_lock_remove_passcode), v -> removePasscode(context, rebuild));
            removeRow.setTag("lockPasscodeRemove");
            passcodeCard.addView(removeRow);
        }
        passcodeCard.addView(lockSwitch(context, "lockUseFingerprint", accent, refresh, changed));
        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.cat_sec_passcode)));
        layout.addView(passcodeCard);
        if (!hasCode) layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_lock_no_code_hint)));

        LinearLayout cardView = IeRows.card(context, 5);
        for (String key : PAGE_LOCKS) cardView.addView(lockSwitch(context, key, accent, refresh, changed));
        FeatureCatalog.Item chooseChats = FeatureCatalog.item("lockChooseChats");
        LinearLayout chooseRow = detailRow(context, chooseChats.icon, accent, I18n.t(context, chooseChats.label), v ->
                LockFlows.authenticate(activityOf(context), I18n.t(context, R.string.ig_lock_change_locks_prompt),
                        "settings", () -> showChatPicker(context, true)));
        chooseRow.setTag(chooseChats.key);
        cardView.addView(chooseRow);
        cardView.addView(lockSwitch(context, "lockDirectAlways", accent, refresh, changed));
        cardView.addView(lockSwitch(context, "lockSavedCollections", accent, refresh, changed));
        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.ig_dialog_misc_lock_section)));
        layout.addView(cardView);
        runAll(refresh);
    }

    private static ToggleRow lockSwitch(Context context, String key, String accent, java.util.List<Runnable> refresh,
                                        Runnable changed) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        ToggleRow row = catalogRow(context, key, accent, ExtrasFeatures.get(key));
        row.setOnCheckedChangeListener((b, checked) -> {
            if (suppressLockToggle) return;
            if (!SettingsGuard.request(context, key, checked, () -> {
                setQuietly(row, checked);
                ExtrasFeatures.set(key, checked);
                SettingsManager.saveAllFlags();
                changed.run();
            })) setQuietly(row, !checked);
        });
        if (PasscodeRules.isLock(key)) {
            refresh.add(() -> row.setSubtitle(ExtrasFeatures.get(key) && !LockFlows.hasPasscode()
                    ? I18n.t(context, R.string.ig_lock_on_needs_passcode) : describe(context, item)));
        }
        return row;
    }

    private static void setQuietly(ToggleRow row, boolean checked) {
        suppressLockToggle = true;
        row.setChecked(checked);
        suppressLockToggle = false;
    }

    private static void removePasscode(Context context, Runnable rebuild) {
        LockFlows.authenticate(activityOf(context), I18n.t(context, R.string.ig_lock_settings_prompt), "settings", () -> {
            try {
                IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_lock_remove_passcode))
                        .message(I18n.t(context, R.string.ig_lock_remove_passcode_confirm));
                dlg.secondary(context.getString(android.R.string.cancel), null);
                dlg.destructive(I18n.t(context, R.string.ig_lock_remove_passcode), () -> {
                    LockFlows.removePasscode();
                    SettingsManager.saveAllFlags();
                    rebuild.run();
                });
                dlg.show();
            } catch (Throwable t) {
                ModuleLog.warn("(IE|Menu) Could not ask before removing the passcode", t);
            }
        });
    }

    private static void showLockOptions(Context context) {
        showSection(context, lockPage(context));
    }

    private static IeSheet.Page lockPage(Context context) {
        FeatureCatalog.Category cat = FeatureCatalog.category("lock");
        String accent = accentHex(cat.group);
        LinearLayout layout = createSwitchLayout(context);
        Runnable rebuild = () -> replaceTop(context, lockPage(context));
        addLockCards(context, layout, accent, rebuild);
        layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_lock_relock_hint)));
        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.cat_sec_hidden_chats)));
        layout.addView(hiddenChatsCard(context, accent));
        layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_hide_chats_picker_hint)));
        layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_hide_chats_view_hint)));
        if (!LockFlows.hasPasscode()) {
            layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_hide_chats_needs_passcode)));
            LinearLayout setCard = IeRows.card(context, 5);
            FeatureCatalog.Item passcode = FeatureCatalog.item("lockPasscode");
            LinearLayout setRow = detailRow(context, passcode.icon, accent, I18n.t(context, R.string.ig_lock_set_passcode),
                    v -> LockFlows.setPasscode(activityOf(context), rebuild, null));
            setRow.setTag("hidePasscode");
            setCard.addView(setRow);
            layout.addView(setCard);
        }
        return new IeSheet.Page(I18n.t(context, cat.title), layout);
    }

    private static LinearLayout hiddenChatsCard(Context context, String accent) {
        java.util.List<Runnable> refresh = new java.util.ArrayList<>();
        LinearLayout hideCard = IeRows.card(context, 5);
        hideCard.addView(catalogSwitch(context, FeatureCatalog.item("hideSpecificChats"), accent, refresh));
        hideCard.addView(catalogSwitch(context, FeatureCatalog.item("showHideChatButton"), accent, refresh));
        hideCard.addView(catalogAction(context, FeatureCatalog.item("hideChooseChats"), accent, refresh,
                v -> SettingsGuard.request(context, SettingsGuard.HIDE_PICKER, null, () -> showChatPicker(context))));
        runAll(refresh);
        return hideCard;
    }

    /**
     * Profile spoofer editor: live preview of your profile header, then name / username / bio,
     * counts (empty = real), the badge and the picture. The picture is chosen in the InstaEclipse
     * app (Instagram's process can't open a picker for us): "Choose photo" saves what is typed,
     * closes this and opens the app's picker, which sends the picture back.
     */
    private static void showProfileSpoofDialog(Context context) {
        try {
            IeDialog dlg = new IeDialog(context).title(I18n.t(context, R.string.ig_profile_spoofer_editor_title));
            ProfileSpoofEditor.Values v = new ProfileSpoofEditor.Values();
            v.username = FeatureFlags.spoofUsername;
            v.name = FeatureFlags.spoofName;
            v.bio = FeatureFlags.spoofBio;
            v.avatar = FeatureFlags.spoofAvatar;
            v.posts = FeatureFlags.spoofPosts;
            v.followers = FeatureFlags.spoofFollowers;
            v.following = FeatureFlags.spoofFollowing;
            v.verified = FeatureFlags.spoofVerified;
            ProfileSpoofEditor[] editor = new ProfileSpoofEditor[1];
            editor[0] = new ProfileSpoofEditor(dlg.ctx, v,
                    new ProfileSpoofEditor.Palette(dlg.text, dlg.sub, dlg.field, dlg.accent, dlg.border),
                    () -> {
                        saveProfileSpoof(editor[0].values());
                        dlg.dismiss();
                        ModuleActivityLauncher.launch(context, ps.reso.instaeclipse.utils.ipc.BrokerPayload.PROFILE_PHOTO, null);
                    },
                    I18n.t(context, R.string.ig_profile_spoofer_photo_in_app),
                    ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.knownUsername());
            dlg.view(editor[0].view());
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(I18n.t(context, R.string.ig_profile_spoofer_save), () -> saveProfileSpoof(editor[0].values()));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Menu) Could not open the profile spoofer", t);
        }
    }

    private static void saveProfileSpoof(ProfileSpoofEditor.Values v) {
        FeatureFlags.spoofUsername = v.username;
        FeatureFlags.spoofName = v.name;
        FeatureFlags.spoofBio = v.bio;
        FeatureFlags.spoofAvatar = v.avatar;
        FeatureFlags.spoofPosts = v.posts;
        FeatureFlags.spoofFollowers = v.followers;
        FeatureFlags.spoofFollowing = v.following;
        FeatureFlags.spoofVerified = v.verified;
        SettingsManager.saveAllFlags();
        ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.refreshNow();
    }

    /** A typed count, or -1 (keep the real one) when empty or not a number. */
    public static int parseSpoofCount(String s) {
        return ps.reso.instaeclipse.utils.profile.SpoofCounts.parse(s);
    }

    /** Unwraps an Activity from a Context (dialogs run inside IG's activity, possibly wrapped). */
    private static Activity activityOf(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** Lists local inbox threads. Each switch explicitly sets hidden state; it never toggles blindly. */
    private static void showChatPicker(Context context) {
        showChatPicker(context, false);
    }

    /** Inbox chats plus the already hidden (or locked) ones, each with an explicit on/off switch. */
    private static void showChatPicker(Context context, boolean locking) {
        LinearLayout layout = createSwitchLayout(context);
        java.util.Map<String, String> chats =
                ps.reso.instaeclipse.mods.ghost.HideChatsHook.knownThreads();
        java.util.Map<String, String> selectedChats = locking
                ? ps.reso.instaeclipse.utils.ghost.LockedThreads.all()
                : ps.reso.instaeclipse.utils.ghost.HiddenThreads.all();
        int pickerTitle = locking ? R.string.ig_lock_select_chats : R.string.ig_hide_chats_select;
        for (java.util.Map.Entry<String, String> entry : selectedChats.entrySet()) {
            String knownTitle = chats.get(entry.getKey());
            if (knownTitle == null || knownTitle.isEmpty()) chats.put(entry.getKey(), entry.getValue());
        }
        if (chats.isEmpty()) {
            layout.addView(IeRows.helpText(context,
                    I18n.t(context, locking ? R.string.ig_lock_selected_empty : R.string.ig_hide_chats_picker_empty)));
            showSectionDialog(context, I18n.t(context, pickerTitle), layout);
            return;
        }
        LinearLayout cardView = IeRows.card(context, 5);
        for (java.util.Map.Entry<String, String> entry : chats.entrySet()) {
            final String id = entry.getKey();
            final String storedTitle = entry.getValue() == null ? "" : entry.getValue().trim();
            String suffix = id.length() > 6 ? id.substring(id.length() - 6) : id;
            String label = storedTitle.isEmpty()
                    ? I18n.t(context, R.string.ig_hide_chats_unknown) + "  ·  …" + suffix
                    : storedTitle;
            ToggleRow row = createSwitch(context, R.drawable.ic_chat, accentHex(FeatureCatalog.Group.PRIVACY), label,
                    selectedChats.containsKey(id));
            row.setOnCheckedChangeListener((button, checked) -> {
                if (locking) {
                    ps.reso.instaeclipse.utils.ghost.LockedThreads.set(id, storedTitle, checked);
                    return;
                }
                ps.reso.instaeclipse.utils.ghost.HiddenThreads.setHidden(id, storedTitle, checked);
                ps.reso.instaeclipse.mods.ghost.HideChatsHook.refreshVisibleButtons();
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, checked
                                ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                        Toast.LENGTH_SHORT).show();
            });
            cardView.addView(row);
        }
        layout.addView(cardView);
        showSectionDialog(context, I18n.t(context, pickerTitle), layout);
    }

    private static boolean suppressLockToggle = false;

    private static void setRowEnabled(View row, boolean on) {
        row.setEnabled(on);
        row.setAlpha(on ? 1f : 0.4f);
    }

    /** The format, or "Device default", with a sample of the current time. */
    private static String timestampFormatName(Context context, String pattern) {
        String sample = ps.reso.instaeclipse.utils.text.TimestampFormat.formatAsPosts(context, pattern, new java.util.Date());
        return pattern.isEmpty() ? I18n.t(context, R.string.ig_timestamp_format_default) + "  ·  " + sample : sample;
    }

    private static String timestampFormatLabel(Context context) {
        return I18n.t(context, R.string.ig_timestamp_format) + "  ·  " + timestampFormatName(context, FeatureFlags.exactTimestampFormat);
    }

    /** Date format chooser: presets shown as today's date in that format, plus a custom pattern. */
    private static void showTimestampFormatDialog(Context context, TextView label) {
        try {
            String[] presets = ps.reso.instaeclipse.utils.text.TimestampFormat.PRESETS;
            String current = FeatureFlags.exactTimestampFormat;
            CharSequence[] rows = new CharSequence[presets.length + 1];
            int checked = presets.length; // custom
            for (int i = 0; i < presets.length; i++) {
                rows[i] = timestampFormatName(context, presets[i]);
                if (presets[i].equals(current)) checked = i;
            }
            rows[presets.length] = checked == presets.length
                    ? I18n.t(context, R.string.ig_timestamp_format_custom) + "  ·  " + timestampFormatName(context, current)
                    : I18n.t(context, R.string.ig_timestamp_format_custom);
            new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_timestamp_format))
                    .choices(rows, checked, i -> {
                        if (i == presets.length) { showCustomTimestampFormat(context, label); return; }
                        saveTimestampFormat(context, presets[i], label);
                    })
                    .show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Menu) Could not open the timestamp format picker", t);
        }
    }

    private static void showCustomTimestampFormat(Context context, TextView label) {
        try {
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_timestamp_format_custom))
                    .message(I18n.t(context, R.string.ig_timestamp_format_custom_hint));
            final android.widget.EditText input = dlg.input("yyyy-MM-dd HH:mm:ss");
            input.setText(FeatureFlags.exactTimestampFormat.isEmpty() ? "yyyy-MM-dd HH:mm:ss" : FeatureFlags.exactTimestampFormat);
            final TextView preview = dlg.quote("", 2);
            Runnable update = () -> {
                String p = input.getText().toString();
                preview.setText(ps.reso.instaeclipse.utils.text.TimestampFormat.isValid(p) && !p.isEmpty()
                        ? ps.reso.instaeclipse.utils.text.TimestampFormat.formatAsPosts(context, p, new java.util.Date())
                        : I18n.t(context, R.string.ig_timestamp_format_invalid));
            };
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(android.text.Editable s) { update.run(); }
            });
            update.run();
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                String p = input.getText().toString().trim();
                if (!ps.reso.instaeclipse.utils.text.TimestampFormat.isValid(p)) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_timestamp_format_invalid), Toast.LENGTH_SHORT).show();
                    return;
                }
                saveTimestampFormat(context, p, label);
            });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Menu) Could not open the custom timestamp format", t);
        }
    }

    private static void saveTimestampFormat(Context context, String pattern, TextView label) {
        FeatureFlags.exactTimestampFormat = pattern;
        SettingsManager.saveAllFlags();
        SettingsEvents.afterChange("exactTimestampFormat");
        if (label != null) label.setText(timestampFormatLabel(context));
    }

    private static String shareDomainLabel(Context context) {
        String d = FeatureFlags.customShareDomain;
        return I18n.t(context, R.string.ig_dialog_extras_share_domain) + "  ·  "
                + (d == null || d.isEmpty() ? I18n.t(context, R.string.ig_dialog_extras_share_domain_off) : d);
    }

    /** Custom share domain: replaces instagram.com in links Instagram copies or shares. */
    private static void showShareDomainDialog(Context context, TextView label) {
        try {
            IeDialog dlg = new IeDialog(context)
                    .title(I18n.t(context, R.string.ig_dialog_extras_share_domain))
                    .message(I18n.t(context, R.string.ig_dialog_extras_share_domain_hint));
            final android.widget.EditText input = dlg.input("ddinstagram.com");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            input.setText(FeatureFlags.customShareDomain);
            dlg.secondary(context.getString(android.R.string.cancel), null);
            dlg.primary(context.getString(android.R.string.ok), () -> {
                String raw = input.getText().toString();
                String domain = ps.reso.instaeclipse.mods.ads.ShareDomain.normalize(raw);
                if (domain == null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_extras_share_domain_invalid), Toast.LENGTH_SHORT).show();
                    return;
                }
                FeatureFlags.customShareDomain = domain;
                SettingsManager.saveAllFlags();
                SettingsEvents.afterChange("customShareDomain");
                if (label != null) label.setText(shareDomainLabel(context));
            });
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.warn("(IE|Menu) Could not open the share domain dialog", t);
        }
    }

    private static void showLocationOptions(Context context) {
        showSection(context, locationPage(context));
    }

    private static IeSheet.Page locationPage(Context context) {
        FeatureCatalog.Category cat = FeatureCatalog.category("location");
        String accent = accentHex(cat.group);
        LinearLayout layout = createSwitchLayout(context);

        boolean set = ps.reso.instaeclipse.mods.location.LocationPresets.valid(FeatureFlags.spoofLat, FeatureFlags.spoofLng);
        java.util.List<Runnable> refresh = new java.util.ArrayList<>();
        LinearLayout toggleCard = IeRows.card(context, 5);
        ToggleRow spoof = catalogSwitch(context, FeatureCatalog.item("spoofLocation"), accent, refresh);
        pickPlaceFirst(context, spoof);
        toggleCard.addView(spoof);
        runAll(refresh);
        layout.addView(toggleCard);

        String place = !set ? I18n.t(context, R.string.ig_dialog_location_unset)
                : !FeatureFlags.spoofLabel.isEmpty() ? FeatureFlags.spoofLabel
                : ps.reso.instaeclipse.mods.location.LocationPresets.coords(FeatureFlags.spoofLat, FeatureFlags.spoofLng);

        FeatureCatalog.Item placeItem = FeatureCatalog.item("locationPlace");
        LinearLayout current = IeRows.card(context, 5);
        View placeRow = IeRows.valueRow(context, I18n.t(context, placeItem.label), place);
        placeRow.setTag(placeItem.key);
        current.addView(placeRow);
        LinearLayout pickRow = detailRow(context, placeItem.icon, accent, I18n.t(context, R.string.ig_dialog_location_pick),
                v -> openLocationPicker(context, false));
        pickRow.setTag(placeItem.key);
        current.addView(pickRow);
        layout.addView(current);

        java.util.List<ps.reso.instaeclipse.mods.location.LocationPresets.Preset> recent =
                ps.reso.instaeclipse.mods.location.LocationPresets.parse(FeatureFlags.spoofRecent);
        FeatureCatalog.Item recentItem = FeatureCatalog.item("locationRecent");
        TextView recentHeader = IeRows.sectionHeader(context, I18n.t(context, recentItem.label));
        LinearLayout recentCard = IeRows.card(context, 5);
        recentCard.setTag(recentItem.key);
        for (ps.reso.instaeclipse.mods.location.LocationPresets.Preset p : recent) {
            boolean active = set && ps.reso.instaeclipse.mods.location.LocationPresets.distanceMeters(
                    p.lat, p.lng, FeatureFlags.spoofLat, FeatureFlags.spoofLng) < 5;
            RadioRow row = new RadioRow(context, p.title(), active);
            row.setOnClickListener(v -> {
                FeatureFlags.spoofLat = p.lat;
                FeatureFlags.spoofLng = p.lng;
                FeatureFlags.spoofLabel = p.label;
                FeatureFlags.spoofRecent = ps.reso.instaeclipse.mods.location.LocationPresets.serialize(
                        ps.reso.instaeclipse.mods.location.LocationPresets.remember(recent, p));
                SettingsManager.saveAllFlags();
                SettingsEvents.afterChange(java.util.Arrays.asList("spoofLat", "spoofLng", "spoofLabel", "spoofRecent"));
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, FeatureFlags.spoofLocation
                        ? R.string.ig_dialog_location_applied : R.string.loc_saved_spoof_off), Toast.LENGTH_SHORT).show();
                replaceTop(context, locationPage(context));
            });
            recentCard.addView(row);
        }
        recentHeader.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        recentCard.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
        layout.addView(recentHeader);
        layout.addView(recentCard);

        return new IeSheet.Page(I18n.t(context, cat.title), layout);
    }

    private static void pickPlaceFirst(Context context, ToggleRow spoof) {
        if (FeatureFlags.spoofLocation
                || ps.reso.instaeclipse.mods.location.LocationPresets.valid(FeatureFlags.spoofLat, FeatureFlags.spoofLng)) {
            return;
        }
        spoof.setOnCheckedChangeListener((b, checked) -> {
            if (suppressLockToggle || !checked) return;
            setQuietly(spoof, false);
            openLocationPicker(context, true);
        });
    }

    private static void openLocationPicker(Context context, boolean enableOnPick) {
        Bundle extras = new Bundle();
        extras.putDouble(LocationPickerActivity.EXTRA_LAT, FeatureFlags.spoofLat);
        extras.putDouble(LocationPickerActivity.EXTRA_LNG, FeatureFlags.spoofLng);
        if (enableOnPick) extras.putBoolean(LocationPickerActivity.EXTRA_ENABLE_ON_PICK, true);
        if (ModuleActivityLauncher.launch(context, ps.reso.instaeclipse.utils.ipc.BrokerPayload.LOCATION, extras)) {
            IeSheet.close();
        }
    }

    private static void showThemeOptions(Context context) {
        showSection(context, themePage(context));
    }

    private static void showThemeRoot(Activity activity) {
        IeSheet.open(activity, themePage(activity), () -> showEclipseOptionsDialog(activity));
    }

    private static void applyThemeChange(Context context, String key, boolean rebuildPage) {
        Activity activity = UIHookManager.getCurrentActivity();
        if (SettingsEvents.afterChange(key)) {
            IeSheet.close();
            IeSheet.reopenAfterRecreate(activity, DialogUtils::showThemeRoot, 3000);
        } else if (rebuildPage) {
            replaceTop(context, themePage(context));
        }
    }

    private static IeSheet.Page themePage(Context context) {
        FeatureCatalog.Category cat = FeatureCatalog.category("theme");
        String accent = accentHex(cat.group);
        LinearLayout layout = createSwitchLayout(context);

        ToggleRow themeSwitch = catalogRow(context, "customThemeEnabled", accent, FeatureFlags.customThemeEnabled);
        themeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.customThemeEnabled = isChecked;
            SettingsManager.saveAllFlags();
            applyThemeChange(context, "customThemeEnabled", false);
        });

        LinearLayout colorsCard = IeRows.card(context, 5);
        colorsCard.addView(themeSwitch);
        FeatureCatalog.Item customize = FeatureCatalog.item("themeCustomize");
        LinearLayout customizeRow = detailRow(context, customize.icon, accent, I18n.t(context, customize.label), v -> {
            if (ModuleActivityLauncher.launch(context,
                    ps.reso.instaeclipse.utils.ipc.BrokerPayload.THEME, null)) {
                IeSheet.close();
            }
        });
        customizeRow.setTag(customize.key);
        colorsCard.addView(customizeRow);
        boolean night = ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.isNightNow();
        FeatureCatalog.Item dark = FeatureCatalog.item("themePresetId");
        LinearLayout darkRow = detailRow(context, dark.icon, accent,
                I18n.t(context, dark.label) + ": " + darkModeLabel(context, FeatureFlags.themePresetId),
                v -> pickDarkModeTheme(context));
        darkRow.setTag(dark.key);
        darkRow.setVisibility(night ? View.VISIBLE : View.GONE);
        colorsCard.addView(darkRow);
        FeatureCatalog.Item light = FeatureCatalog.item("themeLightPresetId");
        LinearLayout lightRow = detailRow(context, light.icon, accent,
                I18n.t(context, light.label) + ": " + lightModeLabel(context, FeatureFlags.themeLightPresetId),
                v -> pickLightModeTheme(context));
        lightRow.setTag(light.key);
        lightRow.setVisibility(night ? View.GONE : View.VISIBLE);
        colorsCard.addView(lightRow);
        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.theme_section_colors)));
        layout.addView(colorsCard);

        layout.addView(IeRows.sectionHeader(context, I18n.t(context, R.string.theme_section_fonts)));
        layout.addView(buildFontsCard(context, accent));

        return new IeSheet.Page(I18n.t(context, cat.title), layout);
    }

    private static String lightModeLabel(Context context, int value) {
        if (value == 0) return I18n.t(context, R.string.theme_light_same);
        if (value == -1) return I18n.t(context, R.string.theme_light_stock);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(context, value);
    }

    private static String darkModeLabel(Context context, int value) {
        if (value == 0) return I18n.t(context, R.string.theme_section_custom);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(context, value);
    }

    /** Dark-mode theme: one of the dark presets, or the custom palette when that is in use. */
    private static void pickDarkModeTheme(Context context) {
        int[] presets = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.darkModeChoices();
        final int[] values;
        if (FeatureFlags.themePresetId == 0) {
            values = new int[presets.length + 1];
            System.arraycopy(presets, 0, values, 1, presets.length);
        } else {
            values = presets;
        }
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = darkModeLabel(context, values[i]);
            if (values[i] == FeatureFlags.themePresetId) sel = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.theme_dark_mode))
                .choices(labels, sel, which -> {
                    FeatureFlags.themePresetId = values[which];
                    SettingsManager.saveAllFlags();
                    applyThemeChange(context, "themePresetId", true);
                })
                .show();
    }

    /** Light-mode behaviour of the custom theme (#251): same theme, stock Instagram, or a preset. */
    private static void pickLightModeTheme(Context context) {
        final int[] values = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.lightModeChoices();
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = lightModeLabel(context, values[i]);
            if (values[i] == FeatureFlags.themeLightPresetId) sel = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.theme_light_mode))
                .choices(labels, sel, which -> {
                    FeatureFlags.themeLightPresetId = values[which];
                    SettingsManager.saveAllFlags();
                    applyThemeChange(context, "themeLightPresetId", true);
                })
                .show();
    }

    private static LinearLayout buildFontsCard(Context context, String accent) {
        LinearLayout fontCard = IeRows.card(context, 5);

        ToggleRow fontSwitch = catalogRow(context, "customFontEnabled", accent, FeatureFlags.customFontEnabled);
        fontSwitch.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.customFontEnabled = checked;
            SettingsManager.saveAllFlags();
            SettingsEvents.afterChange("customFontEnabled");
        });
        fontCard.addView(fontSwitch);
        FeatureCatalog.Item fontPick = FeatureCatalog.item("customFontPick");
        LinearLayout fontPickRow = detailRow(context, fontPick.icon, accent, I18n.t(context, fontPick.label), v -> {
            Activity act = activityOf(context);
            if (act != null) ps.reso.instaeclipse.mods.ui.CustomFontHook.launchPicker(act);
            else ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker),
                    Toast.LENGTH_SHORT).show();
        });
        fontPickRow.setTag(fontPick.key);
        fontCard.addView(fontPickRow);

        ToggleRow emojiSwitch = catalogRow(context, "customEmojiEnabled", accent, FeatureFlags.customEmojiEnabled);
        emojiSwitch.setOnCheckedChangeListener((b, checked) -> {
            FeatureFlags.customEmojiEnabled = checked;
            SettingsManager.saveAllFlags();
            SettingsEvents.afterChange("customEmojiEnabled");
        });
        fontCard.addView(emojiSwitch);
        FeatureCatalog.Item emojiPick = FeatureCatalog.item("customEmojiPick");
        LinearLayout emojiPickRow = detailRow(context, emojiPick.icon, accent, I18n.t(context, emojiPick.label), v -> {
            Activity act = activityOf(context);
            if (act != null) ps.reso.instaeclipse.mods.ui.CustomFontHook.launchEmojiPicker(act);
            else ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_dialog_downloader_cannot_open_picker),
                    Toast.LENGTH_SHORT).show();
        });
        emojiPickRow.setTag(emojiPick.key);
        fontCard.addView(emojiPickRow);
        return fontCard;
    }

    private static String downloadQualityLabel(Context context, int ceiling) {
        return ceiling <= 0 ? I18n.t(context, R.string.ie_download_best)
                : I18n.t(context, R.string.ie_download_ceiling, ceiling);
    }

    /** Standard (the original downloader) or Best, with its resolution cap. */
    private static String downloadModeLabel(Context context) {
        return FeatureFlags.downloaderUseOriginal ? I18n.t(context, R.string.ie_downloader_original)
                : downloadQualityLabel(context, FeatureFlags.downloaderQualityMaxShortSide);
    }

    private static String downloadQualityRowLabel(Context context, FeatureCatalog.Item item) {
        return I18n.t(context, item.label) + "  ·  " + downloadModeLabel(context);
    }

    private static void showDownloadQuality(Context context, FeatureCatalog.Item item, TextView label, Runnable onChange) {
        int[] ceilings = {-1, 0, 2160, 1440, 1080, 720, 480};
        String[] labels = new String[ceilings.length];
        int selected = 0;
        for (int i = 0; i < ceilings.length; i++) {
            labels[i] = ceilings[i] < 0 ? I18n.t(context, R.string.ie_downloader_original)
                    : downloadQualityLabel(context, ceilings[i]);
            if (!FeatureFlags.downloaderUseOriginal && ceilings[i] == FeatureFlags.downloaderQualityMaxShortSide) selected = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, R.string.ie_download_quality))
                .message(I18n.t(context, R.string.ie_download_quality_hint))
                .choices(labels, selected, which -> {
                    FeatureFlags.downloaderUseOriginal = ceilings[which] < 0;
                    if (ceilings[which] >= 0) FeatureFlags.downloaderQualityMaxShortSide = ceilings[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange(java.util.Arrays.asList("downloaderUseOriginal", "downloaderQualityMaxShortSide"));
                    if (label != null) label.setText(downloadQualityRowLabel(context, item));
                    onChange.run();
                })
                .show();
    }

    private static View downloadFolderBlock(Context context, FeatureCatalog.Item item) {
        IePalette p = IePalette.of(context);
        String folderRaw = FeatureFlags.downloaderCustomPath.isEmpty()
                ? android.os.Environment.getExternalStorageDirectory().getAbsolutePath()
                        + "/Download/InstaEclipse"
                : FeatureFlags.downloaderCustomPath;
        String folderDisplay = folderRaw.replaceFirst("^.*/0/", "");
        TextView folderHint = new TextView(context);
        folderHint.setText(I18n.t(context, R.string.ig_downloader_folder_hint));
        folderHint.setTextSize(13);
        folderHint.setTextColor(p.sub);
        folderHint.setPadding(dp(context, 13), 0, dp(context, 11), dp(context, 7));
        LinearLayout folderBlock = new LinearLayout(context);
        folderBlock.setOrientation(LinearLayout.VERTICAL);
        folderBlock.addView(IeRows.valueRow(context, I18n.t(context, item.label), folderDisplay));
        folderBlock.addView(folderHint);
        folderBlock.setTag(item.key);
        return folderBlock;
    }

    private static View storyCacheDurationRow(Context context, FeatureCatalog.Item item, java.util.List<Runnable> refresh) {
        IePalette p = IePalette.of(context);
        LinearLayout duration = (LinearLayout) IeRows.valueRow(context, I18n.t(context, item.label),
                I18n.t(context, R.string.ig_story_cache_hours, FeatureFlags.storyCacheHours));
        duration.setTag(item.key);
        TextView hours = (TextView) duration.getChildAt(1);
        hours.setTextColor(IePalette.readable(p.accent, p.card, IePalette.TEXT));
        duration.setMinimumHeight(dp(context, 48));
        duration.setFocusable(true);
        duration.setOnClickListener(v -> showStoryCacheDurationPicker(context, FeatureFlags.storyCacheHours, value -> {
            if (!FeatureFlags.cacheStories) return;
            FeatureFlags.storyCacheHours = value;
            SettingsManager.saveAllFlags();
            SettingsEvents.afterChange("storyCacheHours");
            hours.setText(I18n.t(context, R.string.ig_story_cache_hours, value));
        }));
        refresh.add(() -> {
            boolean on = anyOn(item.requires);
            duration.setEnabled(on);
            duration.setAlpha(on ? 1f : .45f);
        });
        return duration;
    }

    /** Viewer for the story cache — a list per author; tap to view; plus a clear action. */
    private static void showCachedStories(Context context) {
        IeSheet sheet = IeSheet.showing(context);
        IeSheet.Page from = sheet != null ? sheet.top() : null;
        MenuWorker.load(ps.reso.instaeclipse.utils.media.StoryCache::entries, entries -> {
            IeSheet.Page page = cachedStoriesPage(context, entries);
            if (sheet == null) IeSheet.open(context, page, null);
            else if (sheet.isShowing() && sheet.top() == from) sheet.push(page);
        });
    }

    private static IeSheet.Page cachedStoriesPage(Context context,
                                                  java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry> entries) {
        LinearLayout layout = createSwitchLayout(context);
        IeSheet.Page[] self = new IeSheet.Page[1];
        if (entries == null || entries.isEmpty()) {
            layout.addView(IeRows.helpText(context, I18n.t(context, R.string.ig_story_cache_empty_current)));
            return new IeSheet.Page(I18n.t(context, R.string.ig_story_cache_view), layout);
        }
        // Level 1: one "folder" row per username; tap opens that user's cached stories.
        java.util.LinkedHashMap<String, java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry>> byUser =
                new java.util.LinkedHashMap<>();
        for (ps.reso.instaeclipse.utils.media.StoryCache.Entry e : entries) {
            String author = e.author == null ? "" : e.author;
            byUser.computeIfAbsent(author, k -> new java.util.ArrayList<>()).add(e);
        }
        IePalette p = IePalette.of(context);
        LinearLayout foldersCard = IeRows.card(context, 5);
        for (java.util.Map.Entry<String, java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry>> u : byUser.entrySet()) {
            final String author = u.getKey().isEmpty() ? I18n.t(context, R.string.ig_story_cache_unknown_author) : u.getKey();
            final java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry> list = u.getValue();
            LinearLayout row = (LinearLayout) IeRows.actionRow(context, R.drawable.ic_folder,
                    author, "#FF9F0A", v -> showUserStories(context, author, list));
            row.addView(trailingLabel(context, java.text.NumberFormat.getIntegerInstance().format(list.size()), p.sub));
            foldersCard.addView(row);
        }
        layout.addView(foldersCard);
        layout.addView(IeRows.actionRow(context, R.drawable.ic_delete, I18n.t(context, R.string.ig_story_cache_clear),
                "#FF453A", v -> {
                    // Confirm before permanently deleting every cached story, then refresh the view.
                    try {
                        IeDialog dlg = new IeDialog(context)
                                .title(I18n.t(context, R.string.ig_story_cache_clear))
                                .message(I18n.t(context, R.string.ig_story_cache_clear_confirm));
                        dlg.secondary(context.getString(android.R.string.cancel), null);
                        dlg.primary(I18n.t(context, R.string.ig_story_cache_clear), () -> MenuWorker.load(() -> {
                            ps.reso.instaeclipse.utils.media.StoryCache.clearAll();
                            return ps.reso.instaeclipse.utils.media.StoryCache.entries();
                        }, left -> {
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_cache_cleared), Toast.LENGTH_SHORT).show();
                            IeSheet sheet = IeSheet.showing(context);
                            if (sheet != null && sheet.top() == self[0]) sheet.replaceTop(cachedStoriesPage(context, left));
                        }));
                        dlg.show();
                    } catch (Throwable ignored) {}
                }));
        self[0] = new IeSheet.Page(I18n.t(context, R.string.ig_story_cache_view), layout);
        return self[0];
    }

    /** Level 2: the cached stories for one username. */
    private static void showUserStories(Context context, String author,
                                        java.util.List<ps.reso.instaeclipse.utils.media.StoryCache.Entry> list) {
        LinearLayout layout = createSwitchLayout(context);
        LinearLayout cardView = IeRows.card(context, 5);
        IePalette p = IePalette.of(context);
        for (ps.reso.instaeclipse.utils.media.StoryCache.Entry e : list) {
            LinearLayout row = (LinearLayout) IeRows.actionRow(context, e.video ? R.drawable.ic_movie : R.drawable.ic_gallery,
                    shortDate(context, e.at), "#FF9F0A", v -> openCachedStory(context, e));
            if (e.isExpired()) row.addView(trailingLabel(context, I18n.t(context, R.string.ig_story_cache_expired), p.danger));
            cardView.addView(row);
        }
        layout.addView(cardView);
        showSectionDialog(context, author, layout);
    }

    /** Opens one cached story full-screen: ImageView for photos, VideoView for videos. */
    private static void openCachedStory(Context context, ps.reso.instaeclipse.utils.media.StoryCache.Entry e) {
        try {
            IePalette p = IePalette.of(context);
            android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            android.widget.FrameLayout root = new android.widget.FrameLayout(context);
            root.setBackgroundColor(Color.BLACK);
            root.setOnClickListener(v -> dialog.dismiss());
            if (e.video) {
                android.widget.VideoView vv = new android.widget.VideoView(context);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-1, -1);
                lp.gravity = Gravity.CENTER;
                vv.setLayoutParams(lp);
                vv.setVideoPath(e.path);
                vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
                root.addView(vv);
            } else {
                ps.reso.instaeclipse.mods.feed.ZoomableImageView iv = new ps.reso.instaeclipse.mods.feed.ZoomableImageView(context);
                iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
                iv.setOnDismissRequest(dialog::dismiss);
                root.addView(iv);
                android.widget.ProgressBar spinner = new android.widget.ProgressBar(context);
                spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
                android.widget.FrameLayout.LayoutParams spinnerLp = new android.widget.FrameLayout.LayoutParams(dp(context, 48), dp(context, 48));
                spinnerLp.gravity = Gravity.CENTER;
                root.addView(spinner, spinnerLp);
                android.util.DisplayMetrics screen = context.getResources().getDisplayMetrics();
                int maxWidth = Math.max(1, screen.widthPixels);
                int maxHeight = Math.max(1, screen.heightPixels);
                MenuWorker.load(() -> decodeForScreen(e.path, maxWidth, maxHeight), bitmap -> {
                    if (!dialog.isShowing()) return;
                    spinner.setVisibility(View.GONE);
                    if (bitmap == null) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_cache_open_failed), Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        return;
                    }
                    iv.setImageBitmap(bitmap);
                });
            }
            float d = context.getResources().getDisplayMetrics().density;
            int pad = Math.round(12 * d);

            View bar = new View(context);
            bar.setBackgroundColor(Color.WHITE);
            android.widget.FrameLayout.LayoutParams barLp = new android.widget.FrameLayout.LayoutParams(-1, Math.round(3 * d));
            barLp.leftMargin = pad; barLp.rightMargin = pad; barLp.topMargin = Math.round(8 * d);
            bar.setLayoutParams(barLp);
            root.addView(bar);

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.FrameLayout.LayoutParams hLp = new android.widget.FrameLayout.LayoutParams(-1, -2);
            hLp.topMargin = Math.round(16 * d); hLp.leftMargin = pad; hLp.rightMargin = pad;
            header.setLayoutParams(hLp);

            TextView name = new TextView(context);
            String who = (e.author == null || e.author.isEmpty()) ? I18n.t(context, R.string.ig_story_cache_unknown_author) : e.author;
            name.setText(who + "   " + shortDate(context, e.at));
            name.setTextColor(Color.WHITE);
            name.setTextSize(14);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(0, -2, 1f);
            name.setLayoutParams(nLp);
            header.addView(name);

            if (e.isExpired()) {
                TextView badge = new TextView(context);
                badge.setText(I18n.t(context, R.string.ig_story_cache_expired));
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(11);
                badge.setTypeface(null, android.graphics.Typeface.BOLD);
                badge.setPadding(Math.round(10 * d), Math.round(4 * d), Math.round(10 * d), Math.round(4 * d));
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(p.danger);
                bg.setCornerRadius(Math.round(10 * d));
                badge.setBackground(bg);
                header.addView(badge);
            }
            root.addView(header);

            // Save-to-gallery button (bottom-right): copies the cached file to the gallery.
            TextView save = new TextView(context);
            save.setText(I18n.t(context, R.string.ig_story_cache_save));
            save.setTextColor(Color.WHITE);
            save.setTextSize(14);
            save.setTypeface(null, android.graphics.Typeface.BOLD);
            save.setPadding(Math.round(18 * d), Math.round(10 * d), Math.round(18 * d), Math.round(10 * d));
            android.graphics.drawable.GradientDrawable sbg = new android.graphics.drawable.GradientDrawable();
            sbg.setColor(IePalette.withAlpha(p.accent, 0xCC));
            sbg.setCornerRadius(Math.round(22 * d));
            save.setBackground(sbg);
            android.widget.FrameLayout.LayoutParams sLp = new android.widget.FrameLayout.LayoutParams(-2, -2);
            sLp.gravity = Gravity.BOTTOM | Gravity.END;
            sLp.rightMargin = pad; sLp.bottomMargin = Math.round(28 * d);
            save.setLayoutParams(sLp);
            save.setOnClickListener(v -> saveCachedStoryToGallery(context, e));
            root.addView(save);

            dialog.setContentView(root);
            dialog.show();
        } catch (Throwable t) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_cache_open_failed), Toast.LENGTH_SHORT).show();
        }
    }

    static android.graphics.Bitmap decodeForScreen(String path, int maxWidth, int maxHeight) {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight);
        return android.graphics.BitmapFactory.decodeFile(path, options);
    }

    static int sampleSize(int width, int height, int maxWidth, int maxHeight) {
        int sample = 1;
        while (width / (sample * 2) >= maxWidth || height / (sample * 2) >= maxHeight) sample *= 2;
        return sample;
    }

    /** Copies a cached story's local file into the gallery/download folder (off the UI thread). */
    private static void saveCachedStoryToGallery(Context context, ps.reso.instaeclipse.utils.media.StoryCache.Entry e) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, I18n.t(context, R.string.ig_story_cache_saving), Toast.LENGTH_SHORT).show();
        ps.reso.instaeclipse.mods.media.FeedVideoDownloadHook.saveLocalFileToGallery(
                context, e.path,
                (e.author == null || e.author.isEmpty()) ? "story" : e.author, e.id, e.video,
                I18n.t(context, R.string.ig_story_cache_saved), I18n.t(context, R.string.ig_story_cache_save_fail));
    }

    private static void backupSettings(Context context) {
        try {
            String json = ps.reso.instaeclipse.utils.backup.SettingsBackupManager.toJson();
            Bundle extras = new Bundle();
            extras.putString("json_content", json);
            extras.putString("file_name", "instaeclipse_settings.json");
            openModuleScreen(context, ps.reso.instaeclipse.utils.ipc.BrokerPayload.EXPORT, extras);
        } catch (Exception e) {
            showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_backup_failed, e.getMessage()));
        }
    }

    private static void restoreSettings(Context context) {
        Bundle extras = new Bundle();
        extras.putString("target_package", context.getPackageName());
        extras.putString("broadcast_action", "ps.reso.instaeclipse.ACTION_RESTORE_SETTINGS");
        openModuleScreen(context, ps.reso.instaeclipse.utils.ipc.BrokerPayload.IMPORT, extras);
    }

    private static void backupData(Context context) {
        Activity a = UIHookManager.getCurrentActivity();
        if (a != null && !a.isFinishing()) ps.reso.instaeclipse.mods.media.DataBackup.export(a);
        else showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
    }

    private static void restoreData(Context context) {
        Activity a = UIHookManager.getCurrentActivity();
        if (a != null && !a.isFinishing()) ps.reso.instaeclipse.mods.media.DataBackup.pickAndRestore(a);
        else showSimpleDialog(context, I18n.t(context, R.string.ig_dialog_error), I18n.t(context, R.string.ig_dialog_instagram_not_ready));
    }

    private static void showAboutDialog(Context context) {
        IePalette p = IePalette.of(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(context, 8), dp(context, 12), dp(context, 8), dp(context, 8));

        // App icon on a soft accent halo (pops in when the page opens).
        android.widget.FrameLayout halo = new android.widget.FrameLayout(context);
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{IePalette.withAlpha(p.accent, 0x55), 0x33BF5AF2});
        glow.setShape(GradientDrawable.OVAL);
        halo.setBackground(glow);
        ImageView icon = new ImageView(context);
        Drawable launcher = ModuleResources.drawable(context, R.mipmap.ic_launcher_round);
        if (launcher != null) icon.setImageDrawable(launcher);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(dp(context, 76), dp(context, 76));
        ilp.gravity = Gravity.CENTER;
        halo.addView(icon, ilp);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(context, 104), dp(context, 104));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        layout.addView(halo, hlp);

        TextView title = new TextView(context);
        title.setText(I18n.t(context, R.string.ig_dialog_title));
        title.setTextColor(p.text);
        title.setTextSize(24f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(context, 12), 0, 0);
        layout.addView(title);

        TextView tagline = new TextView(context);
        tagline.setText(I18n.t(context, R.string.about_tagline));
        tagline.setTextColor(p.sub);
        tagline.setTextSize(14f);
        tagline.setGravity(Gravity.CENTER);
        tagline.setPadding(0, dp(context, 2), 0, dp(context, 10));
        layout.addView(tagline);

        // Version chips + feature health (same chips as the menu header).
        View chips = buildStatusChips(context);
        layout.addView(chips);

        LinearLayout links = IeRows.card(context, 5);
        links.addView(IeRows.actionRow(context, R.drawable.ic_github_logo, I18n.t(context, R.string.about_report_issue), "#8E8E93",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.GITHUB + "/issues")));
        links.addView(IeRows.actionRow(context, R.drawable.ic_github_logo, I18n.t(context, R.string.about_source), "#8E8E93",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.GITHUB)));
        links.addView(IeRows.actionRow(context, R.drawable.ic_telegram_logo, I18n.t(context, R.string.about_channel), "#29B6F6",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.TELEGRAM)));
        links.addView(IeRows.actionRow(context, R.drawable.ic_heart,
                I18n.t(context, R.string.about_based_on, ps.reso.instaeclipse.utils.core.ExportBrand.UPSTREAM_OWNER), "#BF5AF2",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.UPSTREAM)));
        links.addView(IeRows.actionRow(context, R.drawable.ic_github_logo, I18n.t(context, R.string.about_contributors), "#BF5AF2",
                v -> openLink(context, ps.reso.instaeclipse.utils.core.ExportBrand.UPSTREAM + "/graphs/contributors")));
        layout.addView(links);

        TextView footer = new TextView(context);
        footer.setText(I18n.t(context, R.string.about_footer));
        footer.setTextColor(p.sub);
        footer.setTextSize(12f);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(context, 14), 0, 0);
        layout.addView(footer);

        showSectionDialog(context, I18n.t(context, R.string.ig_dialog_section_about), layout);

        // Entrance: icon pops in, the rest rises in just after it.
        halo.setScaleX(0.6f);
        halo.setScaleY(0.6f);
        halo.setAlpha(0f);
        halo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        icon.setRotation(-25f);
        icon.animate().rotation(0f).setDuration(520)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        int i = 0;
        float d = context.getResources().getDisplayMetrics().density;
        for (View v : new View[]{title, tagline, chips, links, footer}) {
            v.setAlpha(0f);
            v.setTranslationY(12 * d);
            v.animate().alpha(1f).translationY(0f).setStartDelay(120 + 60L * i++).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
    }

    private static void openLink(Context context, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private static void showRestartSection(Context context) {
        FeatureCatalog.Category cat = FeatureCatalog.category("restart");
        String accent = accentHex(cat.group);
        IePalette p = IePalette.of(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 13), dp(context, 20), dp(context, 13));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView message = new TextView(context);
        message.setText(I18n.t(context, R.string.ig_dialog_restart_message_v2));
        message.setTextColor(p.text);
        message.setTextSize(18f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, dp(context, 10));

        layout.addView(message);
        FeatureCatalog.Item now = FeatureCatalog.item("restartNow");
        FeatureCatalog.Item clear = FeatureCatalog.item("restartClearCache");
        View restartRow = detailRow(context, now.icon, accent, I18n.t(context, now.label), v -> restart(context, false));
        restartRow.setTag(now.key);
        View clearRow = detailRow(context, clear.icon, accent, I18n.t(context, clear.label), v -> {
            setRowEnabled(restartRow, false);
            v.setEnabled(false);
            android.widget.ProgressBar busy = new android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
            busy.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(p.accent));
            ((LinearLayout) v).addView(busy, new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)));
            restart(context, true);
        });
        clearRow.setTag(clear.key);
        layout.addView(restartRow);
        layout.addView(clearRow);

        showSectionDialog(context, I18n.t(context, cat.title), layout);
    }

    private static void showRescan(Context context) {
        FeatureCatalog.Item item = FeatureCatalog.item("rescan");
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 20), dp(context, 13), dp(context, 20), dp(context, 13));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView message = new TextView(context);
        message.setText(I18n.t(context, R.string.cat_rescan_confirm));
        message.setTextColor(IePalette.of(context).text);
        message.setTextSize(16f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, dp(context, 10));

        layout.addView(message);
        layout.addView(detailRow(context, item.icon, accentHex(FeatureCatalog.Group.TOOLS),
                I18n.t(context, R.string.ie_rescan_restart), v -> {
                    ps.reso.instaeclipse.utils.core.DexKitCache.clearCache();
                    restart(context, false);
                }));

        showSectionDialog(context, I18n.t(context, item.label), layout);
    }

    private static final int[] AUTO_CLEAR_SIZES_MB = {50, 100, 200, 500};

    private static String autoClearSizeLabel(Context context) {
        return I18n.t(context, R.string.cat_auto_clear_size, FeatureFlags.autoClearCacheSizeMb);
    }

    private static void showAutoClearSizeDialog(Context context, TextView label) {
        String[] rows = new String[AUTO_CLEAR_SIZES_MB.length];
        int checked = -1;
        for (int i = 0; i < rows.length; i++) {
            rows[i] = AUTO_CLEAR_SIZES_MB[i] + " MB";
            if (AUTO_CLEAR_SIZES_MB[i] == FeatureFlags.autoClearCacheSizeMb) checked = i;
        }
        new IeDialog(context)
                .title(I18n.t(context, FeatureCatalog.item("autoClearCache").label))
                .choices(rows, checked, which -> {
                    FeatureFlags.autoClearCacheSizeMb = AUTO_CLEAR_SIZES_MB[which];
                    SettingsManager.saveAllFlags();
                    SettingsEvents.afterChange("autoClearCacheSizeMb");
                    if (label != null) label.setText(autoClearSizeLabel(context));
                })
                .show();
    }

    // ==== HELPERS ====

    private static IeSheet.Page showSectionDialog(Context context, String title, View content) {
        return showSection(context, new IeSheet.Page(title, content));
    }

    private static IeSheet.Page showSection(Context context, IeSheet.Page page) {
        IeSheet sheet = IeSheet.showing(context);
        if (sheet != null) return sheet.push(page);
        IeSheet.open(context, page, null);
        return page;
    }

    private static void replaceTop(Context context, IeSheet.Page page) {
        IeSheet sheet = IeSheet.showing(context);
        if (sheet != null) sheet.replaceTop(page);
    }

    private static ToggleRow createSwitch(Context context, String label, boolean defaultState) {
        return new ToggleRow(context, label, defaultState);
    }

    private static ToggleRow createSwitch(Context context, int iconRes, String accentHex, String label, boolean defaultState) {
        return new ToggleRow(context, iconRes, accentHex, label, defaultState);
    }

    private static LinearLayout createSwitchLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(context, 5), dp(context, 3), dp(context, 5), dp(context, 3));
        return layout;
    }

    /** Public wrapper so hooks (e.g. the DM thread button) can load a tinted module vector. */
    public static Drawable moduleIcon(Context context, int iconRes, int tintColor) {
        return ModuleResources.icon(context, iconRes, tintColor);
    }

    /** Same, for callers without a Context at hand (resolved against Instagram's Application). */
    public static Drawable moduleIcon(int iconRes, int tintColor) {
        return ModuleResources.icon(ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication(), iconRes, tintColor);
    }

    private static boolean areAllEnabled(ToggleRow[] rows) {
        for (ToggleRow r : rows) {
            if (!r.isChecked()) return false;
        }
        return true;
    }

}
