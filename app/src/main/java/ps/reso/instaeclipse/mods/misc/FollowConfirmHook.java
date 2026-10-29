package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.FoundClass;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Ask before following. Instagram's follow manager (the class holding "follow_unknown" and
 * "last_follow_status") handles every Follow button: it first marks the user as followed, which
 * flips the button, then hands the action to one static sender (manager, User, action enum, ...)
 * that queues the request and sends it. A follow (action "create") is held at that sender behind a
 * dialog: Follow sends it unchanged, Cancel puts the user's follow status back with the manager's
 * own setter, so the button returns to "Follow" and nothing was sent. Unfollows pass untouched.
 */
public class FollowConfirmHook {

    private static final String TAG = "(InstaEclipse | FollowConfirm): ";
    private static final String USER = "com.instagram.user.model.User";
    private static final String SENDER_KEY = "FollowConfirm_sender_v1";
    private static final String SETTER_KEY = "FollowManagerSetStatus";
    private static final String RESET_KEY = "FollowConfirm_reset_v1";
    private static final String GETTER_KEY = "FollowConfirm_statusGetter_v1";
    private static final String EVENT_KEY = "FollowConfirm_statusEvent_v1";
    private static final String BUS_FACTORY_KEY = "FollowConfirm_busFactory_v1";
    private static final String BUS_POST_KEY = "FollowConfirm_busPost_v1";
    private static final String SUSPENDED_KEY = "FollowConfirm_suspended_v1";
    private static final String FAILURE_KEY = "FollowConfirm_failure_v1";
    private static final String RESUME_KEY = "FollowConfirm_resumeWith_v1";
    private static final String SUSPENDED = "COROUTINE_SUSPENDED";
    private static Object suspendedMarker;
    private static Constructor<?> failureCtor;
    private static Method resumeWith;
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The manager's (FollowStatus, User, boolean) setter, used to undo the flip on Cancel. */
    private static Method statusSetter;
    /** The manager's reset (profile model -> re-read status), found as its 1-arg method calling the setter. */
    private static Method resetMethod;
    /** User's current follow status (FollowStatus enum), read by the manager's setter. */
    private static Method statusGetter;
    private static int profileButtonId;
    private static boolean replayingClick;
    private static final String DATA_SOURCE = "com.instagram.user.follow.mvvm.data.FollowUserDataSourceImpl";

