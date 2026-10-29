package ps.reso.instaeclipse.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.MainActivity;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.location.LocationPickerActivity;
import ps.reso.instaeclipse.mods.location.LocationPresets;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionSettings;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.utils.core.ExtremeRules;
import ps.reso.instaeclipse.utils.core.InstagramCompat;
import ps.reso.instaeclipse.utils.core.InstagramRestarter;
import ps.reso.instaeclipse.utils.core.PermissionAsk;
import ps.reso.instaeclipse.utils.core.RestartDecision;
import ps.reso.instaeclipse.utils.core.SettingsGuard;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.core.SettingsSchema;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.feature.RestartPromptRule;
import ps.reso.instaeclipse.utils.log.LogMirrorProvider;
import ps.reso.instaeclipse.utils.ui.CompanionRestartHint;
import ps.reso.instaeclipse.utils.ui.Links;
import ps.reso.instaeclipse.utils.ui.NoticeQueue;

public class HomeFragment extends Fragment implements TabFragment {

    private static final int COLOR_OK = 0xFF30D158;
    private static final int COLOR_WARN = 0xFFFF9F0A;
    private static final int COLOR_ERROR = 0xFFFF453A;
    private static final int COLOR_INSTAGRAM = 0xFFFF375F;

    private static final long SYNC_DELAY_MS = 200;

    static final class ShortcutPicker {
        static final int MAX_PINNED = 8;
        private static final String DEFAULT_PINNED = "isGhostSeen,isGhostTyping,isGhostStory,isAdBlockEnabled";
        private static final Map<String, FeatureCatalog.Category> CATEGORY = new HashMap<>();
        private static final List<String> OFFERED = offeredKeys();

        private ShortcutPicker() {}

        private static List<String> offeredKeys() {
            List<String> out = new ArrayList<>();
            for (FeatureCatalog.Category c : FeatureCatalog.CATEGORIES) {
                for (FeatureCatalog.Item i : c.items()) CATEGORY.putIfAbsent(i.key, c);
                if (c.hidden) continue;
                for (FeatureCatalog.Item i : c.items()) {
                    if (i.kind == FeatureCatalog.Kind.SWITCH && i.counts && i.side != FeatureCatalog.Side.IG_ONLY
                            && !out.contains(i.key)) {
                        out.add(i.key);
                    }
                }
            }
            return Collections.unmodifiableList(out);
        }

        static List<String> offered() {
            return OFFERED;
        }

        static List<String> pinned(@Nullable String raw) {
            if (raw == null) raw = DEFAULT_PINNED;
            List<String> out = new ArrayList<>();
            for (String k : raw.split(",")) {
                String trimmed = k.trim();
                String key = SettingsSchema.LEGACY.getOrDefault(trimmed, trimmed);
                if (!key.isEmpty() && OFFERED.contains(key) && !out.contains(key)) out.add(key);
                if (out.size() >= MAX_PINNED) break;
            }
            return out;
        }

        static List<String> chosen(List<String> current, List<String> offered, boolean[] checked) {
            List<String> result = new ArrayList<>();
            for (String key : current) {
                int idx = offered.indexOf(key);
                if (idx >= 0 && checked[idx]) result.add(key);
            }
            for (int i = 0; i < offered.size(); i++) {
                String key = offered.get(i);
                if (checked[i] && !result.contains(key) && result.size() < MAX_PINNED) result.add(key);
            }
            return result;
        }

        @Nullable
        static FeatureCatalog.Item find(String key) {
            return OFFERED.contains(key) ? FeatureCatalog.item(key) : null;
        }

        static int accent(String key) {
            FeatureCatalog.Category c = CATEGORY.get(key);
            return c != null ? c.group.accent : FeatureCatalog.Group.TOOLS.accent;
        }
    }

    private static final class ShortcutRow {
        final String key;
        final MaterialSwitch toggle;
        CompoundButton.OnCheckedChangeListener listener;

