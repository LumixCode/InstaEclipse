package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.MediaIds;

public final class ProfileDownloadAll {

    private static final String TAG = "(IE|ProfileDL) ";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 600, IDLE_MS = 6000, SPACING_MS = 700;
    private static boolean busy;

    private ProfileDownloadAll() {}

    private static final int[] LATEST = {12, 30, 50, 100};
    private static final int PINNED_SLACK = 3;

    public static void start(Activity a, String username) {
        if (a == null || busy) return;
        if (findGrid(a.getWindow().getDecorView()) == null) {
            toast(a, I18n.t(a, R.string.ig_profile_dl_none));
            return;
        }
        CharSequence[] labels = new CharSequence[LATEST.length + 2];
        for (int i = 0; i < LATEST.length; i++) labels[i] = I18n.t(a, R.string.ig_profile_dl_latest, LATEST[i]);
        labels[LATEST.length] = I18n.t(a, R.string.ig_profile_dl_all);
        labels[LATEST.length + 1] = I18n.t(a, R.string.ig_profile_dl_custom);
        new IeDialog(a).title(I18n.t(a, R.string.ig_profile_dl_how_many)).choices(labels, -1, i -> {
            if (i < LATEST.length) collect(a, username, LATEST[i]);
            else if (i == LATEST.length) collect(a, username, 0);
            else askNumber(a, username);
        }).show();
    }

