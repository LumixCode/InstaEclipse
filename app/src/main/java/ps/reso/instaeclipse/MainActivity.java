package ps.reso.instaeclipse;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import ps.reso.instaeclipse.fragments.FeaturesFragment;
import ps.reso.instaeclipse.fragments.HelpFragment;
import ps.reso.instaeclipse.fragments.HomeFragment;
import ps.reso.instaeclipse.fragments.LoggingFragment;
import ps.reso.instaeclipse.fragments.TabFragment;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.utils.version.VersionCheckUtility;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "selectedTab";
    private static final int[] TAB_IDS = {R.id.nav_home, R.id.nav_features, R.id.nav_logs, R.id.nav_help};

    private BottomNavigationView bottomNavigation;
    private int selectedTab = R.id.nav_home;

    private final OnBackPressedCallback backToHome = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            bottomNavigation.setSelectedItemId(R.id.nav_home);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // NOTE: intentionally NOT applying DynamicColors — 0.7 ships a fixed branded dark palette
        // (see Theme.InstaEclipse); adopting the wallpaper palette here would override the brand.
        super.onCreate(savedInstanceState);
        Logging.init(this, "instaeclipse_companion.log");
        ps.reso.instaeclipse.mods.media.FrameSessionCache.cleanupOrphans(getCacheDir());
        if (savedInstanceState == null) VersionCheckUtility.checkForUpdates(this); // not again on rotation
        // Stale companion-cache keys are dropped once per process in InstaEclipseApp.onCreate.

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.top_app_bar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
        }

        // Prevent status bar color changes on scroll
        AppBarLayout appBarLayout = findViewById(R.id.app_bar_layout);
        if (appBarLayout != null) {
            appBarLayout.setExpanded(true, false);
            appBarLayout.setLiftOnScroll(false);
        }

        bottomNavigation = findViewById(R.id.bottom_navigation);
        FrameLayout fragmentContainer = findViewById(R.id.fragment_container);

        // On targetSdk 35+, edge-to-edge is enforced. The BottomNavigationView absorbs the
        // system gesture inset via fitsSystemWindows, making its actual height larger than the
        // fixed 82dp we had in XML. Sync the fragment container's bottom padding to match the
        // nav bar's real height after each layout pass.
        bottomNavigation.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int navHeight = v.getHeight();
            if (fragmentContainer.getPaddingBottom() != navHeight) {
                fragmentContainer.setPadding(0, 0, 0, navHeight);
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

    public void showHelpAbout() {
        bottomNavigation.setSelectedItemId(R.id.nav_help);
        Fragment help = getSupportFragmentManager().findFragmentByTag(tagOf(R.id.nav_help));
        if (help instanceof HelpFragment) ((HelpFragment) help).scrollToAbout();
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