        ShortcutRow(String key, MaterialSwitch toggle) {
            this.key = key;
            this.toggle = toggle;
        }
    }

    private SharedPreferences localCache;
    private SharedPreferences uiPrefs;

    private MaterialButton launchInstagramButton;
    private MaterialButton restartInstagramButton;
    private MaterialCardView instagramStatusCard;
    private TextView instagramStatusText;
    private TextView versionText;
    private TextView instagramVariantText;
    private MaterialButton instagramMultiButton;
    private ImageView instagramLogo;
    private ImageButton instagramInfoIcon;
    private View logoContainer;
    private View statusPill;
    private ImageView statusDot;
    private TextView statusPillText;
    private TextView statusHint;
    private View setupSteps;
    private View setupTip;
    private MaterialButton setupOpenButton;
    private TextView featuresCountText;
    private LinearLayout shortcutsContainer;
    private TextView shortcutsEmpty;
    private TextView shortcutsHint;

    private String activePackage;
    private String activeVersionName;
    private List<String> installedPackages;
    private boolean moduleConfirmed;
    private boolean restarting;
    private boolean pingReceiverRegistered;
    private boolean listeningToCache;
    private List<String> renderedPins;
    private Map<String, String> renderedLocks;
    private final List<ShortcutRow> shortcutRows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable syncShortcuts = this::syncShortcutStates;
    private final SharedPreferences.OnSharedPreferenceChangeListener cacheListener = (prefs, key) -> {
        handler.removeCallbacks(syncShortcuts);
        handler.postDelayed(syncShortcuts, SYNC_DELAY_MS);
    };

    private String pingNonce;
    private ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending pingReply;

