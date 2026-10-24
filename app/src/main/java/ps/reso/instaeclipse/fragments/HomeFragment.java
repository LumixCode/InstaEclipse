package ps.reso.instaeclipse.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionSettings;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.utils.core.InstagramCompat;
import ps.reso.instaeclipse.utils.core.InstagramRestarter;
import ps.reso.instaeclipse.utils.core.PermissionAsk;
import ps.reso.instaeclipse.utils.core.RestartDecision;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.log.LogMirrorProvider;

public class HomeFragment extends Fragment implements TabFragment {

    private static final String DEFAULT_PINNED = "isGhostSeen,isGhostTyping,isGhostStory,isAdBlockEnabled";
    private static final int MAX_PINNED = 8;

    private static final int COLOR_OK = 0xFF30D158;
    private static final int COLOR_WARN = 0xFFFF9F0A;
    private static final int COLOR_ERROR = 0xFFFF453A;
    private static final int COLOR_INSTAGRAM = 0xFFFF375F;

    /** Distraction keys FeaturesFragment locks while Extreme Mode is on. */
    private static final List<String> EXTREME_LOCKED = Arrays.asList(
            "disableStories", "disableFeed", "disableReels", "disableReelsExceptDM",
            "disableExplore", "disableComments", "dailyReelLimitEnabled", "dailyReelLimitAllowDM",
            "nightReelBlockEnabled", "nightReelBlockAllowDM");

    /** A pinnable toggle on the Home dashboard. */
    private static final class Shortcut {
        final String key;
        final int labelRes;
        final int iconRes;
        final int accent;

        Shortcut(String key, int labelRes, int iconRes, int accent) {
            this.key = key;
            this.labelRes = labelRes;
            this.iconRes = iconRes;
            this.accent = accent;
        }
    }

    // Same icons / accent colours FeaturesFragment uses for these rows.
    private static final List<Shortcut> CATALOG = Arrays.asList(
            new Shortcut("isGhostSeen", R.string.ig_dialog_ghost_hide_dm_seen, R.drawable.ic_eye_off, 0xFF5E5CE6),
            new Shortcut("isGhostTyping", R.string.ig_dialog_ghost_hide_typing, R.drawable.ic_chat, 0xFF5E5CE6),
            new Shortcut("isGhostStory", R.string.ig_dialog_ghost_hide_story_views, R.drawable.ic_story_ring, 0xFF5E5CE6),
            new Shortcut("isGhostViewOnce", R.string.ig_dialog_ghost_hide_view_once, R.drawable.ic_eye_off, 0xFF5E5CE6),
            new Shortcut("isGhostScreenshot", R.string.ig_dialog_ghost_bypass_screenshot, R.drawable.ic_camera, 0xFF5E5CE6),
            new Shortcut("enablePostDownload", R.string.ig_dialog_downloader_posts, R.drawable.ic_download, 0xFFFF9F0A),
            new Shortcut("enableReelDownload", R.string.ig_dialog_downloader_reels, R.drawable.ic_download, 0xFFFF9F0A),
            new Shortcut("enableStoryDownload", R.string.ig_dialog_downloader_stories, R.drawable.ic_download, 0xFFFF9F0A),
            new Shortcut("isAdBlockEnabled", R.string.ig_dialog_ad_block_ads, R.drawable.ic_shield, 0xFFFF453A),
            new Shortcut("customThemeEnabled", R.string.theme_enable, R.drawable.ic_palette, 0xFFFF375F),
            new Shortcut("disableReels", R.string.ig_dialog_distraction_disable_reels, R.drawable.ic_movie, 0xFF30D158),
            new Shortcut("disableStories", R.string.ig_dialog_distraction_disable_stories, R.drawable.ic_story_ring, 0xFF30D158),
            new Shortcut("disableExplore", R.string.ig_dialog_distraction_disable_explore, R.drawable.ic_search, 0xFF30D158),
            new Shortcut("maxUploadQuality", R.string.ig_dialog_max_upload_quality, R.drawable.ic_upload, 0xFFFF9F0A)
    );

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

