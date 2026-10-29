package ps.reso.instaeclipse;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import ps.reso.instaeclipse.fragments.FeaturesFragment;
import ps.reso.instaeclipse.fragments.HelpFragment;
import ps.reso.instaeclipse.fragments.HomeFragment;
import ps.reso.instaeclipse.fragments.LoggingFragment;
import ps.reso.instaeclipse.fragments.TabFragment;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionUiPrefs;
import ps.reso.instaeclipse.utils.core.PermissionAsk;
import ps.reso.instaeclipse.utils.ui.NoticeQueue;
import ps.reso.instaeclipse.utils.version.VersionCheckUtility;

public class MainActivity extends AppCompatActivity {

    public static final NoticeQueue<String> NOTICES = new NoticeQueue<>();
    public static final String NOTICE_UPDATE = "update";
    public static final String NOTICE_SETUP = "setup";
    public static final String SCREEN_HOME = "home";
    public static final String SCREEN_FEATURES = "features";
    private static final String STATE_TAB = "selectedTab";
    private static final int[] TAB_IDS = {R.id.nav_home, R.id.nav_features, R.id.nav_logs, R.id.nav_help};
    private static final int MAX_CONTENT_WIDTH_DP = 640;
    private static final AtomicInteger PERMISSION_REQUESTS = new AtomicInteger();
    private static volatile Boolean grantedForTest;

