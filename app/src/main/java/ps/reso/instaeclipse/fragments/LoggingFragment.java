package ps.reso.instaeclipse.fragments;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.fragments.LogEntries.Entry;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.log.CrashReports;
import ps.reso.instaeclipse.utils.log.LogMirrorProvider;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.utils.text.CopyFeedback;

/**
 * Logs tab. Instagram's module log is read from a file in the companion's own storage that the
 * hooked Instagram process keeps appending to (see {@link LogMirrorProvider}), so it works even
 * when Instagram is backgrounded or dead. While Instagram is running we additionally ask it (via
 * broadcast) to re-sync that file; its inline reply is only used as a fallback when the file is
 * missing (e.g. an Instagram process started before this companion version was installed).
 */
public class LoggingFragment extends Fragment implements TabFragment {

    private static final long INSTAGRAM_REPLY_TIMEOUT_MS = 3000;
    private static final int MAX_ENTRIES = 6000;
    private static final long MAX_READ_BYTES = 3L * 1024 * 1024;
    private static final int MAX_EXPORT_CHARS = 250_000;

    private static final int FILTER_ALL = 0;

    private static final int MENU_SHARE_CRASH = 1;
    private static final int MENU_DELETE_CRASH = 2;

    private static final String PREFS = "instaeclipse_logs_ui";
    private static final String PREF_DETAILS = "show_technical_details";
    private static final String PREF_CLEARED_AT = "cleared_at";

    private RecyclerView listView;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyBody;
    private View emptyRefresh;
    private TextView countView;
    private TextView statusView;
    private View progressView;
    private ExtendedFloatingActionButton latestButton;
    private Chip chipAll, chipHooked, chipErrors, chipWarnings;

    private final LogAdapter adapter = new LogAdapter();
    private List<Entry> allEntries = new ArrayList<>();
    private int filterKind = FILTER_ALL;
    private boolean showDetails;
    private boolean loadedOnce;
    private int crashReports;

    private final Map<String, String> liveReplies = new HashMap<>();
    private boolean waitingForInstagram;
    private boolean noTargetInstalled;
    private boolean liveSyncUnavailable;
    private Runnable pendingTimeout;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger loadGeneration = new AtomicInteger(0);

    /** Nonce of the last ACTION_REQUEST_LOGS; replies without it are ignored (IpcSecurity). */
    private String logsNonce;
    private final java.util.List<ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending> logReplies = new java.util.ArrayList<>();

    private final MenuProvider crashMenu = new MenuProvider() {
        @Override
        public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
            if (crashReports == 0) return;
            menu.add(Menu.NONE, MENU_SHARE_CRASH, Menu.NONE, R.string.logs_crash_share);
            menu.add(Menu.NONE, MENU_DELETE_CRASH, Menu.NONE, R.string.logs_crash_delete);
        }

