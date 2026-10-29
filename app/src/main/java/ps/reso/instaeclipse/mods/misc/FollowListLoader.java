package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.AbsListView;
import android.widget.Adapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ProgressBar;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

final class FollowListLoader {

    private static final String TAG = "(IE|FollowExport) ";
    static final String USER = "com.instagram.user.model.User";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 400;

    private static Field pagerField, nextMaxIdField;
    private static Method pagerIsLoading, pagerLoadMore, pagerFetch;
    private static Method usernameGetter, fullNameGetter, followerCountGetter, followingCountGetter;
    private static volatile Field userIdField;
    private static volatile Load current;

    private static Field pageField, pageKeyField, pageOwnerField;
    private static Method pageLimited, pageUsers, pageLimitedFollowing, pageHiddenFollowing;
    private static volatile Field ownerFragField;
    private static Field failureFragField;
    private static Method failurePayload, failureError;
    private static final Map<String, String> errorGetters = new HashMap<>();
    private static Class<?> listFragClass;
    private static final Map<Object, Boolean> limitedLists = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final String LIMIT_KEY = "should_limit_list_of_followers";
    private static final List<Object[]> responseTypes = new ArrayList<>();
    private static Field orderField, orderValueField;
    private static Method sortMethod;
    private static Field queryManagerField;
    private static Field[] pageCacheFields;
    private static Method pageCacheClear;
    static final String PANDO_REQUEST = "com.facebook.pando.PandoGraphQLRequest";
    private static final String SURFACE = "follow_list_page";
    private static final Map<Method, Object[]> listBuilders = new HashMap<>();

    private FollowListLoader() {}

    static final class Result {
        final Map<String, String[]> users;
        final boolean complete;
        final FollowListPolicy.Block blocked;
        final boolean interrupted;
        final int expected;
        final boolean limited;
        final int hiddenFollowing;
        final int received, repeated;
        final int passes;
        final int lastPassAdded;
        final int f1, f2;
        final double estMissing;

        Result(Map<String, String[]> users, boolean complete, FollowListPolicy.Block blocked, boolean interrupted,
               int expected, boolean limited, int hiddenFollowing,
               int received, int repeated, int passes, int lastPassAdded, int f1, int f2, double estMissing) {
            this.users = users;
            this.complete = complete;
            this.blocked = blocked;
            this.interrupted = interrupted;
            this.expected = expected;
            this.limited = limited;
            this.hiddenFollowing = hiddenFollowing;
            this.received = received;
            this.repeated = repeated;
            this.passes = passes;
            this.lastPassAdded = lastPassAdded;
            this.f1 = f1;
            this.f2 = f2;
            this.estMissing = estMissing;
        }
    }

