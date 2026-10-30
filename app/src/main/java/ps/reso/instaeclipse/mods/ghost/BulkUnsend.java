package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.ModuleBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;

public final class BulkUnsend {

    private static final String TAG = "(IE|BulkUnsend) ";
    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String FUTURE = "com.google.common.util.concurrent.ListenableFuture";
    private static final String UNSEND_KEY = "BulkUnsend_unsend_v1";
    private static final String REF_KEY = "BulkUnsend_ref_v1";

    private static volatile ClassLoader loader;
    private static volatile Class<?> messageClass;
    private static volatile Method unsendMethod;
    private static volatile Constructor<?> refCtor;
    private static volatile Method senderFor;
    private static volatile String source = "none";

    private static volatile Object lastCache;

    private static final Set<String> OWN_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Set<String> PENDING_IDS = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final Map<Class<?>, Field[]> HOLDER_FIELDS = new ConcurrentHashMap<>();
    private static final Executor BACKGROUND = r -> new Thread(r, "IE-BulkUnsend").start();
    private static final LazyResolve LAZY = new LazyResolve(BulkUnsend::ready,
            () -> resolve(loader, messageClass));

    private static Selection active;

    private BulkUnsend() {}

    public static boolean ready() {
        return unsendMethod != null && refCtor != null && messageClass != null;
    }

    public static void onLookup(Object cache) {
        if (cache != null) lastCache = cache;
    }

    public static boolean isOwnUnsend(String id) {
        return id != null && (OWN_IDS.contains(id) || PENDING_IDS.contains(id));
    }

    public static String describe() {
        return "ready=" + ready() + " source=" + source;
    }

    public static void install(ClassLoader cl, Class<?> message) {
        loader = cl;
        messageClass = message;
        if (FeatureFlags.bulkUnsend) resolve(cl, message);
    }

    public static void switched() {
        if (loader != null) LAZY.request(FeatureFlags.bulkUnsend, BACKGROUND);
    }

    static final class LazyResolve {
        private final AtomicBoolean running = new AtomicBoolean();
        private final BooleanSupplier resolved;
        private final Runnable resolve;

        LazyResolve(BooleanSupplier resolved, Runnable resolve) {
            this.resolved = resolved;
            this.resolve = resolve;
        }

        boolean request(boolean on, Executor executor) {
            if (!on || resolved.getAsBoolean() || !running.compareAndSet(false, true)) return false;
            try {
                executor.execute(() -> {
                    try {
                        if (!resolved.getAsBoolean()) resolve.run();
                    } finally {
                        running.set(false);
                    }
                });
                return true;
            } catch (RuntimeException e) {
                running.set(false);
                return false;
            }
        }
    }