    /**
     * Liveness ping: the module answers ACTION_REQUEST_PREFS with ACTION_SEND_PREFS from inside a
     * running Instagram process. A reply proves the module is loaded; no reply just means
     * Instagram isn't running, so we fall back to the plain install state. We only use the reply
     * as a signal — FeaturesFragment remains the one that syncs its contents into the cache.
     */
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
        downloadButton.setOnClickListener(v -> {
            String url = ps.reso.instaeclipse.utils.core.ExportBrand.TESTED_INSTAGRAM_APK;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
        view.findViewById(R.id.home_shortcuts_edit).setOnClickListener(v -> showEditShortcutsDialog());

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        checkInstagramStatus();
        // Prefs may have changed in the Features tab or from inside Instagram.
        updateFeatureCount();
        renderShortcuts();
        pingModule();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (pingReceiverRegistered) {
            if (pingReply != null) { pingReply.cancel(); pingReply = null; }
            pingReceiverRegistered = false;
        }
    }

    @Override
    public void onTabReselected() {
        if (getView() instanceof NestedScrollView) ((NestedScrollView) getView()).smoothScrollTo(0, 0);
    }

    // =========================================================
    // STATUS HERO
    // =========================================================

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
        setupSteps.setVisibility(confirmedOnce ? View.GONE : View.VISIBLE);
        setupTip.setVisibility(confirmedOnce ? View.VISIBLE : View.GONE);
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
        IntentFilter filter = new IntentFilter("ps.reso.instaeclipse.ACTION_SEND_PREFS");
        try {
            // Same registration as FeaturesFragment: the reply comes from the Instagram process.
            if (pingReply != null) pingReply.cancel();
            pingReceiverRegistered = true;
            pingNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
            pingReply = ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(requireContext(),
                    new Intent("ps.reso.instaeclipse.ACTION_REQUEST_PREFS").setPackage(activePackage)
                            .putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, pingNonce),
                    "ps.reso.instaeclipse.ACTION_SEND_PREFS", pingReceiver);
        } catch (Throwable ignored) { }
    }

    // =========================================================
    // QUICK ACTIONS
    // =========================================================

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

    // =========================================================
    // QUICK SHORTCUTS
    // =========================================================

    private List<String> getPinnedKeys() {
        String raw = uiPrefs.getString(CompanionUiPrefs.HOME_PINNED_SHORTCUTS, null);
        if (raw == null) raw = DEFAULT_PINNED;
        List<String> out = new ArrayList<>();
        for (String k : raw.split(",")) {
            String key = k.trim();
            if (!key.isEmpty() && findShortcut(key) != null && !out.contains(key)) out.add(key);
            if (out.size() >= MAX_PINNED) break;
        }
        return out;
    }

    @Nullable
    private static Shortcut findShortcut(String key) {
        for (Shortcut s : CATALOG) if (s.key.equals(key)) return s;
        return null;
    }

    private void renderShortcuts() {
        if (shortcutsContainer == null) return;
        shortcutsContainer.removeAllViews();
        List<String> pinned = getPinnedKeys();
        boolean extreme = localCache.getBoolean("isExtremeMode", false);
        for (String key : pinned) {
            Shortcut s = findShortcut(key);
            if (s == null) continue;
            // A lock that is on turns off only in Instagram's Lock menu, which asks for the passcode.
            int lockedHint = extreme && EXTREME_LOCKED.contains(s.key) ? R.string.home_shortcuts_locked_extreme
                    : SettingsManager.isPasscodeLock(s.key) && localCache.getBoolean(s.key, false)
                    ? R.string.ig_lock_turn_off_in_ig : 0;
            shortcutsContainer.addView(buildShortcutRow(s, lockedHint));
        }
        boolean empty = shortcutsContainer.getChildCount() == 0;
        shortcutsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        shortcutsHint.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** {@code lockedHint}: the subtitle of a row that can't be toggled here, or 0. */
    private View buildShortcutRow(Shortcut s, int lockedHint) {
        Context ctx = requireContext();
        boolean locked = lockedHint != 0;

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        TypedValue ripple = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        FrameLayout iconBox = new FrameLayout(ctx);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(s.iconRes);
        icon.setContentDescription(null);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER);
        iconBox.addView(icon, iconLp);
        applyTonalSquare(iconBox, icon, s.accent);
        row.addView(iconBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(s.labelRes);
        title.setTextAppearance(resolveStyle(com.google.android.material.R.attr.textAppearanceBodyLarge));
        title.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurface));
        texts.addView(title);
        if (locked) {
            TextView sub = new TextView(ctx);
            sub.setText(lockedHint);
            sub.setTextAppearance(resolveStyle(com.google.android.material.R.attr.textAppearanceBodySmall));
            sub.setTextColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorOnSurfaceVariant));
            texts.addView(sub);
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginStart(dp(14));
        textLp.setMarginEnd(dp(12));
        row.addView(texts, textLp);

        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setChecked(localCache.getBoolean(s.key, false));
        sw.setContentDescription(getString(s.labelRes));
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (locked) {
            sw.setEnabled(false);
            row.setEnabled(false);
            row.setAlpha(0.4f);
        } else {
            sw.setOnCheckedChangeListener((btn, checked) -> {
                setFeature(s.key, checked);
                updateFeatureCount();
            });
            row.setOnClickListener(v -> sw.toggle());
        }
        return row;
    }

    private void setFeature(String key, boolean value) {
        if (!value && "disableReels".equals(key) && localCache.getBoolean("disableReelsExceptDM", false)) {
            LinkedHashMap<String, Object> values = new LinkedHashMap<>();
            values.put(key, false);
            values.put("disableReelsExceptDM", false);
            CompanionSettings.putAll(requireContext(), values);
        } else {
            CompanionSettings.put(requireContext(), key, value);
        }
        PermissionAsk.Trigger trigger = PermissionAsk.forChanges(java.util.Collections.singletonMap(key, value));
        if (trigger != PermissionAsk.Trigger.NONE && getActivity() instanceof ps.reso.instaeclipse.MainActivity) {
            ((ps.reso.instaeclipse.MainActivity) getActivity()).requestDownloadPermissionsIfNeeded(trigger);
        }
    }

    private void showEditShortcutsDialog() {
        final List<String> current = getPinnedKeys();
        final String[] labels = new String[CATALOG.size()];
        final boolean[] checked = new boolean[CATALOG.size()];
        for (int i = 0; i < CATALOG.size(); i++) {
            labels[i] = getString(CATALOG.get(i).labelRes);
            checked[i] = current.contains(CATALOG.get(i).key);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_shortcuts_dialog_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (isChecked) {
                        int count = 0;
                        for (boolean b : checked) if (b) count++;
                        if (count > MAX_PINNED) {
                            checked[which] = false;
                            ((AlertDialog) dialog).getListView().setItemChecked(which, false);
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(),
                                    getString(R.string.home_shortcuts_limit, MAX_PINNED), Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // Keep the existing order for shortcuts that stay pinned, append new ones.
                    List<String> result = new ArrayList<>();
                    for (String key : current) {
                        int idx = indexOfShortcut(key);
                        if (idx >= 0 && checked[idx]) result.add(key);
                    }
                    for (int i = 0; i < CATALOG.size(); i++) {
                        String key = CATALOG.get(i).key;
                        if (checked[i] && !result.contains(key) && result.size() < MAX_PINNED) result.add(key);
                    }
                    uiPrefs.edit().putString(CompanionUiPrefs.HOME_PINNED_SHORTCUTS, String.join(",", result)).apply();
                    renderShortcuts();
                })
                .setNegativeButton(R.string.ig_dialog_cancel, null)
                .show();
    }

    private static int indexOfShortcut(String key) {
        for (int i = 0; i < CATALOG.size(); i++) if (CATALOG.get(i).key.equals(key)) return i;
        return -1;
    }

    // =========================================================
    // STYLE HELPERS
    // =========================================================

    /** 40dp rounded-square tonal container, same recipe as FeaturesFragment's row icons. */
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