    static boolean resolve(ClassLoader cl, Class<?> fragClass) {
        try {
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(USER).paramCount(0).returnType("java.lang.String").usingNumbers("full_name".hashCode())))) {
                fullNameGetter = md.getMethodInstance(cl);
                break;
            }
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .declaredClass(USER).paramCount(0).returnType("java.lang.String").usingNumbers("username".hashCode())))) {
                Method m = md.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers())) continue;
                usernameGetter = m;
                break;
            }
            followerCountGetter = countGetter(cl, "follower_count");
            followingCountGetter = countGetter(cl, "following_count");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Loaded lists may miss usernames, names and Instagram's counts: " + t);
        }
        try {
            findPager(cl, fragClass);
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not load whole lists: the list loader was not found in this Instagram version", t);
        }
        boolean pages = false;
        try {
            listFragClass = fragClass;
            hookPages(cl);
            pages = true;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists still load, but a list Instagram limits may not be recognised: " + t);
        }
        try {
            findSort(fragClass);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists load in one pass only (the list order cannot be changed), so a few accounts may be missing: " + t);
        }
        try {
            findPageCaches(fragClass);
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "page caches: " + t);
        }
        boolean builders = false;
        try {
            hookListBuilders(cl);
            builders = listBuilders.size() == 2;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists still load, but may come partly from Instagram's cache: " + t);
        }
        try {
            hookFailures(cl, fragClass);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Lists still load, but a block by Instagram while loading may not be recognised: " + t);
        }
        StringBuilder hooked = new StringBuilder();
        for (Map.Entry<Method, Object[]> e : listBuilders.entrySet())
            hooked.append(hooked.length() > 0 ? "," : "").append(e.getValue()[0]).append('=').append(e.getKey().getName())
                    .append((int) e.getValue()[1] >= 0 ? "+refresh" : "");
        ModuleLog.detail(TAG + "list request hooks: graphql=[" + hooked + "] page caches=" + (pageCacheFields != null
                ? pageCacheFields.length + " (" + pageCacheFields[0].getType().getName() + ")" : "none, orders not repeated"));
        ModuleLog.detail(TAG + "pager=" + (pagerField != null ? pagerField.getType().getName() : null)
                + " isLoading=" + (pagerIsLoading != null) + " loadMore=" + name(pagerLoadMore) + " fetch=" + name(pagerFetch)
                + " username=" + name(usernameGetter) + " fullName=" + name(fullNameGetter)
                + " counts=" + name(followerCountGetter) + "/" + name(followingCountGetter)
                + " pages=" + (pageField != null ? pageField.getDeclaringClass().getName() : null)
                + " limit=" + name(pageLimited) + "/" + name(pageLimitedFollowing) + " hidden=" + name(pageHiddenFollowing)
                + " users=" + name(pageUsers) + " response models=" + responseTypes.size()
                + " failure=" + (failureFragField != null ? failureFragField.getDeclaringClass().getName() : null) + " " + errorGetters
                + " sort=" + name(sortMethod) + "(" + (orderField != null ? orderField.getType().getName() : null) + ")");
        return pagerField != null && pagerLoadMore != null && pages && builders;
    }

    private static void hookPages(ClassLoader cl) throws Throwable {
        List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(List.of("fetch_suggested_users_optimized", "ui_render_start")).paramCount(0)));
        if (found.size() != 1) throw new IllegalStateException("page handler: " + found.size() + " matches");
        Method handler = found.get(0).getMethodInstance(cl);
        for (Field f : SafeReflect.fields(handler.getDeclaringClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            if (f.getType().isInterface()) pageField = f;
            else if (f.getType() == String.class) pageKeyField = f;
            else pageOwnerField = f;
        }
        if (pageField == null || pageOwnerField == null) throw new IllegalStateException("page handler fields");
        ClassMatcher page = ClassMatcher.create().addInterface(pageField.getType().getName(), StringMatchType.Equals, false);
        pageLimited = pageGetter(MethodMatcher.create().declaredClass(page).paramCount(0).returnType("boolean")
                .usingNumbers(LIMIT_KEY.hashCode()));
        pageUsers = pageGetter(MethodMatcher.create().declaredClass(page).paramCount(0).returnType("java.util.List")
                .addInvoke(MethodMatcher.create().name("getItems")));
        pageLimitedFollowing = pageGetter(MethodMatcher.create().declaredClass(page).paramCount(0).returnType("boolean")
                .usingNumbers("should_limit_list_of_followings".hashCode()));
        pageHiddenFollowing = pageGetter(MethodMatcher.create().declaredClass(page).paramCount(0).returnType("int")
                .usingNumbers("hidden_following_account_count".hashCode()));
        if (pageLimited == null) resolveResponse(cl);
        XposedBridge.hookMethod(handler, PAGE_HOOK);
    }

    private static Method pageGetter(MethodMatcher m) {
        try {
            String name = null;
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(m))) {
                if (name != null && !name.equals(md.getName())) return null;
                name = md.getName();
            }
            if (name == null) return null;
            Method g = pageField.getType().getMethod(name);
            g.setAccessible(true);
            return g;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "page getter: " + t);
            return null;
        }
    }

    private static void resolveResponse(ClassLoader cl) throws Throwable {
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(0).returnType("java.lang.Boolean").usingNumbers(LIMIT_KEY.hashCode())))) {
            Class<?> model = cl.loadClass(md.getClassName()), type = model;
            for (Class<?> i : model.getInterfaces()) if (declares(i, md.getName())) { type = i; break; }
            boolean known = false;
            for (Object[] t : responseTypes) if (t[0] == type) known = true;
            if (known) continue;
            try {
                Method getter = type.getMethod(md.getName());
                getter.setAccessible(true);
                responseTypes.add(new Object[]{type, getter});
            } catch (NoSuchMethodException ignored) {}
        }
    }

    private static boolean declares(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static final XC_MethodHook PAGE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            if (!FeatureFlags.followListExport && !FeatureFlags.profileAnalyzer) return;
            Load l = current;
            try {
                if (l != null) l.page(param.thisObject);
                else rememberLimited(param.thisObject);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "page: " + t);
            }
        }
    };

    private static void rememberLimited(Object step) throws Exception {
        if (listFragClass == null) return;
        Object page = pageField.get(step);
        if (!Boolean.TRUE.equals(call(pageLimited, page)) && !Boolean.TRUE.equals(call(pageLimitedFollowing, page))) return;
        Object owner = pageOwnerField.get(step);
        if (owner == null) return;
        for (Field f : SafeReflect.fields(owner.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            f.setAccessible(true);
            Object v = f.get(owner);
            if (listFragClass.isInstance(v)) {
                limitedLists.put(v, Boolean.TRUE);
                return;
            }
        }
    }

    private static Object[] responseOf(Object page) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{page, 0});
        while (!q.isEmpty()) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            for (Object[] t : responseTypes) if (((Class<?>) t[0]).isInstance(o)) return new Object[]{o, t[1]};
            if (d >= 2) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        q.add(new Object[]{f.get(o), d + 1});
                    } catch (Throwable ignored) {}
                }
        }
        return null;
    }

    private static Object responseLimit(Object page) {
        if (responseTypes.isEmpty()) return NOT_READ;
        Object[] r = responseOf(page);
        return r != null ? call((Method) r[1], r[0]) : NOT_READ;
    }

    private static Object call(Method getter, Object o) {
        if (getter == null || o == null) return NOT_READ;
        try {
            return getter.invoke(o);
        } catch (Throwable t) {
            return NOT_READ;
        }
    }

    private static final Object NOT_READ = new Object() {
        @Override public String toString() { return "?"; }
    };

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : SafeReflect.fields(k)) if (!Modifier.isStatic(f.getModifiers())) out.add(f);
        return out;
    }

    private static void findSort(Class<?> fragClass) {
        for (Class<?> c = fragClass; c != null && orderField == null; c = c.getSuperclass())
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isEnum()
                        || order(f.getType(), FollowListPolicy.ORDER_LATEST) == null
                        || order(f.getType(), FollowListPolicy.ORDER_EARLIEST) == null) continue;
                f.setAccessible(true);
                orderField = f;
                break;
            }
        if (orderField == null) return;
        for (Field f : SafeReflect.fields(orderField.getType()))
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                f.setAccessible(true);
                orderValueField = f;
            }
        Method found = null;
        for (Class<?> c = fragClass; c != null && c != Object.class; c = c.getSuperclass())
            for (Method m : SafeReflect.methods(c)) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != orderField.getType()) continue;
                if (found != null) return;
                found = m;
            }
        if (found != null) found.setAccessible(true);
        sortMethod = found;
    }

    private static Object order(Class<?> type, String name) {
        Object[] all = type.getEnumConstants();
        if (all != null) for (Object o : all) if (((Enum<?>) o).name().equals(name)) return o;
        return null;
    }

    private static String orderValue(Object order) {
        try {
            if (orderValueField != null && orderValueField.get(order) instanceof String s) return s;
        } catch (Throwable ignored) {}
        return order != null ? ((Enum<?>) order).name() : null;
    }

    private static void findPageCaches(Class<?> fragClass) throws Throwable {
        Field found = null;
        List<Field> caches = null;
        for (Class<?> c = fragClass; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().startsWith("X.")) continue;
                boolean mode = false;
                List<Field> fs = new ArrayList<>();
                for (Field g : SafeReflect.fields(f.getType())) {
                    if (Modifier.isStatic(g.getModifiers())) continue;
                    if (g.getType() == boolean.class) mode = true;
                    else if (isPageCache(g.getType())) fs.add(g);
                }
                if (!mode || fs.size() != 2 || fs.get(0).getType() != fs.get(1).getType()) continue;
                if (found != null) return;
                found = f;
                caches = fs;
            }
        if (found == null) return;
        Method clear = caches.get(0).getType().getMethod("clear");
        clear.setAccessible(true);
        found.setAccessible(true);
        for (Field f : caches) f.setAccessible(true);
        queryManagerField = found;
        pageCacheFields = caches.toArray(new Field[0]);
        pageCacheClear = clear;
    }

    private static boolean isPageCache(Class<?> c) {
        if (c.isPrimitive() || c.isInterface() || !c.getName().startsWith("X.")) return false;
        try {
            Method clear = c.getMethod("clear");
            if (clear.getReturnType() != void.class) return false;
        } catch (NoSuchMethodException e) {
            return false;
        }
        for (Field f : SafeReflect.fields(c))
            if (!Modifier.isStatic(f.getModifiers()) && Map.class.isAssignableFrom(f.getType())) return true;
        return false;
    }

    private static boolean clearPageCaches(Object frag) {
        if (queryManagerField == null || pageCacheFields == null) return false;
        try {
            Object qm = queryManagerField.get(frag);
            if (qm == null) return false;
            for (Field f : pageCacheFields) {
                Object cache = f.get(qm);
                if (cache != null) pageCacheClear.invoke(cache);
            }
            return true;
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "clear page caches: " + (t.getCause() != null ? t.getCause() : t));
            return false;
        }
    }

    private static void hookListBuilders(ClassLoader cl) throws Throwable {
        for (String[] k : new String[][]{{FollowListExportHook.FOLLOWERS, "xdt_api__v1__friendships__followers"},
                {FollowListExportHook.FOLLOWING, "xdt_api__v1__friendships__following"}}) {
            List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .usingEqStrings(List.of(k[1], SURFACE, "rank_token")).returnType(PANDO_REQUEST)));
            if (found.size() != 1) {
                ModuleLog.warn(TAG + (FollowListExportHook.FOLLOWERS.equals(k[0]) ? "Followers" : "Following")
                        + " lists still load, but may come partly from Instagram's cache");
                ModuleLog.detail(TAG + "list requests: " + k[0] + " builder: " + found.size() + " matches");
                continue;
            }
            Method m = found.get(0).getMethodInstance(cl);
            Class<?>[] p = m.getParameterTypes();
            listBuilders.put(m, new Object[]{k[0], p.length > 0 && p[p.length - 1] == boolean.class ? p.length - 1 : -1});
        }
        for (Method m : listBuilders.keySet()) XposedBridge.hookMethod(m, BUILDER_HOOK);
    }

    private static final XC_MethodHook BUILDER_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Load l = current;
            if (l == null) return;
            try {
                Object[] b = listBuilders.get(param.method);
                if (b == null || !l.ownsRequest((String) b[0], param.args)) return;
                int refresh = (int) b[1];
                if (refresh >= 0) param.args[refresh] = Boolean.TRUE;
                l.requestBuilt(refresh >= 0);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "list requests: " + t);
            }
        }
    };

    private static void hookFailures(ClassLoader cl, Class<?> fragClass) throws Throwable {
        List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(List.of("UserList onRequestFail", "fetch_request_fail")).paramCount(2)));
        if (found.size() != 1) throw new IllegalStateException(found.size() + " matches");
        Method cb = found.get(0).getMethodInstance(cl);
        Field frag = null;
        for (Field f : instanceFields(cb.getDeclaringClass())) if (f.getType() == fragClass) frag = f;
        Method payload = null, error = null;
        for (Method m : SafeReflect.methods(cb.getParameterTypes()[0])) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            if (m.getReturnType() == Object.class) payload = m;
            else if (m.getReturnType() == Throwable.class) error = m;
        }
        if (frag == null || payload == null || error == null) throw new IllegalStateException("fragment " + (frag != null)
                + ", answer " + (payload != null) + ", exception " + (error != null));
        frag.setAccessible(true);
        payload.setAccessible(true);
        error.setAccessible(true);
        Set<String> models = new TreeSet<>();
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(0).returnType("java.lang.String").usingEqStrings("checkpoint_url"))))
            models.add(md.getClassName());
        List<Class<?>> others = new ArrayList<>();
        String first = null;
        for (String model : models) {
            if (first == null) first = model;
            else try { others.add(cl.loadClass(model)); } catch (ClassNotFoundException ignored) {}
        }
        String[][] keys = {{"message", "java.lang.String"}, {"error_type", "java.lang.String"}, {"checkpoint_url", "java.lang.String"},
                {"feedback_message", "java.lang.String"}, {"checkpoint_required", "boolean"}, {"login_required", "boolean"}, {"challenge", null}};
        for (String[] k : keys) {
            if (first == null) break;
            MethodMatcher mm = MethodMatcher.create().declaredClass(first).paramCount(0).usingEqStrings(k[0]);
            if (k[1] != null) mm.returnType(k[1]);
            Set<String> names = new HashSet<>();
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(mm))) {
                String rt = md.getReturnTypeName();
                if (md.getName().equals("toString")
                        || (k[1] == null && (rt.equals("java.lang.String") || rt.equals("boolean") || rt.equals("void")))) continue;
                names.add(md.getName());
            }
            for (Class<?> other : others) names.removeIf(n -> !declares(other, n));
            if (names.size() == 1) errorGetters.put(k[0], names.iterator().next());
        }
        failureFragField = frag;
        failurePayload = payload;
        failureError = error;
        XposedBridge.hookMethod(cb, FAILURE_HOOK);
    }

    private static final XC_MethodHook FAILURE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Load l = current;
            if (l == null) return;
            try {
                if (failureFragField.get(param.thisObject) != l.frag) return;
                FollowListPolicy.Failed f = failureOf(param.args[0]);
                String key = param.args[1] instanceof String s ? s : null;
                H.post(() -> l.requestFailed(f, key));
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "failure: " + t);
            }
        }
    };

    private static FollowListPolicy.Failed failureOf(Object result) {
        FollowListPolicy.Failed f = new FollowListPolicy.Failed();
        Object answer = result != null ? call(failurePayload, result) : null;
        Object error = result != null ? call(failureError, result) : null;
        if (answer == NOT_READ) answer = null;
        if (error instanceof Throwable t) {
            f.exception = true;
            f.message = t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            return f;
        }
        if (answer == null) return f;
        try {
            if (answer.getClass().getMethod("getStatusCode").invoke(answer) instanceof Integer s) f.status = s;
        } catch (Throwable ignored) {}
        f.message = errorString(answer, "message");
        f.errorType = errorString(answer, "error_type");
        f.feedbackMessage = errorString(answer, "feedback_message");
        String url = errorString(answer, "checkpoint_url");
        f.checkpointUrl = url != null && !url.isEmpty();
        f.challenge = errorValue(answer, "challenge") != null;
        f.checkpointRequired = Boolean.TRUE.equals(errorValue(answer, "checkpoint_required"));
        f.loginRequired = Boolean.TRUE.equals(errorValue(answer, "login_required"));
        return f;
    }

    private static Object errorValue(Object answer, String key) {
        String name = errorGetters.get(key);
        if (name == null) return null;
        try {
            return answer.getClass().getMethod(name).invoke(answer);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String errorString(Object answer, String key) {
        return errorValue(answer, key) instanceof String s ? s : null;
    }

    private static String name(Method m) { return m != null ? m.getName() : null; }

    private static String keyName(String key) { return key == null ? "?" : key.isEmpty() ? "default" : key; }

    private static Method countGetter(ClassLoader cl, String field) throws Throwable {
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(USER).paramCount(0).returnType("java.lang.Integer").usingNumbers(field.hashCode())))) {
            Method m = md.getMethodInstance(cl);
            if (!Modifier.isStatic(m.getModifiers())) return m;
        }
        return null;
    }

    private static void findPager(ClassLoader cl, Class<?> fragClass) throws Throwable {
        for (Class<?> c = fragClass; c != null && pagerField == null; c = c.getSuperclass())
            for (Field f : SafeReflect.fields(c)) {
                try {
                    Method il = f.getType().getDeclaredMethod("isLoading");
                    if (il.getReturnType() != boolean.class) continue;
                    Field maxId = null;
                    int strings = 0;
                    for (Field sf : SafeReflect.fields(f.getType()))
                        if (sf.getType() == String.class && !Modifier.isStatic(sf.getModifiers())) { maxId = sf; strings++; }
                    if (strings != 1) continue;
                    maxId.setAccessible(true);
                    f.setAccessible(true);
                    il.setAccessible(true);
                    nextMaxIdField = maxId;
                    pagerIsLoading = il;
                    pagerField = f;
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
        if (pagerField == null) return;
        for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .declaredClass(pagerField.getType().getName()).paramCount(0).returnType("void")
                .addInvoke(MethodMatcher.create().name("getLifecycle"))))) {
            pagerLoadMore = md.getMethodInstance(cl);
            break;
        }
        if (pagerLoadMore == null) return;
        Method fetch = null;
        int others = 0;
        for (Method m : SafeReflect.methods(pagerField.getType())) {
            if (m.getParameterCount() != 0 || m.getReturnType() != void.class || Modifier.isStatic(m.getModifiers())
                    || m.getDeclaringClass() != pagerField.getType() || m.equals(pagerLoadMore)) continue;
            fetch = m;
            others++;
        }
        if (others == 1) {
            fetch.setAccessible(true);
            pagerFetch = fetch;
        }
    }

    static boolean isLoading() { return current != null; }

    static void stop() {
        Load l = current;
        if (l != null) {
            l.stopped = true;
            l.poke();
        }
    }

    static boolean loadAll(View root, Object frag, CharSequence title, CharSequence stopLabel, Consumer<Result> done) {
        if (current != null) return false;
        Object pager = null;
        try { if (pagerField != null) pager = pagerField.get(frag); } catch (Throwable ignored) {}
        FollowListExportHook.ListInfo info = FollowListExportHook.listInfo(frag);
        int expected = expectedCount(frag, info);
        Load l = new Load(root, frag, pager, info, expected, done);
        current = l;
        if (pager == null || pagerLoadMore == null) {
            ModuleLog.error(TAG + "Could not load the list: the list loader was not found in this Instagram version");
            ModuleLog.detail(TAG + "no pager (pager=" + (pager != null) + ", loadMore=" + (pagerLoadMore != null) + ")");
            l.finish(false, "no pager");
            return true;
        }
        l.shown = progressDialog(root.getContext(), title, stopLabel);
        l.begin();
        return true;
    }

    static int accountsShown(View root) {
        try {
            AbsListView lv = FollowListExportHook.findList(root);
            Object ad = adapterOf(lv);
            if (ad == null) return 0;
            int best = 0;
            for (Field f : listFields(ad.getClass())) {
                Object v = f.get(ad);
                if (v instanceof List<?> l && holdsUsers(l)) best = Math.max(best, l.size());
            }
            return best;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static final String PAUSE_PREFS = "instaeclipse_follow_pause";

    private static SharedPreferences pausePrefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PAUSE_PREFS, Context.MODE_PRIVATE);
    }

    private static String pauseKey(String self) {
        return (self != null ? self : "_") + ".";
    }

    static void warmPause(Context ctx) {
        try { pausePrefs(ctx); } catch (Throwable ignored) {}
    }

    static FollowListPolicy.Block pause(Context ctx, String self) {
        try {
            SharedPreferences p = pausePrefs(ctx);
            String k = pauseKey(self);
            FollowListPolicy.Block b = new FollowListPolicy.Block(FollowListPolicy.Failure.valueOf(p.getString(k + "kind", "")),
                    p.getString(k + "message", null), p.getLong(k + "at", 0), p.getLong(k + "until", 0));
            return b.pausing(System.currentTimeMillis()) ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void savePause(Context ctx, String self, FollowListPolicy.Block b) {
        try {
            String k = pauseKey(self);
            pausePrefs(ctx).edit().putString(k + "kind", b.kind.name()).putString(k + "message", b.message)
                    .putLong(k + "at", b.at).putLong(k + "until", b.until).apply();
            ModuleLog.line(TAG + "Instagram blocked list loading (" + b.kind + "), so loading lists pauses for " + (self != null ? self : "this account")
                    + (b.until == Long.MAX_VALUE ? " until a list loads fine again" : " until " + new Date(b.until)));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "save pause: " + t);
        }
    }

    private static void clearPause(Context ctx, String self) {
        try {
            SharedPreferences p = pausePrefs(ctx);
            String k = pauseKey(self);
            if (!p.contains(k + "kind")) return;
            p.edit().remove(k + "kind").remove(k + "message").remove(k + "at").remove(k + "until").apply();
            ModuleLog.line(TAG + "A list loaded fine, so the pause after Instagram's block ended for " + (self != null ? self : "this account"));
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "clear pause: " + t);
        }
    }

    static String blockText(Context ctx, FollowListPolicy.Block b, boolean stopped) {
        StringBuilder sb = new StringBuilder(I18n.t(ctx, switch (b.kind) {
            case RATE_LIMIT -> R.string.ig_follow_block_rate;
            case ACTION_BLOCK -> R.string.ig_follow_block_action;
            default -> R.string.ig_follow_block_challenge;
        }));
        if (b.message != null) sb.append("\n\n").append(I18n.t(ctx, R.string.ig_follow_block_said, b.message));
        if (stopped) sb.append("\n\n").append(I18n.t(ctx, R.string.ig_follow_block_stopped));
        if (b.until > 0 && b.until != Long.MAX_VALUE)
            sb.append(stopped ? "\n" : "\n\n").append(I18n.t(ctx, R.string.ig_follow_block_wait,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(b.until))));
        return sb.toString();
    }

    static void showBlocked(Context ctx, FollowListPolicy.Block b) {
        try {
            new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_follow_block_title)).message(blockText(ctx, b, true)).show();
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show why Instagram stopped the list loading", t);
        }
    }

    static void warnPaused(Context ctx, FollowListPolicy.Block b, Runnable run) {
        try {
            IeDialog d = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_follow_block_title)).message(blockText(ctx, b, false));
            d.secondary(I18n.t(ctx, R.string.ig_dialog_cancel), () -> {});
            d.primary(I18n.t(ctx, R.string.ig_follow_block_anyway), run);
            d.show();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the pause warning, so the list was not loaded", t);
        }
    }

    private static final class Load implements Runnable {
        final View root;
        final Object frag, pager;
        final FollowListExportHook.ListInfo info;
        final int expected;
        final Consumer<Result> done;
        final long started = SystemClock.uptimeMillis();
        Progress shown;
        AbsListView list;
        Object adapter;
        DataSetObserver observer;
        Field[] lists;
        int[] sizes, growth;
        boolean walked;
        boolean walkCut;
        final Map<String, String[]> names = new HashMap<>();
        final FollowListPolicy.IncrementalIds listed = new FollowListPolicy.IncrementalIds();
        final Set<String> cursors = new HashSet<>();
        String cursor;
        int lastUnique = -1, pages, retries, retriesTotal;
        long lastProgressAt, lastAskAt, lastFetchAt, retryAt, lastWalkAt;
        volatile boolean stopped;
        boolean finished;
        int pagesSeen, pagesLimited, usersSeen, hiddenFollowing;
        boolean limited;
        int pass, passPages;
        int maxPasses = FollowListPolicy.MAX_PASSES;
        final boolean own;
        boolean relist;
        boolean passRelist;
        int cycled;
        boolean orderChanged;
        volatile boolean noRefresh;
        Object firstOrder;
        String firstOrderName;
        String passOrderName;
        String passKey;
        long passStarted;
        boolean passPageSeen, passMerged;
        boolean fromStart;
        FollowListPolicy.PassTally tally = new FollowListPolicy.PassTally();
        FollowListPolicy.Counts firstCounts;
        int firstReceived = -1, firstRepeated = -1;
        int maxReceived;
        int passesDone, lastAdded = -1;
        boolean interrupted;
        final Map<String, String[]> merged = new LinkedHashMap<>();
        final Map<String, Integer> hits = new HashMap<>();
        final StringBuilder passLog = new StringBuilder();
        volatile int requestLogged = -1;
        volatile int passRequests;
        volatile boolean stalePage;
        boolean staleRetried;
        FollowListPolicy.Failure lastFailure;
        FollowListPolicy.Block blocked;

        Load(View root, Object frag, Object pager, FollowListExportHook.ListInfo info, int expected, Consumer<Result> done) {
            this.root = root;
            this.frag = frag;
            this.pager = pager;
            this.info = info;
            this.expected = expected;
            this.done = done;
            lastProgressAt = started;
            limited = limitedLists.containsKey(frag);
            own = info != null && info.owner != null && info.owner.equals(info.self);
        }

        void begin() {
            list = FollowListExportHook.findList(root);
            adapter = adapterOf(list);
            if (adapter != null) {
                lists = listFields(adapter.getClass()).toArray(new Field[0]);
                sizes = new int[lists.length];
                growth = new int[lists.length];
                for (int i = 0; i < lists.length; i++) sizes[i] = sizeOf(lists[i], adapter);
            }
            try {
                Adapter a = list != null ? list.getAdapter() : null;
                if (a != null) {
                    observer = new DataSetObserver() {
                        @Override public void onChanged() { poke(); }
                        @Override public void onInvalidated() { poke(); }
                    };
                    a.registerDataSetObserver(observer);
                }
            } catch (Throwable t) {
                observer = null;
                ModuleLog.detail(TAG + "observer: " + t);
            }
            Object order = currentOrder();
            passOrderName = order instanceof Enum<?> o ? o.name() : null;
            passKey = order != null ? orderValue(order) : null;
            if (pageCacheFields == null) maxPasses = FollowListPolicy.MAX_PASSES_WITHOUT_REPEATS;
            ModuleLog.detail(TAG + "load " + label() + ": " + (lists != null ? lists.length : 0) + " list fields, observer="
                    + (observer != null) + ", expected=" + expected + ", limited before load=" + limited
                    + ", order=" + (passOrderName != null ? passOrderName : "?") + (own ? ", your own list" : ""));
            if (!startOver()) poke();
        }

        private boolean startOver() {
            Object order = currentOrder();
            String why = info == null || info.type == null ? "not a followers / following list"
                    : listBuilders.isEmpty() ? "no request hooks" : sortMethod == null || order == null
                    ? "no sort handler" : pageCacheFields == null ? "no page caches" : lists == null || lists.length == 0 ? "no account list" : null;
            if (why == null) {
                fromStart = true;
                passStarted = lastProgressAt = SystemClock.uptimeMillis();
                passRequests = 0;
                if (listFromStart(order)) {
                    ModuleLog.detail(TAG + label() + ": first pass listed again from its first page (order "
                            + passOrderName + ", page caches emptied)");
                    poke();
                    return true;
                }
                fromStart = false;
                why = "sort failed";
            }
            ModuleLog.detail(TAG + label() + ": first pass goes on from the pages on screen (" + why
                    + "): those may come from Instagram's response cache");
            return false;
        }

        private boolean listFromStart(Object order) {
            boolean cleared = clearPageCaches(frag);
            Object before = currentOrder();
            try {
                if (order == before) {
                    if (!cleared) return false;
                    Object other = null;
                    for (Object o : orderField.getType().getEnumConstants()) if (o != order) { other = o; break; }
                    if (other == null) return false;
                    orderField.set(frag, other);
                }
                sortMethod.invoke(frag, order);
                return true;
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "sort " + orderValue(order) + ": " + (t.getCause() != null ? t.getCause() : t));
                try { if (currentOrder() != before && before != null) orderField.set(frag, before); } catch (Throwable ignored) {}
                return false;
            }
        }

        void requestBuilt(boolean refresh) {
            passRequests++;
            if (!refresh) noRefresh = true;
            int p = pass;
            if (requestLogged == p) return;
            requestLogged = p;
            ModuleLog.detail(TAG + (refresh ? "page requests skip Instagram's response cache for " : "no refresh flag on the page requests for ")
                    + label() + " (pass " + (p + 1) + ")");
        }

        private boolean refreshHooked() {
            for (Object[] b : listBuilders.values()) if (info != null && b[0].equals(info.type) && (int) b[1] >= 0) return true;
            return false;
        }

        void requestFailed(FollowListPolicy.Failed f, String key) {
            if (finished) return;
            FollowListPolicy.Failure kind = FollowListPolicy.classify(f);
            ModuleLog.detail(TAG + label() + ": page request failed (pass " + (pass + 1) + ", order " + keyName(key) + "): " + kind
                    + (f.exception ? ", " + f.message : (f.status >= 0 ? ", status " + f.status : "")
                    + (f.errorType != null ? ", error_type " + f.errorType : "")
                    + (f.message != null ? ", message \"" + f.message + "\"" : "")
                    + (f.feedbackMessage != null ? ", feedback_message \"" + f.feedbackMessage + "\"" : "")
                    + (f.checkpointUrl ? ", checkpoint_url set" : "") + (f.challenge ? ", challenge set" : "")
                    + (f.checkpointRequired ? ", checkpoint_required" : "") + (f.loginRequired ? ", login_required" : "")));
            if (!kind.blocks()) {
                lastFailure = kind;
                poke();
                return;
            }
            long now = System.currentTimeMillis();
            blocked = new FollowListPolicy.Block(kind, FollowListPolicy.shownMessage(f.message, f.feedbackMessage), now,
                    FollowListPolicy.pauseUntil(kind, now));
            savePause(root.getContext(), info != null ? info.self : null, blocked);
            finish(false, "blocked by Instagram (" + kind + ")");
        }

        boolean ownsRequest(String type, Object[] args) {
            if (finished || info == null || info.owner == null || !type.equals(info.type)) return false;
            for (Object a : args) if (info.owner.equals(a)) return true;
            return false;
        }

        private boolean hooked() {
            for (Object[] b : listBuilders.values()) if (info != null && b[0].equals(info.type)) return true;
            return false;
        }

        void poke() {
            H.removeCallbacks(this);
            H.post(this);
        }

        @Override public void run() {
            H.removeCallbacks(this);
            if (finished) return;
            long now = SystemClock.uptimeMillis();
            if (stopped) { finish(false, "stopped"); return; }
            if (!root.isAttachedToWindow()) { finish(false, "left the list"); return; }
            if (stalePage) {
                stalePage = false;
                staleRetried = true;
                ModuleLog.detail(TAG + label() + ": a page asked for before the first pass started over came in, listing it again");
                passPageSeen = false;
                passRequests = 0;
                tally = new FollowListPolicy.PassTally();
                passStarted = lastProgressAt = now;
                cursor = null;
                cursors.clear();
                lastUnique = -1;
                if (!listFromStart(currentOrder())) ModuleLog.detail(TAG + label() + ": could not list the first pass again");
            }
            read(now, false);
            String next;
            try {
                next = (String) nextMaxIdField.get(pager);
            } catch (Throwable t) {
                finish(false, "pager: " + t);
                return;
            }
            boolean newPage = !Objects.equals(next, cursor);
            if (newPage || listed.unique() != lastUnique) {
                if (newPage && lastUnique >= 0) { pages++; passPages++; }
                if (newPage && next != null && !cursors.add(next)) {
                    endPass(false, "Instagram repeated a page");
                    return;
                }
                cursor = next;
                lastUnique = listed.unique();
                lastProgressAt = now;
                retries = 0;
                retryAt = 0;
                lastFailure = null;
            }
            boolean failed = next != null && failed();
            if (next == null) {
                FollowListPolicy.End end = !fromStart ? FollowListPolicy.atEnd(listed.unique(), expected, now - started)
                        : FollowListPolicy.passAtEnd(listed.unique(), passPageSeen || expected == 0, now - passStarted);
                if (end == FollowListPolicy.End.COMPLETE) { endPass(true, "end"); return; }
                if (end == FollowListPolicy.End.INCOMPLETE) { endPass(false, "no first page"); return; }
            } else if (retryAt == 0 && !failed && (newPage || now - lastAskAt >= FollowListPolicy.REASK_MS)) {
                ask(pagerLoadMore, now);
            }
            boolean failedAgain = failed && now - lastFetchAt >= FollowListPolicy.REASK_MS + 500;
            boolean passFirstPage = fromStart && next == null;
            if (retryAt == 0 && !passFirstPage && (failedAgain || now - lastProgressAt >= FollowListPolicy.STALL_MS)) {
                FollowListPolicy.Failure cause = lastFailure;
                long wait = FollowListPolicy.retryDelayMs(retries + 1, cause);
                if (wait < 0) {
                    endPass(false, "page failed after " + retries + " retries" + (cause != null ? " (" + cause + ")" : ""));
                    return;
                }
                retries++;
                retriesTotal++;
                retryAt = now + wait;
                ModuleLog.detail(TAG + label() + ": " + (failedAgain ? "page failed" + (cause != null ? " (" + cause + ")" : "")
                        : "no page for " + (now - lastProgressAt) / 1000 + " s") + ", retry " + retries + " in " + wait / 1000 + " s");
            }
            if (retryAt != 0 && now >= retryAt) {
                retryAt = 0;
                lastProgressAt = now;
                lastFetchAt = now;
                ask(pagerFetch != null ? pagerFetch : pagerLoadMore, now);
            }
            progress(now);
            H.postDelayed(this, TICK_MS);
        }

        private void endPass(boolean ok, String why) {
            if (pass == 0) {
                if (!ok || walkCut) { finish(false, why); return; }
                read(SystemClock.uptimeMillis(), true);
                firstCounts = listed.counts();
                for (String id : firstCounts.ids) merged.put(id, nameOf(id));
                countHits(firstCounts.ids);
                passesDone = 1;
                FollowListPolicy.PassTally t = tally;
                if (t.pages > 0) {
                    firstReceived = t.received;
                    firstRepeated = t.repeated;
                    maxReceived = t.received;
                }
                ModuleLog.detail(TAG + label() + ": pass 1 (order " + keyName(passKey) + "): " + tallyText()
                        + "; " + firstCounts.unique + " listed, " + passRequests + " requests");
                int passPagesSeen = pageField != null ? t.pages : pages + 1;
                if (!limited && limitFlagMissing() && FollowListPolicy.limitedByShape(true, passPagesSeen, firstCounts.unique, expected)) {
                    limited = true;
                    ModuleLog.detail(TAG + label() + ": no limit flag readable, but " + firstCounts.unique + " of " + expected + " in "
                            + passPagesSeen + " pages looks capped: limited");
                }
                Object first = currentOrder();
                if (sortMethod == null || first == null || walked || !FollowListPolicy.startExtraPasses(true, limited,
                        merged.size(), expected, firstReceived, firstRepeated)) {
                    if (limited && FollowListPolicy.startExtraPasses(true, false, merged.size(), expected, firstReceived, firstRepeated))
                        ModuleLog.detail(TAG + label() + ": limited by Instagram, so no extra passes");
                    finish(true, why);
                    return;
                }
                firstOrder = first;
                firstOrderName = ((Enum<?>) first).name();
                relist = FollowListPolicy.relistPasses(own, pageCacheFields != null, refreshHooked()) && passRequests > 0 && !noRefresh;
                ModuleLog.detail(TAG + label() + ": extra passes (" + (maxPasses - 1) + " at most) " + (relist
                        ? "in the same order again (someone else's list: Instagram ignores the order there)"
                        : "in the other orders (" + (own ? "your own list" : pageCacheFields == null ? "no page caches, no order listed twice"
                        : !refreshHooked() ? "no request hook with a refresh flag" : noRefresh ? "a page request without the refresh flag"
                        : "no page request of the first pass seen") + ")"));
                startPass(1);
                return;
            }
            int before = merged.size();
            int added = mergePass(ok ? "complete" : "failed: " + why);
            lastAdded = added;
            if (!ok) interrupted = true;
            boolean fellShort = false;
            if (ok && passRelist) {
                double predicted = FollowListPolicy.predictedNew(target(), before, listed.unique());
                if (FollowListPolicy.relistFellShort(predicted, added)) {
                    fellShort = true;
                    relist = false;
                    ModuleLog.detail(TAG + label() + ": the same order again added " + added + " of about " + Math.round(predicted)
                            + " predicted, so the next passes switch order");
                }
            }
            if (relist && noRefresh) {
                relist = false;
                ModuleLog.detail(TAG + label() + ": a page request went out without the refresh flag, so the next passes switch order");
            }
            if (ok && FollowListPolicy.anotherPass(pass + 1, maxPasses, merged.size(), target(), added, fellShort, budgetRows())) {
                startPass(pass + 1);
                return;
            }
            finish(true, why);
        }

        private int target() {
            return FollowListPolicy.target(expected, maxReceived);
        }

        private int budgetRows() {
            return Math.max(Math.max(maxReceived, expected), firstCounts != null ? firstCounts.rows : listed.rows());
        }

        private void countHits(List<String> ids) {
            for (String id : ids) hits.merge(id, 1, Integer::sum);
        }

        private boolean limitFlagMissing() {
            if (info != null && FollowListExportHook.FOLLOWING.equals(info.type)) return pageLimitedFollowing == null;
            return pageLimited == null && responseTypes.isEmpty();
        }

        private void startPass(int n) {
            pass = n;
            passPages = 0;
            passPageSeen = false;
            passMerged = false;
            fromStart = true;
            passRequests = 0;
            tally = new FollowListPolicy.PassTally();
            passStarted = lastProgressAt = SystemClock.uptimeMillis();
            cursor = null;
            cursors.clear();
            lastUnique = -1;
            retries = 0;
            retryAt = 0;
            lastFailure = null;
            passRelist = relist;
            passOrderName = relist ? firstOrderName : FollowListPolicy.passOrder(firstOrderName, ++cycled);
            Object order = relist ? firstOrder : order(orderField.getType(), passOrderName);
            if (order != null && order != firstOrder) orderChanged = true;
            passKey = order != null ? orderValue(order) : passOrderName;
            ModuleLog.detail(TAG + label() + ": extra pass " + n + " of up to " + (maxPasses - 1) + ", order " + keyName(passKey)
                    + (relist ? " again" : "") + " (listed " + merged.size() + " of " + target() + ")");
            if (order == null || !listFromStart(order)) {
                passMerged = true;
                passLog.append(passLog.length() > 0 ? ", " : "").append(keyName(passKey)).append(" not set");
                finish(true, "sort failed");
                return;
            }
            poke();
        }

        private int mergePass(String how) {
            if (passMerged) return 0;
            passMerged = true;
            read(SystemClock.uptimeMillis(), true);
            List<String> passIds = listed.ids();
            List<String> fresh = FollowListPolicy.newIds(merged.keySet(), passIds);
            for (String id : fresh) merged.put(id, nameOf(id));
            countHits(passIds);
            passesDone++;
            maxReceived = Math.max(maxReceived, tally.received);
            ModuleLog.detail(TAG + label() + ": order " + keyName(passKey) + (passRelist ? " again" : "") + ": +" + fresh.size()
                    + " new (" + listed.unique() + " listed in this pass, " + passPages + " pages, " + passRequests + " requests, "
                    + how + ") | pass " + (pass + 1) + ": " + tallyText());
            passLog.append(passLog.length() > 0 ? ", " : "").append(keyName(passKey)).append(passRelist ? " again" : "")
                    .append(" +").append(fresh.size()).append(how.equals("complete") ? "" : " (" + how + ")");
            return fresh.size();
        }

        private String tallyText() {
            FollowListPolicy.PassTally t = tally;
            return "received " + t.received + ", distinct " + t.distinct() + ", repeated " + t.repeated + " in " + t.pages
                    + " pages" + (t.withoutId > 0 ? ", " + t.withoutId + " without id" : "");
        }

        private void restoreOrder() {
            if (firstOrder == null || sortMethod == null) return;
            try {
                sortMethod.invoke(frag, firstOrder);
                ModuleLog.detail(TAG + label() + ": order set back to " + ((Enum<?>) firstOrder).name());
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not set the list back to its first order: " + (t.getCause() != null ? t.getCause() : t));
            }
        }

        private Object currentOrder() {
            try {
                return orderField != null ? orderField.get(frag) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private String[] nameOf(String id) {
            String[] n = names.get(id);
            return n != null ? n : new String[2];
        }

        void page(Object step) throws Exception {
            if (finished || !ownsList(pageOwnerField.get(step))) {
                rememberLimited(step);
                return;
            }
            Object page = pageField.get(step);
            String key = pageKeyField != null ? (String) pageKeyField.get(step) : null;
            Object limit = call(pageLimited, page);
            Object users = call(pageUsers, page);
            if (limit == NOT_READ) limit = responseLimit(page);
            boolean following = info != null && FollowListExportHook.FOLLOWING.equals(info.type);
            Object limitFollowing = following ? call(pageLimitedFollowing, page) : null;
            Object hidden = following ? call(pageHiddenFollowing, page) : null;
            int n = users instanceof List<?> l ? l.size() : -1;
            pagesSeen++;
            if (n > 0) usersSeen += n;
            if (Boolean.TRUE.equals(limit) || Boolean.TRUE.equals(limitFollowing)) {
                pagesLimited++;
                limited = true;
            }
            if (hidden instanceof Integer h && h > hiddenFollowing) hiddenFollowing = h;
            boolean keyed = key == null || passKey == null || passKey.equals(key);
            boolean ofPass = keyed && (!fromStart || passRequests > 0 || !hooked());
            if (fromStart && keyed && pass == 0 && !staleRetried && passRequests == 0 && hooked()) stalePage = true;
            if (fromStart && ofPass) passPageSeen = true;
            FollowListPolicy.PassTally t = tally;
            int[] c = null;
            if (users instanceof List<?> l && ofPass) {
                List<String> ids = new ArrayList<>(l.size());
                for (Object u : l) ids.add(u != null && u.getClass().getName().equals(USER) ? idOf(u) : null);
                c = t.page(ids);
            }
            if (!ModuleLog.verbose) return;
            String tallied = "";
            if (c != null) {
                tallied = " | pass " + (pass + 1) + ": " + c[FollowListPolicy.P_NEW] + " new, " + c[FollowListPolicy.P_REPEATED]
                        + " repeated" + (c[FollowListPolicy.P_REPEATED] > 0 ? " (" + repeatsBack(c) + ")" : "")
                        + " (received " + t.received + ", distinct " + t.distinct() + ")";
            } else if (users instanceof List<?>) {
                tallied = " | not counted (" + (keyed ? "before this pass's first request" : "another order's page") + ")";
            }
            ModuleLog.probe(TAG + "page " + pagesSeen + " " + label() + " (order " + keyName(key)
                    + "): " + (n >= 0 ? String.valueOf(n) : "?") + " users, should_limit=" + limit
                    + (following ? ", should_limit_followings=" + limitFollowing + ", hidden_following=" + hidden : "")
                    + tallied);
        }

        private String repeatsBack(int[] c) {
            return "1 back " + c[FollowListPolicy.P_BACK1] + ", 2 back " + c[FollowListPolicy.P_BACK2] + ", 3-5 back "
                    + c[FollowListPolicy.P_BACK3_5] + ", older " + c[FollowListPolicy.P_OLDER]
                    + (c[FollowListPolicy.P_SAME] > 0 ? ", same page " + c[FollowListPolicy.P_SAME] : "");
        }

        private boolean ownsList(Object owner) {
            if (owner == null) return false;
            if (owner == frag) return true;
            try {
                Field f = ownerFragField;
                if (f != null && f.getDeclaringClass().isInstance(owner)) return f.get(owner) == frag;
                for (Field c : SafeReflect.fields(owner.getClass())) {
                    if (Modifier.isStatic(c.getModifiers()) || c.getType().isPrimitive()) continue;
                    c.setAccessible(true);
                    if (c.get(owner) == frag) {
                        ownerFragField = c;
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        }

        private boolean failed() {
            try {
                return pagerIsLoading != null && !(boolean) pagerIsLoading.invoke(pager);
            } catch (Throwable t) {
                return false;
            }
        }

        private void ask(Method m, long now) {
            lastAskAt = now;
            try {
                m.invoke(pager);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + m.getName() + ": " + (t.getCause() != null ? t.getCause() : t));
            }
        }

        private void read(long now, boolean last) {
            if (lists != null && lists.length > 0) {
                listed.read(mainList(), this::rowId);
                walked = false;
                return;
            }
            if (adapter == null || (!last && now - lastWalkAt < 2000)) return;
            lastWalkAt = now;
            boolean[] cut = new boolean[1];
            Map<String, String[]> found = walk(adapter, cut);
            names.putAll(found);
            listed.read(new ArrayList<>(found.keySet()), id -> id);
            walked = true;
            walkCut = cut[0];
        }

        private String rowId(Object u) {
            String id = u != null && u.getClass().getName().equals(USER) ? idOf(u) : null;
            if (id != null && !names.containsKey(id)) names.put(id, new String[]{username(u), fullName(u)});
            return id;
        }

        private List<?> mainList() {
            List<List<?>> users = new ArrayList<>();
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < lists.length; i++) {
                Object v;
                try { v = lists[i].get(adapter); } catch (Throwable t) { continue; }
                int n = v instanceof List<?> l ? l.size() : 0;
                if (n > sizes[i]) growth[i]++;
                sizes[i] = n;
                if (v instanceof List<?> l && holdsUsers(l)) {
                    users.add(l);
                    idx.add(i);
                }
            }
            if (users.isEmpty()) return Collections.emptyList();
            int[] s = new int[users.size()], g = new int[users.size()];
            for (int i = 0; i < s.length; i++) {
                s[i] = users.get(i).size();
                g[i] = growth[idx.get(i)];
            }
            return users.get(FollowListPolicy.mainList(s, g));
        }

        private void progress(long now) {
            if (shown == null) return;
            try {
                Context c = shown.dlg.ctx;
                int fresh = pass > 0 ? listed.fresh(merged.keySet()) : 0;
                int n = pass > 0 ? merged.size() + fresh : listed.unique();
                String msg = expected > 0 ? I18n.t(c, R.string.ig_follow_export_loading_of, n, expected)
                        : I18n.t(c, R.string.ig_follow_export_loading, n);
                if (pass > 0) msg += "\n\n" + I18n.t(c, R.string.ig_follow_export_pass, pass, maxPasses - 1,
                        I18n.t(c, FollowListPolicy.ORDER_EARLIEST.equals(passOrderName) ? R.string.ig_follow_export_order_earliest
                                : FollowListPolicy.ORDER_LATEST.equals(passOrderName) ? R.string.ig_follow_export_order_latest
                                : R.string.ig_follow_export_order_default), fresh);
                if (retryAt != 0) msg += "\n\n" + I18n.t(c, R.string.ig_follow_export_retry,
                        (int) Math.max(1, (retryAt - now + 999) / 1000));
                shown.show(msg, n, expected, FollowListPolicy.determinateProgress(pass, limited, expected));
            } catch (Throwable ignored) {}
        }

        void finish(boolean ok, String why) {
            if (finished) return;
            finished = true;
            if (current == this) current = null;
            H.removeCallbacks(this);
            try {
                if (observer != null && list != null && list.getAdapter() != null) list.getAdapter().unregisterDataSetObserver(observer);
            } catch (Throwable ignored) {}
            try { if (shown != null) shown.dlg.dismiss(); } catch (Throwable ignored) {}
            if (adapter == null) {
                list = FollowListExportHook.findList(root);
                adapter = adapterOf(list);
                if (adapter != null) lists = listFields(adapter.getClass()).toArray(new Field[0]);
                if (lists != null) { sizes = new int[lists.length]; growth = new int[lists.length]; }
            }
            Map<String, String[]> users = new LinkedHashMap<>();
            FollowListPolicy.Counts c;
            boolean complete;
            if (pass > 0) {
                if (!passMerged) {
                    interrupted = true;
                    try { lastAdded = mergePass(stopped ? "stopped" : why); } catch (Throwable t) { ModuleLog.detail(TAG + "read: " + t); }
                }
                users.putAll(merged);
                c = firstCounts;
                complete = blocked == null;
                if (orderChanged) restoreOrder();
            } else {
                try { read(SystemClock.uptimeMillis(), true); } catch (Throwable t) { ModuleLog.detail(TAG + "read: " + t); }
                c = listed.counts();
                for (String id : c.ids) users.put(id, nameOf(id));
                complete = ok && !stopped && !walkCut && blocked == null;
                if (passesDone == 0) {
                    countHits(c.ids);
                    passesDone = 1;
                }
                if (firstReceived < 0 && tally.pages > 0) {
                    firstReceived = tally.received;
                    firstRepeated = tally.repeated;
                }
            }
            int[] f = FollowListPolicy.passHits(hits.values(), passesDone);
            int f1 = f.length > 1 ? f[1] : 0, f2 = f.length > 2 ? f[2] : 0;
            double estMissing = FollowListPolicy.missingEstimate(f1, f2, passesDone);
            if (passesDone > 1) {
                StringBuilder fs = new StringBuilder();
                for (int k = 1; k < f.length; k++) fs.append(k > 1 ? ", " : "").append('f').append(k).append('=').append(f[k]);
                ModuleLog.detail(TAG + label() + ": accounts by how many of the " + passesDone + " passes listed them: " + fs
                        + "; Chao1 estimate of accounts no pass listed: " + Math.round(estMissing * 10) / 10.0);
            }
            ModuleLog.detail(TAG + "loaded " + label() + ": " + users.size() + " accounts (" + c.rows + " rows, "
                    + c.duplicates + " repeated, " + c.withoutId + " without id"
                    + (walked ? ", adapter walk" + (walkCut ? " cut at its cap" : "") : "") + ")"
                    + (expected >= 0 ? ", Instagram counts " + expected : "") + ", " + pages + " pages, "
                    + retriesTotal + " retries, " + (SystemClock.uptimeMillis() - started) / 100 / 10.0 + " s, "
                    + (complete ? "complete" + (interrupted ? " (an extra pass ended early)" : "") : "unfinished: " + (walkCut ? "walk cut" : why))
                    + " | pages seen " + pagesSeen + ", limited " + pagesLimited + (limited ? " (limited)" : "")
                    + (hiddenFollowing > 0 ? ", hidden following " + hiddenFollowing : "") + ", users " + usersSeen
                    + (pass == 0 && firstCounts == null ? " | pass 1 so far: " + tallyText() : "")
                    + (passLog.length() > 0 ? " | extra passes: " + passLog : "")
                    + (blocked != null ? " | blocked: " + blocked : ""));
            if (complete) clearPause(root.getContext(), info != null ? info.self : null);
            try {
                done.accept(new Result(users, complete, blocked, interrupted, expected, limited,
                        hiddenFollowing, firstReceived, firstRepeated, Math.max(1, passesDone), passesDone > 1 ? lastAdded : -1,
                        f1, f2, estMissing));
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not finish after loading the list", t);
            }
        }

        private String label() {
            return (info != null ? info.type + " of " + info.owner : "list");
        }
    }

    private static Object adapterOf(AbsListView lv) {
        Adapter ad = lv != null ? lv.getAdapter() : null;
        if (ad instanceof HeaderViewListAdapter h) ad = h.getWrappedAdapter();
        return ad;
    }

    private static List<Field> listFields(Class<?> adapterClass) {
        List<Field> out = new ArrayList<>();
        for (Field f : SafeReflect.fields(adapterClass)) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) continue;
            try { f.setAccessible(true); out.add(f); } catch (Throwable ignored) {}
        }
        return out;
    }

    private static int sizeOf(Field f, Object adapter) {
        try {
            return f.get(adapter) instanceof List<?> l ? l.size() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean holdsUsers(List<?> l) {
        for (int i = 0, n = Math.min(l.size(), 3); i < n; i++) {
            Object o = l.get(i);
            if (o != null) return o.getClass().getName().equals(USER);
        }
        return false;
    }

    static String idOf(Object user) {
        Field f = userIdField;
        try {
            if (f == null) {
                for (Field c : SafeReflect.fields(user.getClass())) {
                    if (c.getType() != String.class || Modifier.isStatic(c.getModifiers()) || !Modifier.isFinal(c.getModifiers())) continue;
                    c.setAccessible(true);
                    if (c.get(user) instanceof String s && s.matches("\\d+")) { userIdField = f = c; break; }
                }
                if (f == null) return null;
            }
            return f.get(user) instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String username(Object user) {
        Method m = usernameGetter != null ? usernameGetter : UserUtils.userUsernameGetter;
        if (m == null) return null;
        try {
            return m.invoke(user) instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String fullName(Object user) {
        try {
            return fullNameGetter != null && fullNameGetter.invoke(user) instanceof String s && !s.isEmpty() ? s : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static int expectedCount(Object frag, FollowListExportHook.ListInfo info) {
        if (info == null || info.type == null) return -1;
        Method getter = FollowListExportHook.FOLLOWERS.equals(info.type) ? followerCountGetter : followingCountGetter;
        if (getter == null) return -1;
        if (FeatureFlags.profileSpoofer && info.owner.equals(info.self)
                && (FollowListExportHook.FOLLOWERS.equals(info.type) ? FeatureFlags.spoofFollowers : FeatureFlags.spoofFollowing) >= 0)
            return -1;
        try {
            for (Class<?> c = frag.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (Field f : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || !f.getType().getName().equals(USER)) continue;
                    f.setAccessible(true);
                    Object u = f.get(frag);
                    if (u == null || !info.owner.equals(idOf(u))) continue;
                    Object n = getter.invoke(u);
                    return n instanceof Integer i && i >= 0 ? i : -1;
                }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static Map<String, String[]> walk(Object adapter, boolean[] cut) {
        Map<String, String[]> out = new LinkedHashMap<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{adapter, 0, false});
        while (!q.isEmpty()) {
            if (seen.size() >= 20000) { cut[0] = true; break; }
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            boolean row = (boolean) e[2];
            if (o == null || !seen.add(o)) continue;
            if (o instanceof List<?> l) { for (Object it : l) q.add(new Object[]{it, d + 1, true}); continue; }
            if (o instanceof Map<?, ?> m) { for (Object it : m.values()) q.add(new Object[]{it, d + 1, true}); continue; }
            if (o.getClass().getName().equals(USER)) {
                if (!row) continue;
                String id = idOf(o);
                if (id != null) out.putIfAbsent(id, new String[]{username(o), fullName(o)});
                continue;
            }
            if (d >= 4) continue;
            String n = o.getClass().getName();
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
            for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class
                            || Context.class.isAssignableFrom(f.getType()) || View.class.isAssignableFrom(f.getType())) continue;
                    try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1, row}); } catch (Throwable ignored) {}
                }
        }
        return out;
    }

    static final class Progress {
        final IeDialog dlg;
        final ProgressBar bar;

        Progress(IeDialog dlg, ProgressBar bar) {
            this.dlg = dlg;
            this.bar = bar;
        }

        void show(CharSequence message, int loaded, int expected, boolean determinate) {
            dlg.message(message);
            if (bar.isIndeterminate() == determinate) bar.setIndeterminate(!determinate);
            if (!determinate) return;
            bar.setMax(expected);
            bar.setProgress(Math.min(loaded, expected));
        }
    }

    static Progress progressDialog(Context c, CharSequence title, CharSequence stopLabel) {
        try {
            Context ctx = c;
            while (!(ctx instanceof Activity) && ctx instanceof android.content.ContextWrapper w && w.getBaseContext() != null) ctx = w.getBaseContext();
            if (!(ctx instanceof Activity)) {
                Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                ctx = act != null ? act : c;
            }
            IeDialog d = new IeDialog(ctx)
                    .title(title)
                    .message(I18n.t(ctx, R.string.ig_follow_export_loading, 0))
                    .cancelable(false);
            ProgressBar bar = new ProgressBar(d.ctx, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            ColorStateList accent = ColorStateList.valueOf(d.accent);
            bar.setIndeterminateTintList(accent);
            bar.setProgressTintList(accent);
            d.view(bar);
            d.primary(stopLabel, FollowListLoader::stop);
            d.onClose(FollowListLoader::stop);
            d.show();
            return new Progress(d, bar);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show the loading progress; the list still loads", t);
            return null;
        }
    }
}