    private static synchronized void resolve(ClassLoader cl, Class<?> message) {
        if (ready() || cl == null) return;
        if (message == null) { ModuleLog.error(TAG + "Could not turn on bulk unsend: not supported by this Instagram version (no message class)"); return; }
        try {
            UnsentIdentity.senderField(message);
            Class<?> dtk = cl.loadClass(DTK);
            boolean cached = DexKitCache.isCacheValid();
            Method um = cached ? DexKitCache.loadMethod(UNSEND_KEY, cl) : null;
            Constructor<?> rc = cached && isUnsend(um, dtk) ? DexKitCache.loadConstructor(REF_KEY, cl) : null;
            if (isUnsend(um, dtk) && isRef(rc, um, message, dtk)) {
                source = "cache";
            } else {
                source = "search";
                if (ModuleBridge.get() == null) { ModuleLog.error(TAG + "Could not turn on bulk unsend: Instagram's code can't be searched right now"); return; }
                um = findUnsend(cl, dtk);
                if (um == null) { ModuleLog.error(TAG + "Could not turn on bulk unsend: not supported by this Instagram version (unsend method not found)"); return; }
                rc = findRef(cl, um, message, dtk);
                if (rc == null) { ModuleLog.error(TAG + "Could not turn on bulk unsend: not supported by this Instagram version (message reference not found)"); return; }
                DexKitCache.saveMethod(UNSEND_KEY, um);
                DexKitCache.saveConstructor(REF_KEY, rc);
            }
            um.setAccessible(true);
            rc.setAccessible(true);
            senderFor = senderAccessor(um.getDeclaringClass());
            refCtor = rc;
            unsendMethod = um;
            FeatureStatusTracker.setHooked("BulkUnsend");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "unsend=" + um.getDeclaringClass().getName() + "." + um.getName()
                    + " ref=" + rc.getDeclaringClass().getName() + " from " + source
                    + " sender=" + (senderFor != null ? "session" : "cache walk"));
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not turn on bulk unsend", t);
        }
    }

    private static boolean isUnsend(Method m, Class<?> dtk) {
        if (m == null || Modifier.isStatic(m.getModifiers()) || !FUTURE.equals(m.getReturnType().getName())) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 2 && p[1] == dtk;
    }

    private static boolean isRef(Constructor<?> c, Method unsend, Class<?> message, Class<?> dtk) {
        if (c == null || !unsend.getParameterTypes()[0].isAssignableFrom(c.getDeclaringClass())) return false;
        Class<?>[] p = c.getParameterTypes();
        return p.length == 2 && p[0] == message && p[1] == dtk;
    }

    private static Method findUnsend(ClassLoader cl, Class<?> dtk) {
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("DirectUnsendMessageInteractor")))) {
            String owner = md.getClassName();
            for (Found m : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(owner)))) {
                for (MethodData inv : DexKitQueryCache.invokes(m)) {
                    if (!FUTURE.equals(inv.getReturnTypeName())) continue;
                    List<String> p = inv.getParamTypeNames();
                    if (p.size() != 2 || !DTK.equals(p.get(1))) continue;
                    try {
                        Method um = inv.getMethodInstance(cl);
                        if (isUnsend(um, dtk)) return um;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    private static Constructor<?> findRef(ClassLoader cl, Method unsend, Class<?> message, Class<?> dtk)
            throws ClassNotFoundException {
        Class<?> refBase = unsend.getParameterTypes()[0];
        for (FoundClass cd : DexKitQueryCache.classes(FindClass.create().matcher(ClassMatcher.create()
                .superClass(refBase.getName())))) {
            Class<?> c = cd.getInstance(cl);
            for (Constructor<?> k : ps.reso.instaeclipse.utils.core.SafeReflect.constructors(c)) {
                if (isRef(k, unsend, message, dtk)) return k;
            }
        }
        return null;
    }

    private static Method senderAccessor(Class<?> sender) {
        for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(sender)) {
            Class<?>[] p = m.getParameterTypes();
            if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != sender || p.length != 1
                    || !SESSION.equals(p[0].getName())) continue;
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    static final class Ctx {
        Object sender;
        Object session;
        Object threadKey;
    }

    private static Ctx chatContext(Activity act, String openThread) {
        Ctx c = new Ctx();
        Object fragment = CurrentThreadTracker.fragment(act,
                f -> openThread == null || openThread.equals(CurrentThreadTracker.threadIdOf(f)));
        c.session = sessionOf(fragment);
        Method accessor = senderFor;
        if (accessor != null && c.session != null) {
            try { c.sender = accessor.invoke(null, c.session); } catch (Throwable ignored) {}
        }
        if (c.sender == null && lastCache != null) fromCache(c, lastCache);
        c.threadKey = HideChatsHook.openThreadKey(act, openThread);
        return c;
    }

    private static Object sessionOf(Object owner) {
        Method getter = owner == null ? null : HideChatsHook.noArgMethod(owner.getClass(), "getSession");
        if (getter == null) return null;
        try {
            Object session = getter.invoke(owner);
            return session != null && SESSION.equals(session.getClass().getName()) ? session : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void fromCache(Ctx c, Object cache) {
        Class<?> senderClass = unsendMethod.getDeclaringClass();
        Object[] container = new Object[1];
        Object[] sender = new Object[1];
        walk(cache, 4, (o, v) -> {
            if (senderClass.isInstance(v)) { container[0] = o; sender[0] = v; return true; }
            return false;
        });
        if (container[0] == null) return;
        c.sender = sender[0];
        if (c.session != null) return;
        walk(container[0], 3, (o, v) -> {
            if (c.session == null && SESSION.equals(v.getClass().getName())) c.session = v;
            return c.session != null;
        });
        if (c.session == null) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c.sender.getClass())) {
                if (!SESSION.equals(f.getType().getName())) continue;
                try { f.setAccessible(true); c.session = f.get(c.sender); } catch (Throwable ignored) {}
            }
        }
    }

    private interface Visit { boolean on(Object owner, Object value); }

    private static void walk(Object start, int maxDepth, Visit v) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{start, 0});
        while (!q.isEmpty() && seen.size() < 3000) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o) || d >= maxDepth) continue;
            Class<?> k = o.getClass();
            String n = k.getName();
            if (n.startsWith("java.") || n.startsWith("android.") || SESSION.equals(n)) continue;
            if (!(o instanceof View)) {
                Object val = initializedLazyValue(o);
                if (val != null) {
                    if (v.on(o, val)) return;
                    q.add(new Object[]{val, d + 1});
                }
            }
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.") && !n.startsWith("androidx.recyclerview.")) continue;
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                if (x.getName().startsWith("android.")) break;
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(x)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(o);
                        if (val == null || val instanceof View) continue;
                        if (v.on(o, val)) return;
                        q.add(new Object[]{val, d + 1});
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    static Object initializedLazyValue(Object o) {
        if (o instanceof java.util.concurrent.Future<?>) return null;
        for (Class<?> type : o.getClass().getInterfaces()) {
            Method value = null;
            Method initialized = null;
            boolean other = false;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(type)) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() == 0 && "getValue".equals(m.getName()) && m.getReturnType() == Object.class) value = m;
                else if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class && initialized == null) initialized = m;
                else other = true;
            }
            if (other || value == null || initialized == null) continue;
            try {
                if (!Boolean.TRUE.equals(initialized.invoke(o))) return null;
                return value.invoke(o);
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String userId(Object session) {
        try { return (String) session.getClass().getMethod("getUserId").invoke(session); }
        catch (Throwable t) { return null; }
    }

    private static Object threadKeyOf(Object msg) {
        for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || !DTK.equals(f.getType().getName())) continue;
                try { f.setAccessible(true); Object v = f.get(msg); if (v != null) return v; } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object messageOf(View row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        Class<?> message = messageClass;
        if (lp == null || message == null) return null;
        for (Field f : holderFields(lp.getClass())) {
            try {
                Object holder = f.get(lp);
                if (holder == null || holder instanceof View) continue;
                Object msg = UnsentIdentity.directValue(UnsentIdentity.boundData(holder, message), message);
                if (msg != null) return msg;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Field[] holderFields(Class<?> params) {
        return HOLDER_FIELDS.computeIfAbsent(params, k -> {
            List<Field> out = new ArrayList<>();
            for (Class<?> x = k; x != null && x != Object.class && !x.getName().startsWith("android."); x = x.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(x)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) {}
                }
            }
            return out.toArray(new Field[0]);
        });
    }

    public static void show(Activity act, Object pressed) {
        if (act == null) return;
        if (active != null) active.exit();
        if (!ready()) { toast(act, I18n.t(act, R.string.ig_bulk_unsend_unavailable)); return; }
        View list = ThreadHeaderMenu.findShown(act.getWindow().getDecorView(), ResIds.id(act, "message_list"));
        String open = HideChatsHook.resolveThreadId(act);
        Ctx c;
        try { c = chatContext(act, open); } catch (Throwable t) { c = null; ModuleLog.detail(TAG + "resolve failed: " + t); }
        String me = c != null && c.session != null ? userId(c.session) : null;
        if (c == null || c.sender == null || me == null || !(list instanceof ViewGroup rv)) {
            ModuleLog.warn(TAG + "Could not start selecting messages: this chat is not ready yet");
            ModuleLog.detail(TAG + "resolve: sender=" + (c != null && c.sender != null) + " me=" + (me != null)
                    + " list=" + (list != null) + " thread=" + (open != null));
            toast(act, I18n.t(act, R.string.ig_bulk_unsend_not_ready));
            return;
        }
        Selection selection = start(act, rv, c, me, open, pressed);
        ModuleLog.detail(TAG + "selection mode: thread=" + (open != null) + " key=" + (c.threadKey != null) + " rows=" + selection.rows.size());
    }

    static Selection start(Activity act, ViewGroup rv, Ctx c, String me, String thread, Object pressed) {
        if (active != null) active.exit();
        active = new Selection(act, rv, c, me, thread, pressed);
        return active;
    }

    public static IeDialog confirm(Activity act, int count, Runnable unsend) {
        IeDialog dlg = new IeDialog(act).title(I18n.t(act, R.string.ig_dialog_bulk_unsend))
                .message(text(act).confirm(count));
        dlg.secondary(I18n.t(act, R.string.ig_dialog_cancel), null);
        dlg.destructive(I18n.t(act, R.string.ig_bulk_unsend_go), unsend);
        return dlg;
    }

    static BulkUnsendText text(Context c) {
        return new BulkUnsendText((id, count, args) -> I18n.moduleResources(c).getQuantityString(id, count, args));
    }

    static final class Selection {
        final Activity act;
        final ViewGroup rv;
        final Ctx ctx;
        final String me;
        final String thread;
        final List<Object> selected = new ArrayList<>();
        final IdentityHashMap<Object, Boolean> mineCache = new IdentityHashMap<>();
        final IdentityHashMap<View, Object> rows = new IdentityHashMap<>();
        final Overlay overlay;
        final Bar bar;
        final ViewTreeObserver observer;
        final ViewTreeObserver.OnPreDrawListener preDraw;
        final View.OnAttachStateChangeListener detach;
        volatile boolean stopped;
        boolean unsending;
        long lastLayout;
        long rowSet = Long.MIN_VALUE;

        Selection(Activity act, ViewGroup rv, Ctx ctx, String me, String thread, Object pressed) {
            this.act = act;
            this.rv = rv;
            this.ctx = ctx;
            this.me = me;
            this.thread = thread;
            refreshRows();
            Object shown = shownRowOf(pressed);
            if (shown != null && isMine(shown)) selected.add(shown);
            float d = act.getResources().getDisplayMetrics().density;

            overlay = new Overlay(act, this, IePalette.of(act), d);
            bar = new Bar(act, this::exit, this::confirmSelected);
            bar.selected(selected.size());

            ViewGroup content = act.findViewById(android.R.id.content);
            content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            int m = (int) (10 * d);
            blp.setMargins(m, 0, m, m);
            content.addView(bar.view(), blp);
            overlay.requestFocus();
            InputMethodManager ime = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(rv.getWindowToken(), 0);

            preDraw = () -> {
                if (!rv.isShown() || !rv.isAttachedToWindow()) { exit(); return true; }
                refreshRows();
                long sig = rv.getChildCount();
                for (int i = 0; i < rv.getChildCount(); i++) sig = sig * 31 + rv.getChildAt(i).getTop() + rv.getChildAt(i).hashCode();
                if (sig != lastLayout) { lastLayout = sig; overlay.invalidate(); }
                return true;
            };
            detach = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {}

                @Override public void onViewDetachedFromWindow(View v) { exit(); }
            };
            observer = rv.getViewTreeObserver();
            observer.addOnPreDrawListener(preDraw);
            rv.addOnAttachStateChangeListener(detach);
        }

        void refreshRows() {
            long set = rv.getChildCount();
            for (int i = 0; i < rv.getChildCount(); i++) set = set * 31 + System.identityHashCode(rv.getChildAt(i));
            if (set == rowSet) return;
            rowSet = set;
            rows.clear();
            for (int i = 0; i < rv.getChildCount(); i++) {
                View ch = rv.getChildAt(i);
                Object msg = messageOf(ch);
                if (msg != null) rows.put(ch, msg);
            }
        }

        Object shownRowOf(Object msg) {
            if (msg == null) return null;
            for (Object m : rows.values()) if (m == msg) return m;
            List<String> ids = UnsentIdentity.messageIds(msg);
            if (ids.isEmpty()) return null;
            for (Object m : rows.values()) if (!Collections.disjoint(ids, UnsentIdentity.messageIds(m))) return m;
            return null;
        }

        boolean isMine(Object msg) {
            Boolean b = mineCache.get(msg);
            if (b == null) {
                b = UnsentIdentity.senderField(messageClass) != null
                        ? UnsentIdentity.isFromAccount(msg, me) : UnsentIdentity.anyStringFieldIs(msg, me);
                mineCache.put(msg, b);
            }
            return b;
        }

        Object messageAt(float x, float y) {
            for (int i = rv.getChildCount() - 1; i >= 0; i--) {
                View ch = rv.getChildAt(i);
                if (y < ch.getTop() || y > ch.getBottom()) continue;
                Object msg = rows.get(ch);
                return msg != null && isMine(msg) ? msg : null;
            }
            return null;
        }

        void toggle(Object msg) {
            if (unsending) return;
            if (!selected.remove(msg)) selected.add(msg);
            bar.selected(selected.size());
            overlay.invalidate();
        }

        void confirmSelected() {
            if (selected.isEmpty() || unsending) return;
            confirm(act, selected.size(), this::unsendSelected).show();
        }

        void unsendSelected() {
            if (selected.isEmpty() || unsending || stopped || active != this) return;
            unsending = true;
            List<Object> picked = new ArrayList<>(selected);
            bar.progress(0, picked.size());
            unsendAll(act, ctx, thread, picked, this);
        }

        void progress(int done, int total) {
            if (!stopped) bar.progress(done, total);
        }

        void exit() {
            stopped = true;
            if (active == this) active = null;
            try { rv.removeOnAttachStateChangeListener(detach); } catch (Throwable ignored) {}
            try { if (observer.isAlive()) observer.removeOnPreDrawListener(preDraw); } catch (Throwable ignored) {}
            for (View v : new View[]{overlay, bar.view()}) {
                if (v.getParent() instanceof ViewGroup p) p.removeView(v);
            }
        }
    }

    public static final class Bar {
        private final Activity act;
        private final LinearLayout view;
        private final TextView cancel;
        private final TextView status;
        private final TextView go;

        public Bar(Activity act, Runnable onCancel, Runnable onGo) {
            this.act = act;
            IePalette p = IePalette.of(act);
            float d = act.getResources().getDisplayMetrics().density;
            int target = Math.round(48 * d);
            view = new LinearLayout(act);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding((int) (16 * d), (int) (4 * d), (int) (8 * d), (int) (4 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(p.dialog);
            bg.setCornerRadius(24 * d);
            bg.setStroke(Math.max(1, (int) d), p.line);
            view.setBackground(bg);
            view.setElevation(8 * d);
            cancel = new TextView(act);
            cancel.setText(I18n.t(act, R.string.ig_dialog_cancel));
            cancel.setTextSize(15);
            cancel.setTextColor(p.text);
            cancel.setGravity(Gravity.CENTER_VERTICAL);
            cancel.setMinHeight(target);
            cancel.setPadding((int) (6 * d), 0, (int) (12 * d), 0);
            cancel.setOnClickListener(v -> onCancel.run());
            view.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            status = new TextView(act);
            status.setTextSize(15);
            status.setTextColor(p.text);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setMinHeight(target);
            status.setPadding((int) (6 * d), 0, (int) (6 * d), 0);
            status.setVisibility(View.GONE);
            view.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            go = new TextView(act);
            go.setTextSize(15);
            go.setTypeface(Typeface.DEFAULT_BOLD);
            go.setTextColor(p.onAccent);
            go.setGravity(Gravity.CENTER);
            go.setMinHeight(target);
            go.setPadding((int) (18 * d), 0, (int) (18 * d), 0);
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(p.accent);
            pill.setCornerRadius(24 * d);
            go.setBackground(pill);
            go.setOnClickListener(v -> onGo.run());
            view.addView(go);
        }

        public View view() {
            return view;
        }

        public void selected(int n) {
            go.setText(I18n.t(act, R.string.ig_bulk_unsend_go) + (n > 0 ? " (" + n + ")" : ""));
            go.setAlpha(n > 0 ? 1f : 0.5f);
        }

        public void progress(int done, int total) {
            cancel.setVisibility(View.GONE);
            go.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            status.setText(text(act).progress(done, total));
        }
    }

    @SuppressLint("ViewConstructor")
    static final class Overlay extends View {
        final Selection s;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tint = new Paint();
        final float d;
        final int slop;
        final int[] rvLoc = new int[2], myLoc = new int[2];
        MotionEvent down;
        boolean forwarding;
        boolean guarding;

        Overlay(Context c, Selection s, IePalette p, float d) {
            super(c);
            this.s = s;
            this.d = d;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            setFocusable(true);
            setFocusableInTouchMode(true);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(IePalette.readable(p.sub, p.backdrop, IePalette.ICON));
            fill.setColor(p.accent);
            tick.setColor(p.onAccent);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(2.2f * d);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tint.setColor((p.accent & 0x00FFFFFF) | 0x33000000);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
            if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) s.exit();
            return true;
        }

        private float dx() { s.rv.getLocationInWindow(rvLoc); getLocationInWindow(myLoc); return rvLoc[0] - myLoc[0]; }
        private float dy() { s.rv.getLocationInWindow(rvLoc); getLocationInWindow(myLoc); return rvLoc[1] - myLoc[1]; }

        @Override
        protected void onDraw(Canvas canvas) {
            float ox = dx(), oy = dy();
            canvas.save();
            canvas.clipRect(ox, oy, ox + s.rv.getWidth(), oy + s.rv.getHeight());
            float r = 11 * d, cx = ox + 22 * d;
            for (int i = 0; i < s.rv.getChildCount(); i++) {
                View ch = s.rv.getChildAt(i);
                Object msg = s.rows.get(ch);
                if (msg == null || !s.isMine(msg)) continue;
                float top = oy + ch.getTop() + ch.getTranslationY(), bottom = oy + ch.getBottom() + ch.getTranslationY();
                float cy = (top + bottom) / 2f;
                boolean on = s.selected.contains(msg);
                if (on) {
                    canvas.drawRect(ox, top, ox + s.rv.getWidth(), bottom, tint);
                    canvas.drawCircle(cx, cy, r, fill);
                    canvas.drawLine(cx - 5 * d, cy, cx - 1.5f * d, cy + 4 * d, tick);
                    canvas.drawLine(cx - 1.5f * d, cy + 4 * d, cx + 5.5f * d, cy - 4 * d, tick);
                } else {
                    canvas.drawCircle(cx, cy, r, ring);
                }
            }
            canvas.restore();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float ox = dx(), oy = dy();
            float x = e.getX() - ox, y = e.getY() - oy;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    guarding = x < 0 || y < 0 || x > s.rv.getWidth() || y > s.rv.getHeight();
                    forwarding = false;
                    if (guarding) return true;
                    if (down != null) down.recycle();
                    down = MotionEvent.obtain(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (guarding) return true;
                    if (!forwarding && down != null && Math.hypot(e.getX() - down.getX(), e.getY() - down.getY()) > slop) {
                        forwarding = true;
                        forward(down, ox, oy);
                    }
                    if (forwarding) forward(e, ox, oy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (guarding) { guarding = false; return true; }
                    if (forwarding) { forward(e, ox, oy); forwarding = false; return true; }
                    Object msg = s.messageAt(x, y);
                    if (msg != null) s.toggle(msg);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (forwarding && !guarding) forward(e, ox, oy);
                    forwarding = false;
                    guarding = false;
                    return true;
                default:
                    if (forwarding && !guarding) forward(e, ox, oy);
                    return true;
            }
        }

        private void forward(MotionEvent e, float ox, float oy) {
            MotionEvent c = MotionEvent.obtain(e);
            c.offsetLocation(-ox, -oy);
            try { s.rv.dispatchTouchEvent(c); } catch (Throwable ignored) {}
            c.recycle();
        }
    }

    private static void unsendAll(Activity act, Ctx c, String thread, List<Object> msgs, Selection selection) {
        Handler main = new Handler(Looper.getMainLooper());
        BulkUnsendText text = text(act);
        new Thread(() -> {
            UnsendTally tally = new UnsendTally(msgs.size(), OWN_IDS, PENDING_IDS);
            for (int i = 0; i < msgs.size() && !selection.stopped; i++) {
                Object m = msgs.get(i);
                int at = i + 1;
                main.post(() -> selection.progress(at, msgs.size()));
                Object key = threadKeyOf(m);
                if (key == null) key = c.threadKey;
                if (!UnsendTally.sameChat(thread, key != null ? UnsentIdentity.thread(key) : null)) {
                    ModuleLog.detail(TAG + "skipped a message: " + (key == null ? "no thread key" : "another chat"));
                    continue;
                }
                List<String> ids = messageIds(m, key);
                tally.begin(ids);
                Object r = null;
                try {
                    Object ref = refCtor.newInstance(m, key);
                    Object future = unsendMethod.invoke(c.sender, ref, key);
                    r = future instanceof java.util.concurrent.Future<?> f ? f.get(10, java.util.concurrent.TimeUnit.SECONDS) : future;
                    ModuleLog.detail(TAG + "unsend → " + r);
                } catch (Throwable t) {
                    ModuleLog.detail(TAG + "unsend failed: " + t + " / " + t.getCause());
                }
                if (tally.finish(ids, r)) {
                    try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            }
            int done = tally.unsent();
            int total = tally.total();
            if (selection.stopped && done < total) ModuleLog.detail(TAG + "stopped by leaving selection mode");
            ModuleLog.line(TAG + (done == total ? "" : done == 0 ? "❌ " : "⚠️ ") + "Unsent " + done + " of " + total + " messages");
            main.post(() -> {
                selection.exit();
                toast(act, text.result(done, total));
            });
        }, "IE-BulkUnsend").start();
    }

    private static List<String> messageIds(Object msg, Object key) {
        List<String> out = new ArrayList<>();
        String thread = CurrentThreadTracker.firstStringField(key);
        for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (!(v instanceof String s) || s.equals(thread)) continue;
                    if (s.startsWith("mid.") || s.matches("[0-9]{15,}") || s.matches("[0-9a-fA-F-]{20,}")) out.add(s);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static void toast(Context c, String s) {
        try { ps.reso.instaeclipse.utils.toast.IeToast.makeText(c, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