    public void install() {
        boolean cached = DexKitCache.isCacheValid();
        Method sender = null;
        if (cached) {
            sender = DexKitCache.loadMethod(SENDER_KEY, Module.hostClassLoader);
            statusSetter = DexKitCache.loadMethod(SETTER_KEY, Module.hostClassLoader);
        }
        if (sender == null) {
            try {
                List<Method> candidates = new ArrayList<>();
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(ClassMatcher.create().usingStrings("follow_unknown", "last_follow_status"))
                        .returnType("void")))) {
                    if (!md.isMethod()) continue;
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    Class<?>[] p = m.getParameterTypes();
                    if (Modifier.isStatic(m.getModifiers())) {
                        candidates.add(m);
                    } else if (statusSetter == null && p.length == 3 && p[0].isEnum()
                            && p[1].getName().equals(USER) && p[2] == boolean.class) {
                        statusSetter = m;
                    }
                }
                sender = FollowConfirmPolicy.pickSender(candidates, USER);
                if (sender == null) {
                    ModuleLog.error(TAG + "Could not turn on follow confirmation: no single follow action was found in this Instagram version");
                    return;
                }
                DexKitCache.saveMethod(SENDER_KEY, sender);
                if (statusSetter != null) DexKitCache.saveMethod(SETTER_KEY, statusSetter);
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not turn on follow confirmation", t);
                return;
            }
        }
        XposedBridge.hookMethod(sender, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.confirmFollows || REPLAYING.get() == Boolean.TRUE || !FollowConfirmPolicy.isFollow(param.args)) return;
                Activity act = UIHookManager.getCurrentActivity();
                if (act == null) return;
                param.setResult(null);
                Member method = param.method;
                Object[] args = param.args.clone();
                MAIN.post(() -> confirm(act, username(args), () -> send(method, args), () -> undo(method, args)));
            }
        });
        resetMethod = statusSetter != null && cached ? DexKitCache.loadMethod(RESET_KEY, Module.hostClassLoader) : null;
        if (statusSetter != null && resetMethod == null) {
            try {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(sender.getDeclaringClass().getName()).paramCount(1).returnType("void")
                        .addInvoke(MethodMatcher.create().declaredClass(sender.getDeclaringClass().getName())
                                .name(statusSetter.getName()).paramCount(3))))) {
                    if (!md.isMethod()) continue;
                    Method m = md.getMethodInstance(Module.hostClassLoader);
                    if (Modifier.isStatic(m.getModifiers()) || m.getParameterTypes()[0].getName().equals(USER)) continue;
                    resetMethod = m;
                    DexKitCache.saveMethod(RESET_KEY, m);
                    break;
                }
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "After Cancel, a profile's Follow button may show the wrong state until refreshed: " + t);
            }
        }
        hookDataSource(sender.getDeclaringClass());
        statusGetter = statusSetter != null && cached ? DexKitCache.loadMethod(GETTER_KEY, Module.hostClassLoader) : null;
        if (statusSetter != null && statusGetter == null) {
            try {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(statusSetter.getDeclaringClass().getName()).name(statusSetter.getName()).paramCount(3)))) {
                    for (MethodData c : md.data().getInvokes()) {
                        if (!c.isMethod() || !c.getClassName().equals(USER) || c.getParamCount() != 0) continue;
                        Method m = c.getMethodInstance(Module.hostClassLoader);
                        if (m.getReturnType() == statusSetter.getParameterTypes()[0]) { statusGetter = m; break; }
                    }
                }
                if (statusGetter != null) DexKitCache.saveMethod(GETTER_KEY, statusGetter);
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "A profile's Follow button is asked about only when the follow is sent: " + t);
            }
        }
        hookProfileButton();
        if (statusSetter != null) resolveStatusEvent(statusSetter.getParameterTypes()[0], cached);
        ModuleLog.ok(TAG + "Ready");
        ModuleLog.detail(TAG + "Hooked " + sender.getDeclaringClass().getName() + "." + sender.getName()
                + (statusSetter != null ? ", undo " + statusSetter.getName() : ", no undo")
                + (resetMethod != null ? ", reset " + resetMethod.getName() : ", no reset")
                + (statusGetter != null ? ", status " + statusGetter.getName() : ", no status"));
        FeatureStatusTracker.setHooked("ConfirmFollows");
    }

    /**
     * The profile header's Follow button goes through its own handler (the profile fragment), so it
     * is held at the tap: while you don't follow the profile yet (read from its User model), the tap
     * waits for the dialog and is replayed on Follow. Taps on "Following" / "Requested" pass.
     */
    private static void hookProfileButton() {
        XposedBridge.hookAllMethods(android.view.View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.confirmFollows || replayingClick) return;
                android.view.View v = (android.view.View) param.thisObject;
                if (profileButtonId == 0) {
                    if (v.getId() == android.view.View.NO_ID) return;
                    profileButtonId = v.getResources().getIdentifier("profile_header_follow_button", "id", v.getContext().getPackageName());
                    if (profileButtonId == 0) profileButtonId = -1;
                }
                if (v.getId() != profileButtonId) return;
                Activity act = activityOf(v.getContext());
                String username = ps.reso.instaeclipse.mods.ui.ProfileMenuHook.profileUsername(act);
                if (act == null || !FollowConfirmPolicy.holdsProfileTap(profileStatus(act, username))) return;
                param.setResult(true);
                MAIN.post(() -> confirm(act, username, () -> {
                    replayingClick = true;
                    REPLAYING.set(Boolean.TRUE);
                    try { v.performClick(); } finally { replayingClick = false; REPLAYING.remove(); }
                    ModuleLog.line(TAG + "Profile follow confirmed and sent");
                }, () -> ModuleLog.line(TAG + "Profile follow cancelled")));
            }
        });
    }

    private static Activity activityOf(android.content.Context c) {
        while (c instanceof android.content.ContextWrapper w) {
            if (c instanceof Activity a) return a;
            c = w.getBaseContext();
        }
        return UIHookManager.getCurrentActivity();
    }

    private static Object profileStatus(Activity act, String username) {
        Method get = statusGetter;
        if (get == null || username == null) return null;
        try {
            Object user = ps.reso.instaeclipse.mods.ui.ProfileMenuHook.findUser(act, username);
            return user != null ? get.invoke(user) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The profile header's follow: FollowUserDataSourceImpl's suspend method taking the action enum. */
    private static void hookDataSource(Class<?> managerClass) {
        try {
            Class<?> ds = Module.hostClassLoader.loadClass(DATA_SOURCE);
            for (Method m : SafeReflect.methods(ds)) {
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != Object.class || p.length < 5) continue;
                int ci = FollowConfirmPolicy.continuationIndex(p);
                if (ci < 0 || !FollowConfirmPolicy.hasEnum(p)) continue;
                boolean resumes = resolveCoroutine(p[ci]);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.confirmFollows || REPLAYING.get() == Boolean.TRUE || !FollowConfirmPolicy.isFollow(param.args)) return;
                        Activity act = UIHookManager.getCurrentActivity();
                        if (act == null) return;
                        Method method = (Method) param.method;
                        Object self = param.thisObject;
                        Object[] args = param.args.clone();
                        if (resumes) {
                            FollowConfirmPolicy.HeldCall held = hold(method, self, args, ci);
                            param.setResult(suspendedMarker);
                            MAIN.post(() -> confirm(act, ps.reso.instaeclipse.mods.ui.ProfileMenuHook.profileUsername(act), () -> answer(held, true),
                                    () -> { reset(self, args, managerClass); answer(held, false); }));
                            return;
                        }
                        param.setThrowable(new CancellationException(FollowConfirmPolicy.HELD));
                        MAIN.post(() -> confirm(act, ps.reso.instaeclipse.mods.ui.ProfileMenuHook.profileUsername(act), () -> resend(method, self, args),
                                () -> reset(self, args, managerClass)));
                    }
                });
                ModuleLog.detail(TAG + "profile follow: " + m.getName());
                return;
            }
            ModuleLog.warn(TAG + "Some Follow buttons may follow without asking: the profile follow step was not found in this Instagram version");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Some Follow buttons may follow without asking: " + t);
        }
    }

    private static boolean resolveCoroutine(Class<?> continuationType) {
        ClassLoader loader = Module.hostClassLoader;
        boolean cached = DexKitCache.isCacheValid();
        try {
            Method resume = cached ? DexKitCache.loadMethod(RESUME_KEY, loader) : null;
            if (resume == null) {
                resume = resumeMethod(continuationType);
                if (resume != null) DexKitCache.saveMethod(RESUME_KEY, resume);
            }
            Constructor<?> failure = cached ? DexKitCache.loadConstructor(FAILURE_KEY, loader) : null;
            if (failure == null) {
                for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .name("toString").usingEqStrings("Failure(")))) {
                    Constructor<?> c = failureConstructor(Class.forName(md.getClassName(), false, loader));
                    if (c == null) continue;
                    if (failure != null) {
                        failure = null;
                        break;
                    }
                    failure = c;
                }
                if (failure != null) DexKitCache.saveConstructor(FAILURE_KEY, failure);
            }
            String markerClass = cached ? DexKitCache.loadString(SUSPENDED_KEY) : null;
            if (markerClass == null) {
                for (FoundClass cd : DexKitQueryCache.classes(FindClass.create().matcher(ClassMatcher.create()
                        .superClass("java.lang.Enum").usingEqStrings(SUSPENDED)))) {
                    if (marker(Class.forName(cd.getName(), false, loader)) == null) continue;
                    markerClass = cd.getName();
                    DexKitCache.saveString(SUSPENDED_KEY, markerClass);
                    break;
                }
            }
            Object marker = markerClass != null ? marker(Class.forName(markerClass, false, loader)) : null;
            if (resume == null || failure == null || marker == null) {
                ModuleLog.warn(TAG + "Confirming a follow on a profile may not always follow (not supported in this Instagram version)");
                return false;
            }
            resume.setAccessible(true);
            failure.setAccessible(true);
            resumeWith = resume;
            failureCtor = failure;
            suspendedMarker = marker;
            return true;
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Confirming a follow on a profile may not always follow: " + t);
            return false;
        }
    }

    private static Method resumeMethod(Class<?> continuationType) {
        Method found = null;
        for (Method m : SafeReflect.methods(continuationType)) {
            if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || m.getReturnType() != void.class) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    private static Constructor<?> failureConstructor(Class<?> c) {
        Constructor<?>[] ctors = SafeReflect.constructors(c);
        if (!Serializable.class.isAssignableFrom(c) || ctors.length != 1) return null;
        Class<?>[] p = ctors[0].getParameterTypes();
        return p.length == 1 && p[0] == Throwable.class ? ctors[0] : null;
    }

    private static Object marker(Class<?> c) {
        Object[] constants = c.isEnum() ? c.getEnumConstants() : null;
        if (constants == null) return null;
        for (Object e : constants) if (((Enum<?>) e).name().equals(SUSPENDED)) return e;
        return null;
    }

    private static FollowConfirmPolicy.HeldCall hold(Method method, Object self, Object[] args, int ci) {
        Object continuation = args[ci];
        Method resume = resumeWith;
        Constructor<?> failure = failureCtor;
        return new FollowConfirmPolicy.HeldCall(() -> {
            REPLAYING.set(Boolean.TRUE);
            try {
                Object result = XposedBridge.invokeOriginalMethod(method, self, args);
                ModuleLog.line(TAG + "Profile follow confirmed and sent");
                return result;
            } catch (Throwable t) {
                ModuleLog.error(TAG + "Could not send the follow: " + FollowConfirmPolicy.HeldCall.cause(t));
                throw t;
            } finally {
                REPLAYING.remove();
            }
        }, result -> resume.invoke(continuation, result), cause -> failure.newInstance(cause), suspendedMarker);
    }

    private static void answer(FollowConfirmPolicy.HeldCall held, boolean follow) {
        try {
            if (follow) held.follow();
            else held.cancel();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not hand the follow back to Instagram: " + t);
        }
    }

    /** Runs the held call again; its result goes to a stand-in continuation that borrows the
     *  original's coroutine context. */
    private static void resend(Method method, Object self, Object[] args) {
        int ci = FollowConfirmPolicy.continuationIndex(method.getParameterTypes());
        Object original = args[ci];
        Class<?> type = method.getParameterTypes()[ci];
        Object standIn = java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "equals": return proxy == a[0];
                    case "hashCode": return System.identityHashCode(proxy);
                    default: return "InstaEclipse follow";
                }
            }
            if (m.getParameterCount() == 0) return m.invoke(original); // the coroutine context
            return null; // resumeWith: the follow finished
        });
        Object[] again = args.clone();
        again[ci] = standIn;
        try {
            REPLAYING.set(Boolean.TRUE);
            XposedBridge.invokeOriginalMethod(method, self, again);
            ModuleLog.line(TAG + "Profile follow confirmed and sent");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not send the follow: " + t);
        } finally {
            REPLAYING.remove();
        }
    }

    /** Cancel: the manager re-reads the profile's real status (its reset after a failed follow). */
    private static void reset(Object dataSource, Object[] args, Class<?> managerClass) {
        ModuleLog.line(TAG + "Profile follow cancelled");
        Method r = resetMethod;
        if (r == null) return;
        try {
            Object manager = null;
            for (Field f : SafeReflect.fields(dataSource.getClass())) {
                if (f.getType() != managerClass) continue;
                f.setAccessible(true);
                manager = f.get(dataSource);
            }
            if (manager == null) return;
            Class<?> modelType = r.getParameterTypes()[0];
            for (Object a : args) {
                if (a == null || !a.getClass().getName().startsWith("X.")) continue;
                for (Method get : a.getClass().getMethods()) {
                    if (get.getParameterCount() != 0 || get.getReturnType() != modelType) continue;
                    Object model = get.invoke(a);
                    if (model == null) continue;
                    r.setAccessible(true);
                    r.invoke(manager, model);
                    return;
                }
            }
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not reset the Follow button after Cancel: " + t);
        }
    }

    private static Object user(Object[] args) {
        for (Object a : args) if (a != null && a.getClass().getName().equals(USER)) return a;
        return null;
    }

    private static String username(Object[] args) {
        Object u = user(args);
        Method name = UserUtils.userUsernameGetter;
        if (u == null || name == null) return null;
        try { return (String) name.invoke(u); } catch (Throwable t) { return null; }
    }

    private static void confirm(Activity act, String username, Runnable onFollow, Runnable onCancel) {
        try {
            IeDialog dlg = new IeDialog(act)
                    .title(username != null ? I18n.t(act, R.string.ig_confirm_follow_title_v2, username)
                            : I18n.t(act, R.string.ig_confirm_follow_message_any));
            final boolean[] followed = {false};
            dlg.secondary(act.getString(android.R.string.cancel), () -> { });
            dlg.primary(I18n.t(act, R.string.ig_confirm_follow_yes), () -> { followed[0] = true; onFollow.run(); });
            dlg.onDismiss(() -> { if (!followed[0]) onCancel.run(); });
            if (dlg.show().isShowing()) return;
            ModuleLog.warn(TAG + "Could not show the follow confirmation, so the follow was cancelled");
            onCancel.run();
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not show the follow confirmation, so the follow was cancelled", t);
            onCancel.run();
        }
    }

    private static void send(Member method, Object[] args) {
        try {
            REPLAYING.set(Boolean.TRUE);
            XposedBridge.invokeOriginalMethod(method, null, args);
            ModuleLog.line(TAG + "Follow confirmed and sent");
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not send the follow: " + t);
        } finally {
            REPLAYING.remove();
        }
    }

    /** Back to "not following": a follow only starts from there. */
    private static void undo(Member method, Object[] args) {
        ModuleLog.line(TAG + "Follow cancelled");
        Method setter = statusSetter;
        Object user = user(args);
        Object manager = null;
        for (Object a : args) if (a != null && a.getClass() == ((Method) method).getDeclaringClass()) manager = a;
        if (setter == null || user == null || manager == null) return;
        try {
            Object notFollowing = null;
            for (Object c : setter.getParameterTypes()[0].getEnumConstants())
                if (FollowConfirmPolicy.isNotFollowing(c)) notFollowing = c;
            if (notFollowing == null) return;
            setter.setAccessible(true);
            setter.invoke(manager, notFollowing, user, true);
            announce(manager, user, notFollowing);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not reset the Follow button after Cancel: " + t);
        }
    }

    // Instagram's follow-status event ((FollowStatus, user id), posted on the session's event bus
    // after every follow tap): the feed's inline buttons and suggestion cards redraw from it.
    private static Constructor<?> statusEvent;
    private static Method busFactory, busPost;

    private static void resolveStatusEvent(Class<?> statusType, boolean cached) {
        if (cached) {
            Constructor<?> event = DexKitCache.loadConstructor(EVENT_KEY, Module.hostClassLoader);
            Method factory = DexKitCache.loadMethod(BUS_FACTORY_KEY, Module.hostClassLoader);
            Method post = DexKitCache.loadMethod(BUS_POST_KEY, Module.hostClassLoader);
            if (event != null && factory != null && post != null) {
                statusEvent = event;
                busFactory = factory;
                busPost = post;
                return;
            }
        }
        try {
            for (Found md : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                    .name("<init>").paramTypes(statusType.getName(), "java.lang.String")))) {
                Class<?> c = Class.forName(md.getClassName(), false, Module.hostClassLoader);
                if (c.getInterfaces().length != 1 || c.getInterfaces()[0].getDeclaredMethods().length != 0) continue;
                Class<?> marker = c.getInterfaces()[0];
                for (Found post : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes(marker.getName()).returnType("void")))) {
                    if (!post.isMethod()) continue;
                    Class<?> bus = Class.forName(post.getClassName(), false, Module.hostClassLoader);
                    try { bus.getDeclaredConstructor(android.os.Looper.class); } catch (NoSuchMethodException e) { continue; }
                    Method factory = null;
                    for (Method m : SafeReflect.methods(bus))
                        if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == bus && m.getParameterCount() == 1) factory = m;
                    if (factory == null) continue;
                    statusEvent = c.getDeclaredConstructor(statusType, String.class);
                    busFactory = factory;
                    busPost = post.getMethodInstance(Module.hostClassLoader);
                    DexKitCache.saveConstructor(EVENT_KEY, statusEvent);
                    DexKitCache.saveMethod(BUS_FACTORY_KEY, busFactory);
                    DexKitCache.saveMethod(BUS_POST_KEY, busPost);
                    ModuleLog.detail(TAG + "status event " + c.getName() + " on " + bus.getName());
                    return;
                }
            }
            ModuleLog.warn(TAG + "After Cancel, Follow buttons in the feed may only update after a refresh (not supported in this Instagram version)");
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "After Cancel, Follow buttons in the feed may only update after a refresh: " + t);
        }
    }

    private static void announce(Object manager, Object user, Object status) {
        if (statusEvent == null) return;
        try {
            Object session = null;
            for (Field f : SafeReflect.fields(manager.getClass())) {
                if (!f.getType().getName().equals("com.instagram.common.session.UserSession")) continue;
                f.setAccessible(true);
                session = f.get(manager);
            }
            String id = FollowListLoader.idOf(user);
            if (session == null || id == null) return;
            busFactory.setAccessible(true);
            Object bus = busFactory.invoke(null, session);
            statusEvent.setAccessible(true);
            busPost.setAccessible(true);
            busPost.invoke(bus, statusEvent.newInstance(status, id));
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not update the Follow buttons in the feed after Cancel: " + t);
        }
    }
}