    private final BroadcastReceiver pingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ps.reso.instaeclipse.ACTION_SEND_PREFS".equals(intent.getAction())
                    && ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(pingNonce, intent)) {
                moduleConfirmed = true;
                if (isAdded() && getView() != null) updateStatusPill();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);
        localCache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(requireContext());
        uiPrefs = CompanionUiPrefs.get(requireContext());

        launchInstagramButton = view.findViewById(R.id.launch_instagram_button);
        restartInstagramButton = view.findViewById(R.id.restart_instagram_button);
        MaterialButton downloadButton = view.findViewById(R.id.download_instagram_button);

        instagramStatusCard = view.findViewById(R.id.instagram_status_card);
        instagramStatusText = view.findViewById(R.id.instagram_status_text);
        versionText = view.findViewById(R.id.home_version_text);
        instagramVariantText = view.findViewById(R.id.instagram_variant_text);
        instagramMultiButton = view.findViewById(R.id.instagram_multi_button);
        instagramLogo = view.findViewById(R.id.instagram_logo);
        instagramInfoIcon = view.findViewById(R.id.instagram_info_icon);
        logoContainer = view.findViewById(R.id.home_logo_container);
        statusPill = view.findViewById(R.id.home_status_pill);
        statusDot = view.findViewById(R.id.home_status_dot);
        statusPillText = view.findViewById(R.id.home_status_pill_text);
        statusHint = view.findViewById(R.id.home_status_hint);
        setupSteps = view.findViewById(R.id.home_setup_steps);
        setupTip = view.findViewById(R.id.home_setup_tip);
        setupOpenButton = view.findViewById(R.id.home_setup_open_instagram);
        featuresCountText = view.findViewById(R.id.home_features_count);
        shortcutsContainer = view.findViewById(R.id.home_shortcuts_container);
        shortcutsEmpty = view.findViewById(R.id.home_shortcuts_empty);
        shortcutsHint = view.findViewById(R.id.home_shortcuts_hint);
        renderedPins = null;
        shortcutRows.clear();

        int primary = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary);
        applyTonalSquare(view.findViewById(R.id.home_features_icon_container),
                view.findViewById(R.id.home_features_icon), primary);
        applyTonalSquare(view.findViewById(R.id.home_howto_icon_container),
                view.findViewById(R.id.search_icon), primary);

        GradientDrawable variantBg = new GradientDrawable();
        variantBg.setCornerRadius(dp(14));
        variantBg.setColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHighest));
        instagramVariantText.setBackground(variantBg);

        int[] stepNumbers = {R.id.home_setup_step_1_number, R.id.home_setup_step_2_number, R.id.home_setup_step_3_number};
        java.text.NumberFormat numberFormat = java.text.NumberFormat.getInstance();
        for (int i = 0; i < stepNumbers.length; i++) {
            TextView number = view.findViewById(stepNumbers[i]);
            number.setText(numberFormat.format(i + 1));
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor((primary & 0x00FFFFFF) | 0x33000000);
            number.setBackground(circle);
        }
        view.findViewById(R.id.home_setup_got_it).setOnClickListener(v -> {
            uiPrefs.edit().putBoolean(CompanionUiPrefs.MODULE_CONFIRMED_ONCE, true).apply();
            updateSetupCard();
        });
        setupOpenButton.setOnClickListener(v -> {
            if (activePackage != null) launchPackage(activePackage);
        });
        updateSetupCard();

        restartInstagramButton.setOnClickListener(v -> restartInstagram());
        downloadButton.setOnClickListener(v -> Links.open(requireContext(), ExportBrand.TESTED_INSTAGRAM_APK));
        View countRow = (View) featuresCountText.getParent();
        TypedValue countRipple = new TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, countRipple, true);
        countRow.setBackgroundResource(countRipple.resourceId);
        countRow.setOnClickListener(v -> {
            if (getActivity() instanceof ps.reso.instaeclipse.MainActivity) {
                ((ps.reso.instaeclipse.MainActivity) getActivity()).selectTab(R.id.nav_features);
            }
        });
        view.findViewById(R.id.home_shortcuts_edit).setOnClickListener(v -> showEditShortcutsDialog());

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        checkInstagramStatus();
        syncShortcutStates();
        pingModule();
        if (!listeningToCache) {
            localCache.registerOnSharedPreferenceChangeListener(cacheListener);
            listeningToCache = true;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (listeningToCache) {
            localCache.unregisterOnSharedPreferenceChangeListener(cacheListener);
            listeningToCache = false;
        }
        handler.removeCallbacks(syncShortcuts);
        if (pingReceiverRegistered) {
            if (pingReply != null) { pingReply.cancel(); pingReply = null; }
            pingReceiverRegistered = false;
        }
    }

    @Override
    public void onTabReselected() {
        if (getView() instanceof NestedScrollView) ((NestedScrollView) getView()).smoothScrollTo(0, 0);
    }

    private void checkInstagramStatus() {
        PackageManager pm = requireContext().getPackageManager();

        installedPackages = InstagramRestarter.installedPackages(requireContext());

        if (installedPackages.isEmpty()) {
            activePackage = null;
            activeVersionName = null;
            instagramStatusText.setText(getString(R.string.not_installed_instagram));
            versionText.setText(getString(R.string.home_status_no_version));
            versionText.setTextColor(MaterialColors.getColor(versionText, com.google.android.material.R.attr.colorOnSurfaceVariant));
            versionText.setCompoundDrawablesRelative(null, null, null, null);
            instagramLogo.setImageResource(R.drawable.ic_cancel);
            applyTonalSquare(logoContainer, instagramLogo, COLOR_ERROR);
            instagramVariantText.setVisibility(View.GONE);
            instagramMultiButton.setVisibility(View.GONE);
            instagramInfoIcon.setVisibility(View.GONE);
            launchInstagramButton.setEnabled(false);
            restartInstagramButton.setEnabled(false);
            setupOpenButton.setEnabled(false);
            updateStatusPill();
            return;
        }

        activePackage = InstagramRestarter.activePackage(requireContext(), installedPackages);

        instagramLogo.setImageResource(R.drawable.ic_instagram_logo);
        applyTonalSquare(logoContainer, instagramLogo, COLOR_INSTAGRAM);
        instagramVariantText.setVisibility(View.VISIBLE);
        instagramInfoIcon.setVisibility(View.VISIBLE);
        launchInstagramButton.setEnabled(true);
        restartInstagramButton.setEnabled(!restarting);
        setupOpenButton.setEnabled(true);

        if (installedPackages.size() > 1) {
            instagramMultiButton.setVisibility(View.VISIBLE);
            instagramMultiButton.setOnClickListener(v -> showDetectedVersionsDialog(pm));
        } else {
            instagramMultiButton.setVisibility(View.GONE);
        }

        bindPackageActions(pm, activePackage);
        updateStatusPill();
    }

    private void bindPackageActions(PackageManager pm, String pkg) {
        activePackage = pkg;

        String versionName = null;
        try {
            versionName = pm.getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        activeVersionName = versionName;
        instagramStatusText.setText(versionName != null
                ? getString(R.string.home_instagram_version, versionName)
                : getString(R.string.installed_instagram_version));
        if (InstagramCompat.isTested(versionName)) {
            android.graphics.drawable.Drawable check = ContextCompat.getDrawable(requireContext(), R.drawable.ic_check_circle);
            if (check != null) {
                check = check.mutate();
                check.setTint(COLOR_OK);
                check.setBounds(0, 0, dp(16), dp(16));
            }
            versionText.setText(R.string.home_tested_version);
            versionText.setTextColor(MaterialColors.getColor(versionText, com.google.android.material.R.attr.colorOnSurfaceVariant));
            versionText.setCompoundDrawablesRelative(check, null, null, null);
            versionText.setCompoundDrawablePadding(dp(6));
        } else {
            versionText.setText(getString(R.string.home_untested_version, ExportBrand.TESTED_INSTAGRAM));
            versionText.setTextColor(COLOR_WARN);
            versionText.setCompoundDrawablesRelative(null, null, null, null);
        }

        instagramVariantText.setText(variantLabel(pkg));

        instagramInfoIcon.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        launchInstagramButton.setOnClickListener(v -> launchPackage(pkg));
        updateShortcutsHint();
    }

    private void launchPackage(String pkg) {
        if (!isAdded()) return;
        Intent launchIntent = requireContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (launchIntent != null) {
            startActivity(launchIntent);
        } else {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(getActivity(), getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
        }
    }

    private String variantLabel(String pkg) {
        return CommonUtils.IG_PACKAGE_NAME.equals(pkg) ? getString(R.string.home_variant_official) : CommonUtils.getVariantLabel(pkg);
    }

    private void updateStatusPill() {
        boolean installed = installedPackages != null && !installedPackages.isEmpty();
        HomeStatus status = HomeStatus.of(installed, activeVersionName, moduleConfirmed, mirrorLogMtime(activePackage), activePackage);
        int color;
        String label;
        String hint = null;
        switch (status.pill) {
            case NOT_INSTALLED:
                color = COLOR_ERROR;
                label = getString(R.string.home_status_not_installed);
                break;
            case MODULE_ACTIVE:
                color = COLOR_OK;
                label = getString(R.string.home_status_module_active);
                break;
            case WORKED:
                color = COLOR_OK;
                label = getString(R.string.home_status_worked, android.text.format.DateUtils.formatSameDayTime(
                        status.workedAt, System.currentTimeMillis(), java.text.DateFormat.SHORT, java.text.DateFormat.SHORT));
                break;
            case CLONE:
                color = COLOR_WARN;
                label = getString(R.string.home_status_clone);
                break;
            default:
                color = COLOR_WARN;
                label = getString(R.string.home_status_not_confirmed);
                hint = getString(R.string.home_status_open_to_check);
                break;
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor((color & 0x00FFFFFF) | 0x29000000);
        statusPill.setBackground(bg);
        statusDot.setImageTintList(ColorStateList.valueOf(color));
        statusPillText.setTextColor(color);
        statusPillText.setText(label);
        statusHint.setText(hint);
        statusHint.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        if (status.proof && !uiPrefs.getBoolean(CompanionUiPrefs.MODULE_CONFIRMED_ONCE, false)) {
            uiPrefs.edit().putBoolean(CompanionUiPrefs.MODULE_CONFIRMED_ONCE, true).apply();
            updateSetupCard();
        }
    }

    private long mirrorLogMtime(String pkg) {
        if (pkg == null) return 0;
        java.io.File log = new java.io.File(LogMirrorProvider.mirrorDir(requireContext()), pkg + ".log");
        return log.length() > 0 ? log.lastModified() : 0;
    }

    private void updateSetupCard() {
        boolean confirmedOnce = uiPrefs.getBoolean(CompanionUiPrefs.MODULE_CONFIRMED_ONCE, false);
        MainActivity.NOTICES.offer(new NoticeQueue.Notice<>(MainActivity.NOTICE_SETUP, 3,
                screen -> !confirmedOnce && MainActivity.SCREEN_HOME.equals(screen)));
        NoticeQueue.Notice<String> next = MainActivity.NOTICES.next(MainActivity.SCREEN_HOME);
        boolean steps = next != null && MainActivity.NOTICE_SETUP.equals(next.id);
        setupSteps.setVisibility(steps ? View.VISIBLE : View.GONE);
        setupTip.setVisibility(confirmedOnce ? View.VISIBLE : View.GONE);
    }

    public void refreshNotices() {
        if (setupSteps != null && uiPrefs != null && isAdded()) updateSetupCard();
    }

    private void updateFeatureCount() {
        if (featuresCountText == null) return;
        int count = FeatureCatalog.countAll(cacheValues(localCache.getAll()));
        featuresCountText.setText(getResources().getQuantityString(R.plurals.home_features_enabled, count, count));
    }

    private static FeatureCatalog.Values cacheValues(Map<String, ?> all) {
        return new FeatureCatalog.Values() {
            @Override public boolean bool(String key, boolean def) {
                Object v = all.get(key);
                return v instanceof Boolean ? (Boolean) v : def;
            }

            @Override public int integer(String key, int def) {
                Object v = all.get(key);
                return v instanceof Integer ? (Integer) v : def;
            }

            @Override public String string(String key, String def) {
                Object v = all.get(key);
                return v instanceof String ? (String) v : def;
            }
        };
    }

    private void pingModule() {
        if (installedPackages == null || installedPackages.isEmpty() || pingReceiverRegistered) return;
        try {
            if (pingReply != null) pingReply.cancel();
            pingReceiverRegistered = true;
            pingNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
            pingReply = ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(requireContext(),
                    new Intent("ps.reso.instaeclipse.ACTION_REQUEST_PREFS").setPackage(activePackage)
                            .putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, pingNonce),
                    "ps.reso.instaeclipse.ACTION_SEND_PREFS", pingReceiver);
        } catch (Throwable ignored) { }
    }

    private void restartInstagram() {
        restarting = true;
        restartInstagramButton.setEnabled(false);
        restartInstagramButton.setText(R.string.home_restarting);
        InstagramRestarter.restart(requireActivity(), activePackage, outcome -> {
            restarting = false;
            if (!isAdded() || getView() == null) return;
            restartInstagramButton.setText(R.string.home_action_restart);
            restartInstagramButton.setEnabled(activePackage != null);
            if (outcome == RestartDecision.Outcome.RELAUNCH) {
                moduleConfirmed = false;
                updateStatusPill();
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.home_restart_done), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private List<String> getPinnedKeys() {
        return ShortcutPicker.pinned(uiPrefs.getString(CompanionUiPrefs.HOME_PINNED_SHORTCUTS, null));
    }

    private void syncShortcutStates() {
        if (shortcutsContainer == null || !isAdded()) return;
        List<String> pins = getPinnedKeys();
        Map<String, ?> all = localCache.getAll();
        if (!pins.equals(renderedPins) || !lockReasons(pins, all).equals(renderedLocks)) {
            renderShortcuts();
        } else {
            for (ShortcutRow entry : shortcutRows) setQuietly(entry, isOn(entry.key, all));
            updateShortcutsHint();
        }
        updateFeatureCount();
    }

    private void renderShortcuts() {
        if (shortcutsContainer == null) return;
        shortcutsContainer.removeAllViews();
        shortcutRows.clear();
        List<String> pinned = getPinnedKeys();
        Map<String, ?> all = localCache.getAll();
        Map<String, String> locks = lockReasons(pinned, all);
        for (String key : pinned) {
            FeatureCatalog.Item item = ShortcutPicker.find(key);
            if (item == null) continue;
            shortcutsContainer.addView(buildShortcutRow(item, locks.get(key), isOn(key, all)));
        }
        renderedPins = pinned;
        renderedLocks = locks;
        boolean empty = shortcutsContainer.getChildCount() == 0;
        shortcutsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        updateShortcutsHint();
    }

    private void updateShortcutsHint() {
        if (shortcutsHint == null || shortcutsContainer == null) return;
        boolean live = CommonUtils.IG_PACKAGE_NAME.equals(activePackage) && shortcutsContainer.getChildCount() > 0;
        shortcutsHint.setVisibility(live ? View.VISIBLE : View.GONE);
    }

    private Map<String, String> lockReasons(List<String> pins, Map<String, ?> all) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : pins) {
            FeatureCatalog.Item item = FeatureCatalog.item(key);
            if (item != null) out.put(key, lockReason(item, all));
        }
        return out;
    }

    @Nullable
    private String lockReason(FeatureCatalog.Item item, Map<String, ?> all) {
        boolean on = isOn(item.key, all);
        if ("isExtremeMode".equals(item.key) && on) return getString(R.string.ig_extreme_active);
        if (isOn("isExtremeMode", all) && !ExtremeRules.canToggle(item.key, on)) {
            return getString(R.string.home_shortcuts_locked_extreme);
        }
        if (on && SettingsGuard.decideInApp(item.key, false, all) == SettingsGuard.Decision.REFUSE) {
            return getString(R.string.ig_lock_turn_off_in_ig);
        }
        if (item.overriddenBy != null && isOn(item.overriddenBy, all)) {
            return getString(R.string.feat_reason_overridden, labelOf(item.overriddenBy));
        }
        if (item.requires.length > 0 && !anyOn(item.requires, all)) {
            return item.requires.length == 1 ? getString(R.string.cat_requires, labelOf(item.requires[0]))
                    : getString(R.string.home_shortcuts_needs_setting);
        }
        return null;
    }

    private static boolean isOn(String key, Map<String, ?> all) {
        Object value = all.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        return item != null && item.defaultOn;
    }

    private static boolean anyOn(String[] keys, Map<String, ?> all) {
        for (String key : keys) if (isOn(key, all)) return true;
        return false;
    }

    private String labelOf(String key) {
        FeatureCatalog.Item item = FeatureCatalog.item(key);
        return item != null ? getString(item.label) : key;
    }

    private java.util.Set<String> restartWaiting() {
        Object stored;
        try {
            stored = localCache == null ? null : localCache.getString(SettingsManager.RESTART_PENDING, "");
        } catch (ClassCastException e) {
            stored = null;
        }
        return RestartPromptRule.rowKeys(RestartPromptRule.parse(stored));
    }

    private View buildShortcutRow(FeatureCatalog.Item item, @Nullable String lockedHint, boolean on) {
        Context ctx = requireContext();
        boolean locked = lockedHint != null;
        int accent = ShortcutPicker.accent(item.key);

        LinearLayout row = new LinearLayout(ctx);
        row.setTag(item.key);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        FrameLayout iconBox = new FrameLayout(ctx);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(item.icon);
        icon.setContentDescription(null);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        iconBox.addView(icon, iconLp);
        applyTonalSquare(iconBox, icon, accent);
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(item.label);
        title.setTextAppearance(resolveStyle(com.google.android.material.R.attr.textAppearanceBodyLarge));
        title.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(title);
        if (locked) texts.addView(subLine(row, lockedHint));
        if (item.restartOn != FeatureCatalog.Restart.NONE) {
            texts.addView(subLine(row, getString(restartWaiting().contains(item.key) ? R.string.feat_restart_waiting
                    : item.restartOn == FeatureCatalog.Restart.BOTH
                    ? R.string.ig_restart_to_apply_both : R.string.ig_restart_to_apply_hint)));
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(14));
        textLp.setMarginEnd(dp(12));
        row.addView(texts, textLp);

        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setChecked(on);
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(sw.isChecked());
            }

            @Override
            public void onInitializeAccessibilityEvent(@NonNull View host, @NonNull AccessibilityEvent event) {
                super.onInitializeAccessibilityEvent(host, event);
                event.setClassName(Switch.class.getName());
                event.setChecked(sw.isChecked());
            }
        });

        ShortcutRow entry = new ShortcutRow(item.key, sw);
        if (locked) {
            sw.setEnabled(false);
            row.setEnabled(false);
            row.setAlpha(0.4f);
        } else {
            entry.listener = (btn, checked) -> onShortcutToggled(entry, checked);
            sw.setOnCheckedChangeListener(entry.listener);
            row.setOnClickListener(v -> sw.toggle());
        }
        shortcutRows.add(entry);
        return row;
    }

    private TextView subLine(View row, String text) {
        TextView sub = new TextView(row.getContext());
        sub.setText(text);
        sub.setTextAppearance(resolveStyle(com.google.android.material.R.attr.textAppearanceBodySmall));
        sub.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant));
        return sub;
    }

    private static void setQuietly(ShortcutRow entry, boolean checked) {
        entry.toggle.setOnCheckedChangeListener(null);
        entry.toggle.setChecked(checked);
        entry.toggle.setOnCheckedChangeListener(entry.listener);
    }

    private void onShortcutToggled(ShortcutRow entry, boolean checked) {
        if (checked && "isExtremeMode".equals(entry.key)) {
            setQuietly(entry, false);
            confirmExtreme(entry);
            return;
        }
        if (checked && "spoofLocation".equals(entry.key) && !hasSpoofPoint()) {
            setQuietly(entry, false);
            Intent picker = new Intent(requireContext(), LocationPickerActivity.class);
            picker.putExtra(LocationPickerActivity.EXTRA_ENABLE_ON_PICK, true);
            startActivity(picker);
            return;
        }
        Map<String, ?> all = localCache.getAll();
        if (SettingsGuard.decideInApp(entry.key, checked, all) == SettingsGuard.Decision.REFUSE) {
            setQuietly(entry, !checked);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(),
                    getString(SettingsGuard.refusalHint(entry.key, checked, all)), Toast.LENGTH_LONG).show();
            return;
        }
        setFeature(entry.key, checked);
        updateFeatureCount();
    }

    private void confirmExtreme(ShortcutRow entry) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_dialog_distraction_extreme_title)
                .setMessage(R.string.cat_extreme_message)
                .setPositiveButton(R.string.ig_dialog_yes, (dialog, which) -> {
                    setQuietly(entry, true);
                    setFeature(entry.key, true);
                    updateFeatureCount();
                })
                .setNegativeButton(R.string.ig_dialog_cancel, null)
                .show();
    }

    private boolean hasSpoofPoint() {
        try {
            return LocationPresets.valid(Double.parseDouble(localCache.getString("spoofLat", "0")),
                    Double.parseDouble(localCache.getString("spoofLng", "0")));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void setFeature(String key, boolean value) {
        Map<String, ?> all = localCache.getAll();
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        if (!value && "disableReels".equals(key) && isOn("disableReelsExceptDM", all)) {
            values.put("disableReelsExceptDM", false);
        }
        String exception = value && isOn("isExtremeMode", all) ? ExtremeRules.exceptionOf(key) : null;
        if (exception != null && isOn(exception, all)) values.put(exception, false);
        Set<String> restart;
        if (values.size() > 1) {
            restart = CompanionSettings.putAll(requireContext(), values);
        } else {
            restart = CompanionSettings.put(requireContext(), key, value);
        }
        CompanionRestartHint.show(requireActivity(), restart);
        PermissionAsk.Trigger trigger = PermissionAsk.forChanges(java.util.Collections.singletonMap(key, value));
        if (trigger != PermissionAsk.Trigger.NONE && getActivity() instanceof ps.reso.instaeclipse.MainActivity) {
            ((ps.reso.instaeclipse.MainActivity) getActivity()).requestDownloadPermissionsIfNeeded(trigger);
        }
    }

    private void showEditShortcutsDialog() {
        final List<String> current = getPinnedKeys();
        final List<String> offered = ShortcutPicker.offered();
        final String[] labels = new String[offered.size()];
        final boolean[] checked = new boolean[offered.size()];
        for (int i = 0; i < offered.size(); i++) {
            labels[i] = getString(FeatureCatalog.item(offered.get(i)).label);
            checked[i] = current.contains(offered.get(i));
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_shortcuts_dialog_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        int count = 0;
                        for (boolean b : checked) if (b) count++;
                        if (count > ShortcutPicker.MAX_PINNED) {
                            checked[which] = false;
                            ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(),
                                    getString(R.string.home_shortcuts_limit, ShortcutPicker.MAX_PINNED), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> result = ShortcutPicker.chosen(current, offered, checked);
                    uiPrefs.edit().putString(CompanionUiPrefs.HOME_PINNED_SHORTCUTS, String.join(",", result)).apply();
                    renderShortcuts();
                })
                .setNegativeButton(R.string.ig_dialog_cancel, null)
                .show();
    }

    private void applyTonalSquare(View container, ImageView icon, int accent) {
        if (container == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor((accent & 0x00FFFFFF) | 0x33000000);
        bg.setCornerRadius(dp(12));
        container.setBackground(bg);
        if (icon != null) icon.setImageTintList(ColorStateList.valueOf(accent));
    }

    private int resolveStyle(int attr) {
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void showDetectedVersionsDialog(PackageManager pm) {
        String[] labels = new String[installedPackages.size()];
        for (int i = 0; i < installedPackages.size(); i++) {
            String pkg = installedPackages.get(i);
            String version = "?";
            try { version = pm.getPackageInfo(pkg, 0).versionName; }
            catch (PackageManager.NameNotFoundException ignored) { }
            labels[i] = getString(R.string.home_detected_version_row, variantLabel(pkg), version);
        }

        int currentIndex = installedPackages.indexOf(activePackage);
        final int[] selectedIndex = {currentIndex};

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_detected_versions)
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton(R.string.home_use_this, (dialog, which) -> {
                    String chosen = installedPackages.get(selectedIndex[0]);
                    uiPrefs.edit().putString(CompanionUiPrefs.INSTAGRAM_PACKAGE, chosen).apply();
                    if (!chosen.equals(activePackage)) {
                        moduleConfirmed = false;
                        bindPackageActions(pm, chosen);
                        updateStatusPill();
                        if (pingReply != null) { pingReply.cancel(); pingReply = null; }
                        pingReceiverRegistered = false;
                        pingModule();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