    private static void askNumber(Activity a, String username) {
        IeDialog dlg = new IeDialog(a).title(I18n.t(a, R.string.ig_profile_dl_how_many));
        android.widget.EditText in = dlg.input(I18n.t(a, R.string.ig_profile_dl_number_hint));
        in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        dlg.secondary(a.getString(android.R.string.cancel), () -> { });
        dlg.primary(a.getString(android.R.string.ok), () -> {
            int n;
            try { n = Integer.parseInt(in.getText().toString().trim()); } catch (Throwable t) { n = 0; }
            if (n > 0) collect(a, username, n);
        });
        dlg.show();
        in.requestFocus();
        in.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(in, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private static void collect(Activity a, String username, int limit) {
        if (busy) return;
        View grid = findGrid(a.getWindow().getDecorView());
        if (grid == null) {
            toast(a, I18n.t(a, R.string.ig_profile_dl_none));
            return;
        }
        busy = true;
        List<Post> found = new ArrayList<>();
        Set<Object> known = Collections.newSetFromMap(new IdentityHashMap<>());
        IeDialog dlg = new IeDialog(a).title(I18n.t(a, R.string.ig_profile_download_all))
                .message(I18n.t(a, R.string.ig_profile_dl_collecting, 0));
        final boolean[] stopped = {false};
        dlg.secondary(I18n.t(a, R.string.ig_comment_search_stop), () -> stopped[0] = true);
        dlg.cancelable(false);
        dlg.show();
        final long[] lastChange = {System.currentTimeMillis()};
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || a.isDestroyed() || !grid.isAttachedToWindow()) { busy = false; dlg.dismiss(); return; }
                List<Object> fresh = new ArrayList<>();
                gather(adapterOf(grid), known, fresh);
                int before = found.size();
                for (Object media : fresh) {
                    try {
                        if (username.equalsIgnoreCase(owner(media))) found.add(new Post(media));
                    } catch (Throwable ignored) { }
                }
                if (found.size() != before) lastChange[0] = System.currentTimeMillis();
                dlg.message(I18n.t(a, R.string.ig_profile_dl_collecting, found.size()));
                boolean end = System.currentTimeMillis() - lastChange[0] > IDLE_MS
                        || (limit > 0 && found.size() >= limit + PINNED_SLACK);
                if (stopped[0] || end) {
                    dlg.dismiss();
                    newestFirst(found);
                    if (limit > 0 && found.size() > limit) found.subList(limit, found.size()).clear();
                    ModuleLog.line(TAG + "Found " + found.size() + " posts on " + username + "'s profile" + (stopped[0] ? " (stopped early)" : ""));
                    confirm(a, found);
                    return;
                }
                swipeUp(a, grid);
                MAIN.postDelayed(this, TICK_MS);
            }
        };
        MAIN.post(tick);
    }

    private static final class Post {
        final Object media;
        final long id;

        Post(Object media) throws Exception {
            this.media = media;
            this.id = SelectedMediaAdapter.value(media, "id") instanceof String raw ? MediaIds.pkLong(raw) : 0;
        }
    }

    private static String owner(Object media) throws Exception {
        Object user = SelectedMediaAdapter.value(media, "user");
        return user != null && SelectedMediaAdapter.value(user, "username") instanceof String name ? name : null;
    }

    private static void newestFirst(List<Post> posts) {
        posts.sort((x, y) -> Long.compare(y.id, x.id));
    }

    private static void confirm(Activity a, List<Post> posts) {
        if (posts.isEmpty()) {
            busy = false;
            toast(a, I18n.t(a, R.string.ig_profile_dl_none));
            return;
        }
        IeDialog dlg = new IeDialog(a).title(I18n.t(a, R.string.ig_profile_download_all))
                .message(I18n.t(a, R.string.ig_profile_dl_found, posts.size()));
        dlg.secondary(a.getString(android.R.string.cancel), () -> busy = false);
        View go = dlg.primary(I18n.t(a, R.string.ig_dl_title), () -> download(a, posts));
        dlg.onDismiss(() -> { if (!downloading) busy = false; });
        dlg.show();
        go.setEnabled(false);
        go.setAlpha(0.4f);
        MAIN.postDelayed(() -> { go.setEnabled(true); go.setAlpha(1f); }, 800);
    }

    private static boolean downloading;

    private static void download(Activity a, List<Post> posts) {
        downloading = true;
        toast(a, I18n.t(a, R.string.ig_profile_dl_started, posts.size()));
        BatchTally batch = new BatchTally(posts.size());
        for (int i = 0; i < posts.size(); i++) {
            final Post post = posts.get(i);
            final boolean last = i == posts.size() - 1;
            MAIN.postDelayed(() -> {
                try {
                    SelectedMediaDownloads.Captured c = SelectedMediaDownloads.capture(a, post.media, "post");
                    if (c.children.isEmpty()) start(a, c, batch);
                    else {
                        batch.add(c.children.size() - 1);
                        for (SelectedMediaDownloads.Captured child : c.children) start(a, child, batch);
                    }
                } catch (Throwable t) {
                    ModuleLog.warn(TAG + "Skipped one post that could not be downloaded: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                    SelectedMediaDownloads.reportFailed(a, batch);
                }
                if (last) { downloading = false; busy = false; }
            }, i * SPACING_MS);
        }
    }

    private static void start(Activity a, SelectedMediaDownloads.Captured c, BatchTally batch) {
        SelectedMediaDownloads.start(a, c, c.batchKind() == DownloadChoices.Choice.VIDEO, batch);
    }

    private static void swipeUp(Activity a, View grid) {
        View root = a.getWindow().getDecorView();
        android.graphics.Rect shown = new android.graphics.Rect();
        if (!grid.getGlobalVisibleRect(shown)) return;
        float x = shown.centerX();
        float y0 = shown.bottom - 60, y1 = Math.max(shown.top + 60, y0 - root.getHeight() * 0.45f);
        if (y0 - y1 < 200) y1 = Math.max(60, y0 - root.getHeight() * 0.45f);
        long t = android.os.SystemClock.uptimeMillis();
        int steps = 12;
        dispatch(root, t, t, android.view.MotionEvent.ACTION_DOWN, x, y0);
        for (int i = 1; i <= steps; i++) {
            float y = y0 + (y1 - y0) * i / steps;
            boolean last = i == steps;
            MAIN.postDelayed(() -> {
                long now = android.os.SystemClock.uptimeMillis();
                dispatch(root, t, now, android.view.MotionEvent.ACTION_MOVE, x, y);
                if (last) dispatch(root, t, now + 10, android.view.MotionEvent.ACTION_UP, x, y);
            }, i * 16L);
        }
    }

    private static void dispatch(View root, long down, long at, int action, float x, float y) {
        android.view.MotionEvent e = android.view.MotionEvent.obtain(down, at, action, x, y, 0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { root.dispatchTouchEvent(e); } finally { e.recycle(); }
    }

    private static View findGrid(View v) {
        View best = null;
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(v);
        while (!st.isEmpty()) {
            View x = st.pop();
            if (!x.isShown()) continue;
            if (x.getId() == android.R.id.list && isRecyclerView(x.getClass())
                    && (best == null || x.getHeight() > best.getHeight())) best = x;
            if (x instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return best;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }

    private static Object adapterOf(View rv) {
        try { return rv.getClass().getMethod("getAdapter").invoke(rv); } catch (Throwable t) { return null; }
    }

    private static void gather(Object root, Set<Object> known, List<Object> out) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        queue.add(new Object[]{root, 0});
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 60_000) {
            Object[] e = queue.poll();
            Object o = e[0];
            int depth = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            String cn = o.getClass().getName();
            if (cn.equals(MEDIA)) {
                if (known.add(o)) out.add(o);
                continue;
            }
            if (depth >= 7) continue;
            if (o instanceof java.util.Map<?, ?> m) {
                try { for (Object v : m.values()) queue.add(new Object[]{v, depth + 1}); } catch (Throwable ignored) { }
                continue;
            }
            if (o instanceof java.util.Collection<?> col) {
                try { for (Object v : col) queue.add(new Object[]{v, depth + 1}); } catch (Throwable ignored) { }
                continue;
            }
            if (o instanceof Object[] arr) {
                for (Object x : arr) queue.add(new Object[]{x, depth + 1});
                continue;
            }
            if (!(cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("androidx.recyclerview.")
                    || cn.startsWith("kotlinx.coroutines.flow."))) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        queue.add(new Object[]{f.get(o), depth + 1});
                    } catch (Throwable ignored) { }
                }
            }
        }
    }

    private static void toast(Activity a, String s) {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