        @Override
        public boolean onMenuItemSelected(@NonNull MenuItem item) {
            if (item.getItemId() == MENU_SHARE_CRASH) {
                shareCrashReport();
                return true;
            }
            if (item.getItemId() == MENU_DELETE_CRASH) {
                deleteCrashReports();
                return true;
            }
            return false;
        }
    };

    private final BroadcastReceiver logReplyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CommonUtils.ACTION_LOGS_REPLY.equals(intent.getAction()) || listView == null) return;
            if (!ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(logsNonce, intent)) return;
            String src = intent.getStringExtra(CommonUtils.EXTRA_LOG_SOURCE);
            if (src == null) src = CommonUtils.IG_PACKAGE_NAME;
            String body = intent.getStringExtra(CommonUtils.EXTRA_LOG_TEXT);
            liveReplies.put(src, body == null ? "" : body);
            cancelInstagramTimeout();
            waitingForInstagram = false;
            reload();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logging, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.logs_list);
        emptyView = view.findViewById(R.id.logs_empty);
        emptyTitle = view.findViewById(R.id.logs_empty_title);
        emptyBody = view.findViewById(R.id.logs_empty_body);
        emptyRefresh = view.findViewById(R.id.logs_empty_refresh);
        countView = view.findViewById(R.id.logs_count);
        statusView = view.findViewById(R.id.logs_status);
        progressView = view.findViewById(R.id.logs_progress);
        latestButton = view.findViewById(R.id.logs_latest);
        chipAll = view.findViewById(R.id.logs_chip_all);
        chipHooked = view.findViewById(R.id.logs_chip_hooked);
        chipErrors = view.findViewById(R.id.logs_chip_errors);
        chipWarnings = view.findViewById(R.id.logs_chip_warnings);
        for (Chip chip : new Chip[]{chipHooked, chipErrors, chipWarnings}) {
            chip.setOnCheckedChangeListener((button, checked) -> chip.setChipIconVisible(!checked));
        }

        adapter.colorInfo = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant);
        adapter.colorError = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError);
        adapter.colorWarn = ContextCompat.getColor(requireContext(), R.color.warning_yellow);
        adapter.colorOk = Color.parseColor("#81C784");
        adapter.colorDetail = (adapter.colorInfo & 0x00FFFFFF) | 0x99000000;

        showDetails = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_DETAILS, false);
        Chip details = view.findViewById(R.id.logs_chip_details);
        details.setChecked(showDetails);
        details.setOnCheckedChangeListener((chip, checked) -> {
            showDetails = checked;
            requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_DETAILS, checked).apply();
            applyFilter(true);
        });

        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        listView.setLayoutManager(lm);
        listView.setAdapter(adapter);
        listView.setItemAnimator(null);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!recyclerView.canScrollVertically(1)) latestButton.hide();
            }
        });
        latestButton.setOnClickListener(v -> {
            if (adapter.getItemCount() > 0) listView.scrollToPosition(adapter.getItemCount() - 1);
            latestButton.hide();
        });

        ChipGroup group = view.findViewById(R.id.logs_filter_group);
        group.setOnCheckedStateChangeListener((g, ids) -> {
            int id = ids.isEmpty() ? R.id.logs_chip_all : ids.get(0);
            if (id == R.id.logs_chip_hooked) filterKind = LogEntries.KIND_OK;
            else if (id == R.id.logs_chip_errors) filterKind = LogEntries.KIND_ERROR;
            else if (id == R.id.logs_chip_warnings) filterKind = LogEntries.KIND_WARN;
            else filterKind = FILTER_ALL;
            applyFilter(true);
        });

        view.findViewById(R.id.logs_refresh).setOnClickListener(v -> loadLogs());
        emptyRefresh.setOnClickListener(v -> loadLogs());
        view.findViewById(R.id.logs_copy).setOnClickListener(v -> copyLogs());
        view.findViewById(R.id.logs_share).setOnClickListener(v -> shareLogs());
        view.findViewById(R.id.logs_clear).setOnClickListener(v -> clearLogs());
        requireActivity().addMenuProvider(crashMenu, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Override
    public void onTabReselected() {
        if (listView != null && adapter.getItemCount() > 0) listView.scrollToPosition(adapter.getItemCount() - 1);
    }

    @Override
    public void onResume() {
        super.onResume();
        loadLogs();
    }

    @Override
    public void onStop() {
        super.onStop();
        cancelInstagramTimeout();
        waitingForInstagram = false;
        cancelLogReplies();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelInstagramTimeout();
        listView = null;
        emptyView = null;
        countView = null;
        statusView = null;
        progressView = null;
        latestButton = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        loadExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- loading

    /** Shows the saved log right away, then asks running Instagram processes to re-sync it. */
    private void loadLogs() {
        Context ctx = getContext();
        if (ctx == null || listView == null) return;
        liveReplies.clear();
        liveSyncUnavailable = false;
        reload();

        List<String> targets = findInstalledTargets(ctx);
        noTargetInstalled = targets.isEmpty();
        if (noTargetInstalled) return;
        cancelLogReplies();
        logsNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
        for (String pkg : targets) {
            Intent request = new Intent(CommonUtils.ACTION_REQUEST_LOGS);
            request.setPackage(pkg);
            request.putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, logsNonce);
            ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending pending =
                    ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(ctx, request, CommonUtils.ACTION_LOGS_REPLY, logReplyReceiver);
            if (pending != null) logReplies.add(pending);
        }
        if (logReplies.isEmpty()) {
            liveSyncUnavailable = true;
            updateStatus();
            return;
        }
        waitingForInstagram = true;
        setProgress(true);
        cancelInstagramTimeout();
        pendingTimeout = () -> {
            pendingTimeout = null;
            waitingForInstagram = false;
            setProgress(false);
            updateStatus();
        };
        mainHandler.postDelayed(pendingTimeout, INSTAGRAM_REPLY_TIMEOUT_MS);
    }

    private void cancelLogReplies() {
        for (ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending reply : logReplies) reply.cancel();
        logReplies.clear();
    }

    private void reload() {
        Context ctx = getContext();
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        int gen = loadGeneration.incrementAndGet();
        Map<String, String> replies = new HashMap<>(liveReplies);
        String companionTag = getString(R.string.logs_source_companion);
        String clearedAt = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_CLEARED_AT, null);
        if (!loadedOnce) setProgress(true);
        try {
            loadExecutor.execute(() -> {
                List<Entry> entries = readAllEntries(app, replies, companionTag, clearedAt);
                int crashes = CrashReports.count(app);
                mainHandler.post(() -> {
                    if (listView == null || gen != loadGeneration.get()) return;
                    boolean first = !loadedOnce;
                    loadedOnce = true;
                    allEntries = entries;
                    if (!waitingForInstagram) setProgress(false);
                    applyFilter(first);
                    updateStatus();
                    setCrashReports(crashes);
                });
            });
        } catch (Throwable ignored) {
            // executor shut down (fragment destroyed)
        }
    }

    private static List<Entry> readAllEntries(Context ctx, Map<String, String> replies, String companionTag, String clearedAt) {
        List<Entry> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();

        File[] files = LogMirrorProvider.mirrorDir(ctx).listFiles((d, n) -> n.endsWith(".log"));
        if (files != null) {
            for (File f : files) {
                String pkg = f.getName().substring(0, f.getName().length() - 4);
                List<String> lines = readTail(f);
                if (lines.isEmpty()) {
                    String reply = replies.get(pkg);
                    if (reply != null) lines = splitLines(reply);
                }
                if (!lines.isEmpty()) seen.put(pkg, true);
                LogEntries.parseInto(out, lines, sourcePrefix(pkg));
            }
        }
        // Fallback: a running Instagram that couldn't open the mirror still answers inline.
        for (Map.Entry<String, String> r : replies.entrySet()) {
            if (seen.containsKey(r.getKey())) continue;
            LogEntries.parseInto(out, splitLines(r.getValue()), sourcePrefix(r.getKey()));
        }
        LogEntries.parseInto(out, splitLines(Logging.getSnapshot()), companionTag + " ");
        out = LogExport.sinceClear(out, clearedAt);

        // Stable merge by timestamp so app + Instagram entries interleave correctly.
        Collections.sort(out, (a, b) -> a.ts.compareTo(b.ts));
        if (out.size() > MAX_ENTRIES) out = new ArrayList<>(out.subList(out.size() - MAX_ENTRIES, out.size()));
        return out;
    }

    private static String sourcePrefix(String pkg) {
        return CommonUtils.IG_PACKAGE_NAME.equals(pkg) ? "" : "[" + pkg + "] ";
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        for (String l : text.split("\n")) if (!l.isEmpty()) lines.add(l);
        return lines;
    }

    private static List<String> readTail(File f) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (!f.isFile() || f.length() == 0) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(f)) {
            long len = f.length();
            boolean skipFirst = false;
            if (len > MAX_READ_BYTES) {
                long skip = len - MAX_READ_BYTES;
                while (skip > 0) {
                    long s = in.skip(skip);
                    if (s <= 0) break;
                    skip -= s;
                }
                skipFirst = true; // likely a partial line
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (skipFirst) {
                    skipFirst = false;
                    continue;
                }
                if (line.isEmpty()) continue;
                lines.addLast(line);
                if (lines.size() > MAX_ENTRIES * 4) lines.pollFirst();
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(lines);
    }

    // ---------------------------------------------------------------- rendering

    private void applyFilter(boolean jump) {
        if (listView == null) return;
        // Technical details are hidden unless asked for; counts follow what can be shown.
        List<Entry> visible;
        if (showDetails) {
            visible = allEntries;
        } else {
            visible = new ArrayList<>();
            for (Entry e : allEntries) if (e.kind != LogEntries.KIND_DETAIL) visible.add(e);
        }
        List<Entry> shown;
        int ok = 0, warn = 0, err = 0;
        for (Entry e : visible) {
            if (e.kind == LogEntries.KIND_OK) ok++;
            else if (e.kind == LogEntries.KIND_WARN) warn++;
            else if (e.kind == LogEntries.KIND_ERROR) err++;
        }
        if (filterKind == FILTER_ALL) {
            shown = visible;
        } else {
            shown = new ArrayList<>();
            for (Entry e : visible) if (e.kind == filterKind) shown.add(e);
        }
        chipAll.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_all), visible.size()));
        chipHooked.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_hooked), ok));
        chipErrors.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_errors), err));
        chipWarnings.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_warnings), warn));

        boolean atBottom = !listView.canScrollVertically(1);
        Entry newestBefore = adapter.items.isEmpty() ? null : adapter.items.get(adapter.items.size() - 1);
        adapter.technical = showDetails;
        adapter.setItems(shown);
        countView.setText(getString(R.string.logs_count_format, shown.size(), visible.size()));

        boolean empty = shown.isEmpty();
        emptyView.setVisibility(empty && loadedOnce ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        if (empty) {
            boolean filtered = !visible.isEmpty();
            emptyTitle.setText(filtered ? R.string.logs_empty_filter_title : R.string.logs_empty_title);
            emptyBody.setText(filtered ? R.string.logs_empty_filter_body : R.string.logs_empty_body);
            emptyRefresh.setVisibility(filtered ? View.GONE : View.VISIBLE);
            latestButton.hide();
        } else if (jump || atBottom) {
            listView.scrollToPosition(shown.size() - 1);
            latestButton.hide();
        } else {
            Entry newest = shown.get(shown.size() - 1);
            if (newestBefore == null || !newest.ts.equals(newestBefore.ts) || !newest.full.equals(newestBefore.full)) {
                latestButton.show();
            }
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        int res;
        if (noTargetInstalled) res = R.string.logs_status_none;
        else if (!liveReplies.isEmpty()) res = R.string.logs_status_live;
        else if (liveSyncUnavailable) res = R.string.logs_status_unsynced;
        else if (!waitingForInstagram) res = R.string.logs_status_saved;
        else {
            statusView.setVisibility(View.GONE);
            return;
        }
        statusView.setText(res);
        statusView.setVisibility(View.VISIBLE);
    }

    private void setProgress(boolean on) {
        if (progressView != null) progressView.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void cancelInstagramTimeout() {
        if (pendingTimeout != null) {
            mainHandler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
    }

    private static List<String> findInstalledTargets(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                out.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return out;
    }

    // ---------------------------------------------------------------- actions

    /**
     * Text of the currently filtered entries (full timestamps, callers and levels), capped to the
     * newest part. "All" also exports the technical details, so a shared log is useful for a bug
     * report even when they are hidden on screen.
     */
    private String exportText() {
        List<String> items = new ArrayList<>();
        for (Entry e : allEntries) if (filterKind == FILTER_ALL || e.kind == filterKind) items.add(e.full);
        return LogExport.tail(items, MAX_EXPORT_CHARS);
    }

    private void copyLogs() {
        Context ctx = getContext();
        if (ctx == null) return;
        String text = exportText();
        if (text.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        CopyFeedback.copy(ctx, "InstaEclipse logs", text, R.string.logs_copied);
    }

    private void shareLogs() {
        Context ctx = getContext();
        if (ctx == null) return;
        String text = exportText();
        if (text.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_share_subject));
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.logs_share_title)));
        } catch (Throwable ignored) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_share_no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogs() {
        Context ctx = getContext();
        if (ctx == null) return;
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.logs_clear_confirm_title)
                .setMessage(R.string.logs_clear_confirm_body)
                .setPositiveButton(R.string.logs_clear, (d, w) -> clearConfirmed())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearConfirmed() {
        Context ctx = getContext();
        if (ctx == null) return;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_CLEARED_AT, LogExport.stamp(System.currentTimeMillis())).apply();
        for (String pkg : findInstalledTargets(ctx)) {
            Intent clear = new Intent(CommonUtils.ACTION_CLEAR_LOGS);
            clear.setPackage(pkg);
            ctx.sendBroadcast(clear);
        }
        Logging.clear();
        liveReplies.clear();
        Context app = ctx.getApplicationContext();
        try {
            loadExecutor.execute(() -> LogExport.truncate(LogMirrorProvider.mirrorDir(app)));
        } catch (Throwable ignored) {}
        reload();
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
    }

    private void setCrashReports(int count) {
        if (count == crashReports) return;
        crashReports = count;
        FragmentActivity activity = getActivity();
        if (activity != null) activity.invalidateOptionsMenu();
    }

    private void shareCrashReport() {
        Context ctx = getContext();
        if (ctx == null) return;
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.logs_crash_share)
                .setMessage(R.string.logs_crash_share_warning)
                .setPositiveButton(R.string.logs_crash_share_summary, (d, w) -> sendCrashReport(false))
                .setNeutralButton(R.string.logs_crash_share_full, (d, w) -> sendCrashReport(true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sendCrashReport(boolean full) {
        Context ctx = getContext();
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        setProgress(true);
        try {
            loadExecutor.execute(() -> {
                Uri built = null;
                try {
                    built = CrashReports.share(app, full);
                } catch (Throwable ignored) {}
                Uri uri = built;
                mainHandler.post(() -> {
                    if (!waitingForInstagram) setProgress(false);
                    Context c = getContext();
                    if (c == null) return;
                    if (uri == null) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, R.string.logs_crash_share_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("application/zip");
                    send.putExtra(Intent.EXTRA_STREAM, uri);
                    send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_crash_share_subject));
                    send.setClipData(ClipData.newRawUri("", uri));
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(send, getString(R.string.logs_crash_share)));
                    } catch (Throwable ignored) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, R.string.logs_share_no_app, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        } catch (Throwable ignored) {}
    }

    private void deleteCrashReports() {
        Context ctx = getContext();
        if (ctx == null || crashReports == 0) return;
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(getResources().getQuantityString(R.plurals.logs_crash_delete_confirm, crashReports, crashReports))
                .setPositiveButton(R.string.logs_crash_delete_action, (d, w) -> deleteCrashConfirmed())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteCrashConfirmed() {
        Context ctx = getContext();
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        try {
            loadExecutor.execute(() -> {
                CrashReports.delete(app);
                int left = CrashReports.count(app);
                mainHandler.post(() -> {
                    Context c = getContext();
                    if (c == null) return;
                    setCrashReports(left);
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, R.string.logs_crash_deleted, Toast.LENGTH_SHORT).show();
                });
            });
        } catch (Throwable ignored) {}
    }

    // ---------------------------------------------------------------- adapter

    private final class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {
        List<Entry> items = new ArrayList<>();
        int colorInfo, colorOk, colorWarn, colorError, colorDetail;
        boolean technical;

        @SuppressWarnings("NotifyDataSetChanged")
        void setItems(List<Entry> list) {
            items = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, parent.getResources().getDisplayMetrics()));
            tv.setPadding(0, pad, 0, pad);
            tv.setLineSpacing(0, 1.1f);
            ViewCompat.replaceAccessibilityAction(tv, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                    parent.getContext().getString(R.string.logs_copy_entry), null);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Entry e = items.get(position);
            // Technical: the raw entry in monospace. Simple: readable text, one line per entry.
            holder.text.setTypeface(technical ? Typeface.MONOSPACE : Typeface.DEFAULT);
            holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, technical ? 12 : 13);
            holder.text.setText(technical ? e.display : e.friendly);
            int c;
            switch (e.kind) {
                case LogEntries.KIND_ERROR: c = colorError; break;
                case LogEntries.KIND_WARN: c = colorWarn; break;
                case LogEntries.KIND_OK: c = colorOk; break;
                case LogEntries.KIND_DETAIL: c = colorDetail; break;
                default: c = colorInfo;
            }
            holder.text.setTextColor(c);
            holder.text.setOnLongClickListener(v -> {
                CopyFeedback.copy(v.getContext(), "InstaEclipse log entry", e.full, R.string.logs_entry_copied);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView text;

            VH(TextView tv) {
                super(tv);
                text = tv;
            }
        }
    }
}