    private BottomNavigationView bottomNavigation;
    private ActivityResultLauncher<String> permissionLauncher;
    private int selectedTab = R.id.nav_home;

    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            bottomNavigation.setSelectedItemId(R.id.nav_home);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {});
        NoticeQueue.WhatChanged.from(CompanionUiPrefs.get(this), NoticeQueue.WhatChanged.APP_FROM,
                () -> CommonUtils.companionCache(this).getAll(), () -> false);
        if (savedInstanceState == null) VersionCheckUtility.checkForUpdates(this);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.top_app_bar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
        }
        TextView version = findViewById(R.id.toolbar_version);
        version.setText("v" + BuildConfig.VERSION_NAME);
        findViewById(R.id.toolbar_version_target).setOnClickListener(v -> showHelpAbout());

        AppBarLayout appBarLayout = findViewById(R.id.app_bar_layout);
        if (appBarLayout != null) {
            appBarLayout.setExpanded(true, false);
            appBarLayout.setLiftOnScroll(false);
        }

        bottomNavigation = findViewById(R.id.bottom_navigation);
        FrameLayout fragmentContainer = findViewById(R.id.fragment_container);

        bottomNavigation.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int navHeight = v.getHeight();
            if (fragmentContainer.getPaddingBottom() != navHeight) {
                fragmentContainer.setPadding(0, 0, 0, navHeight);
            }
        });

        int maxContentWidth = Math.round(MAX_CONTENT_WIDTH_DP * getResources().getDisplayMetrics().density);
        findViewById(R.id.main).addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int available = v.getWidth() - v.getPaddingLeft() - v.getPaddingRight();
            int width = available > maxContentWidth ? maxContentWidth : ViewGroup.LayoutParams.MATCH_PARENT;
            ViewGroup.LayoutParams lp = fragmentContainer.getLayoutParams();
            if (lp.width != width) {
                lp.width = width;
                fragmentContainer.setLayoutParams(lp);
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets sides = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(sides.left, v.getPaddingTop(), sides.right, v.getPaddingBottom());
            return insets.inset(sides.left, 0, sides.right, 0);
        });

        bottomNavigation.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });
        bottomNavigation.setOnItemReselectedListener(item -> {
            Fragment tab = getSupportFragmentManager().findFragmentByTag(tagOf(item.getItemId()));
            if (tab instanceof TabFragment && tab.isVisible()) ((TabFragment) tab).onTabReselected();
        });
        getOnBackPressedDispatcher().addCallback(backToHome);

        showTab(savedInstanceState == null ? R.id.nav_home : savedInstanceState.getInt(STATE_TAB, R.id.nav_home));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, selectedTab);
    }

    public void selectTab(int id) {
        bottomNavigation.setSelectedItemId(id);
    }

    public void showHelpAbout() {
        bottomNavigation.setSelectedItemId(R.id.nav_help);
        Fragment help = getSupportFragmentManager().findFragmentByTag(tagOf(R.id.nav_help));
        if (help instanceof HelpFragment) ((HelpFragment) help).scrollToAbout();
    }

    public void requestDownloadPermissionsIfNeeded(PermissionAsk.Trigger trigger) {
        String permission = PermissionAsk.permission(Build.VERSION.SDK_INT);
        boolean granted = permission == null || (grantedForTest != null ? grantedForTest
                : ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED);
        SharedPreferences ui = CompanionUiPrefs.get(this);
        if (isFinishing() || !PermissionAsk.shouldAsk(granted, ui.getBoolean(PermissionAsk.ASKED, false), trigger)) return;
        ui.edit().putBoolean(PermissionAsk.ASKED, true).apply();
        boolean storage = android.Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permission);
        new MaterialAlertDialogBuilder(this)
                .setTitle(storage ? R.string.feat_storage_rationale_title : R.string.feat_notification_rationale_title)
                .setMessage(storage ? R.string.feat_storage_rationale_message : R.string.feat_notification_rationale_message)
                .setPositiveButton(R.string.background_access_allow, (d, w) -> {
                    PERMISSION_REQUESTS.incrementAndGet();
                    permissionLauncher.launch(permission);
                })
                .setNegativeButton(R.string.feat_permission_not_now, null)
                .show();
    }

    public static void noticeOpened(Activity activity, String id) {
        NOTICES.opened(id);
        refreshNotices(activity);
    }

    public static void noticeClosed(Activity activity, String id) {
        NOTICES.closed(id);
        refreshNotices(activity);
    }

    private static void refreshNotices(Activity activity) {
        if (!(activity instanceof MainActivity main) || main.isFinishing() || main.isDestroyed()) return;
        FragmentManager fragments = main.getSupportFragmentManager();
        Fragment home = fragments.findFragmentByTag(tagOf(R.id.nav_home));
        if (home instanceof HomeFragment h) h.refreshNotices();
        Fragment features = fragments.findFragmentByTag(tagOf(R.id.nav_features));
        if (features instanceof FeaturesFragment f) f.refreshNotices();
    }

    public static int permissionRequestsForTest() {
        return PERMISSION_REQUESTS.get();
    }

    public static void overrideGrantedForTest(Boolean granted) {
        grantedForTest = granted;
    }

    private void showTab(int id) {
        String tag = tagOf(id);
        if (tag == null) return;
        selectedTab = id;
        FragmentManager fragments = getSupportFragmentManager();
        FragmentTransaction transaction = fragments.beginTransaction().setReorderingAllowed(true);
        for (int other : TAB_IDS) {
            if (other == id) continue;
            Fragment hidden = fragments.findFragmentByTag(tagOf(other));
            if (hidden != null) transaction.hide(hidden).setMaxLifecycle(hidden, Lifecycle.State.STARTED);
        }
        Fragment shown = fragments.findFragmentByTag(tag);
        if (shown == null) {
            shown = newTab(id);
            transaction.add(R.id.fragment_container, shown, tag);
        } else {
            transaction.show(shown);
        }
        transaction.setMaxLifecycle(shown, Lifecycle.State.RESUMED).commitNow();
        backToHome.setEnabled(id != R.id.nav_home);
    }

    private static String tagOf(int id) {
        if (id == R.id.nav_home) return "home";
        if (id == R.id.nav_features) return "features";
        if (id == R.id.nav_logs) return "logs";
        if (id == R.id.nav_help) return "help";
        return null;
    }

    private static Fragment newTab(int id) {
        if (id == R.id.nav_features) return new FeaturesFragment();
        if (id == R.id.nav_logs) return new LoggingFragment();
        if (id == R.id.nav_help) return new HelpFragment();
        return new HomeFragment();
    }
}
