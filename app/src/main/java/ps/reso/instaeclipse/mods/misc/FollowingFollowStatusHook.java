package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Reads only the account and User supplied to the native following-row binder. */
public final class FollowingFollowStatusHook {
    private static final String KEY = "FollowingFollowStatus";
    private static final String TAG = "ie_following_follow_status";
    private static final String USER = "com.instagram.user.model.User";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String LIST_DATA = "com.instagram.follow.analytics.FollowListData";
    private static final String RELATIONSHIP = "com.instagram.api.schemas.RelationshipInfoDict";
    private static final String PANDO_RELATIONSHIP = "com.instagram.api.schemas.ImmutablePandoRelationshipInfoDict";
    private static final String SUBTITLE_ID = "follow_list_subtitle";
    private static final Set<String> diagnostics = Collections.synchronizedSet(new HashSet<>());
    private static volatile Field knownSubtitle;
    private static volatile int subtitleResId;

    /** {@code context} is Instagram's: the row's subtitle id is looked up here so a renamed
     *  resource marks the feature broken instead of leaving it "attached" with no labels. */
    @SuppressLint("DiscouragedApi")
    public void install(android.content.Context context, DexKitBridge bridge, ClassLoader loader) {
        try {
            subtitleResId = context.getResources().getIdentifier(SUBTITLE_ID, "id", context.getPackageName());
            if (subtitleResId == 0) throw new android.content.res.Resources.NotFoundException(SUBTITLE_ID);
            Method binder = resolve(bridge, loader, "FollowingRowBind_v1", MethodMatcher.create()
                    .usingStrings("FollowListFragment.ShouldShowUnfollow", "least_interacted_with_followers")
                    .paramCount(19).returnType(void.class));
            Class<?>[] parameters = binder.getParameterTypes();
            if (!Modifier.isStatic(binder.getModifiers()) || !parameters[3].getName().equals(SESSION)
                    || !parameters[6].getName().equals(USER) || !parameters[4].isEnum()) {
                throw new IllegalStateException("Following row signature changed");
            }
            Field listData = uniqueField(parameters[10], LIST_DATA);
            Field owner = listOwner(bridge, parameters[10], listData.getType());
            Field listType = uniqueField(listData.getType(), parameters[4].getName());
            List<Field> texts = textFields(parameters[9]);
            Method viewerId = parameters[3].getMethod("getUserId");
            Method userId = userIdGetter(bridge, loader, parameters[6]);
            Method directFollowedBy = resolve(bridge, loader, "FollowingUserFollowedBy_v1", MethodMatcher.create()
                    .declaredClass(USER).usingStrings("followed_by").paramCount(0).returnType(Boolean.class));
            Method friendship = resolve(bridge, loader, "FollowingUserFriendship_v1", MethodMatcher.create()
                    .declaredClass(USER).usingStrings("friendship_status").paramCount(0).returnType(RELATIONSHIP));
            Class<?> relationship = Class.forName(RELATIONSHIP, false, loader);
            Method followedBy = relationshipGetter(bridge, loader, relationship, "followed_by");
            Method following = relationshipGetter(bridge, loader, relationship, "following");

            XposedBridge.hookMethod(binder, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    JitGuard.tick(param.method);
                    clearLabel(subtitle(texts, param.args[9]));
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    JitGuard.tick(param.method);
                    try {
                        TextView anchor = subtitle(texts, param.args[9]);
                        if (anchor == null || param.hasThrowable() || !FeatureFlags.showFollowingFollowStatus) return;
                        ProfileFollowStatus.Status status = ProfileFollowStatus.Status.HIDDEN;
                        Object data = listData.get(param.args[10]);
                        Object user = param.args[6];
                        Object session = param.args[3];
                        // Both the row and its list must agree: a recycled/search row is not enough.
                        if (data != null && user != null && session != null && param.args[4] == listType.get(data)) {
                            String viewer = (String) viewerId.invoke(session);
                            String listOwner = (String) owner.get(data);
                            String type = ((Enum<?>) param.args[4]).name();
                            if (viewer != null && viewer.equals(listOwner)) {
                                Object relation = friendship.invoke(user);
                                status = FollowingFollowStatus.from(true, viewer, listOwner, type,
                                        (String) userId.invoke(user), (Boolean) directFollowedBy.invoke(user),
                                        relation == null ? null : (Boolean) followedBy.invoke(relation),
                                        relation == null ? null : (Boolean) following.invoke(relation));
                            }
                        }
                        bindLabel(anchor, status);
                        if (status != ProfileFollowStatus.Status.HIDDEN) diagnostic("Own following row labelled");
                    } catch (Throwable error) {
                        // The pre-bind clear prevents stale status when a native projection changes.
                        diagnostic("Following relationship unavailable: " + error.getClass().getSimpleName());
                    }
                }
            });
            FeatureStatusTracker.setHooked(KEY);
            ModuleLog.ok("(IE|FollowingStatus) Ready");
        } catch (Throwable error) {
            if (FeatureFlags.showFollowingFollowStatus) {
                FeatureStatusTracker.setBroken(KEY, R.string.ig_feature_following_follow_status);
            }
            ModuleLog.error("(IE|FollowingStatus) Could not turn on follow status in the following list: its rows were not found", error);
        }
    }

    /** User.getId() is gone after 446; toString() still delegates to the same id accessor on 446 and 451. */
    private static Method userIdGetter(DexKitBridge bridge, ClassLoader loader, Class<?> user) throws Exception {
        String key = "FollowingUserId_v1";
        Method cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(key, loader) : null;
        if (cached != null) return cached;
        Set<Method> getters = new HashSet<>();
        for (var call : bridge.getMethodData(user.getDeclaredMethod("toString")).getInvokes()) {
            if (call.isMethod() && call.getClassName().equals(user.getName()) && call.getParamTypeNames().isEmpty()
                    && call.getReturnTypeName().equals(String.class.getName())) getters.add(call.getMethodInstance(loader));
        }
        if (getters.size() != 1) throw new NoSuchMethodException("User id accessor matched " + getters.size());
        Method getter = getters.iterator().next();
        getter.setAccessible(true);
        DexKitCache.saveMethod(key, getter);
        return getter;
    }

    /** The list parameters decide "this is my list" in their constructor from exactly this FollowListData id. */
    private static Field listOwner(DexKitBridge bridge, Class<?> parameters, Class<?> data) throws Exception {
        String key = "FollowingListOwner_v1";
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(key) : null;
        if (cached != null) return field(data, cached, String.class);
        Set<String> names = new HashSet<>();
        for (Constructor<?> constructor : parameters.getDeclaredConstructors()) {
            for (var usage : bridge.getMethodData(constructor).getUsingFields()) {
                var field = usage.getField();
                if (usage.getUsingType().isRead() && field.getClassName().equals(data.getName())
                        && field.getTypeName().equals(String.class.getName())) names.add(field.getFieldName());
            }
        }
        if (names.size() != 1) throw new NoSuchFieldException("Following list owner matched " + names.size() + " fields");
        Field owner = field(data, names.iterator().next(), String.class);
        DexKitCache.saveString(key, owner.getName());
        return owner;
    }

    private static List<Field> textFields(Class<?> holder) throws NoSuchFieldException {
        List<Field> fields = new ArrayList<>();
        for (Field field : holder.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != TextView.class) continue;
            field.setAccessible(true);
            fields.add(field);
        }
        if (fields.isEmpty()) throw new NoSuchFieldException("Following row text missing");
        return fields;
    }

    /** The row keeps several TextViews; the label belongs under the one IG inflates as follow_list_subtitle. */
    private static TextView subtitle(List<Field> texts, Object holder) throws IllegalAccessException {
        if (holder == null) return null;
        Field known = knownSubtitle;
        if (known == null) {
            known = subtitleField(texts, holder, subtitleResId);
            if (known == null) {
                diagnostic("Following row subtitle unavailable");
                return null;
            }
            knownSubtitle = known;
        }
        return (TextView) known.get(holder);
    }

    static Field subtitleField(List<Field> texts, Object holder, int id) throws IllegalAccessException {
        if (id == 0 || id == View.NO_ID) return null;
        for (Field field : texts) {
            if (field.get(holder) instanceof TextView view && view.getId() == id) return field;
        }
        return null;
    }

    private static Method relationshipGetter(DexKitBridge bridge, ClassLoader loader, Class<?> type,
                                              String property) throws Exception {
        Method implementation = resolve(bridge, loader, "FollowingRelationship_" + property + "_v1",
                MethodMatcher.create().declaredClass(PANDO_RELATIONSHIP)
                        .usingNumbers(property.hashCode()).paramCount(0).returnType(Boolean.class));
        return type.getMethod(implementation.getName());
    }

    private static Method resolve(DexKitBridge bridge, ClassLoader loader, String key,
                                  MethodMatcher matcher) throws Exception {
        Method cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(key, loader) : null;
        if (cached != null) return cached;
        var matches = bridge.findMethod(FindMethod.create().matcher(matcher));
        if (matches.size() != 1) throw new IllegalStateException(key + " matched " + matches.size() + " methods");
        Method method = matches.get(0).getMethodInstance(loader);
        method.setAccessible(true);
        DexKitCache.saveMethod(key, method);
        return method;
    }

    private static Field field(Class<?> type, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        if (field.getType() != expected || Modifier.isStatic(field.getModifiers())) {
            throw new NoSuchFieldException("Following row field changed: " + name);
        }
        field.setAccessible(true);
        return field;
    }

    private static Field uniqueField(Class<?> type, String expected) throws NoSuchFieldException {
        Field result = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(expected)) continue;
            if (result != null) throw new NoSuchFieldException("Following list field ambiguous: " + expected);
            result = field;
        }
        if (result == null) throw new NoSuchFieldException("Following list field missing: " + expected);
        result.setAccessible(true);
        return result;
    }

    static void clearLabel(TextView subtitle) {
        if (subtitle == null || !(subtitle.getParent() instanceof ViewGroup parent)) return;
        View previous = parent.findViewWithTag(TAG);
        if (previous != null) previous.setVisibility(View.GONE);
    }

    static void bindLabel(TextView subtitle, ProfileFollowStatus.Status status) {
        if (subtitle == null || !(subtitle.getParent() instanceof LinearLayout parent)
                || parent.getOrientation() != LinearLayout.VERTICAL) return;
        TextView label = parent.findViewWithTag(TAG);
        if (!FeatureFlags.showFollowingFollowStatus || status == ProfileFollowStatus.Status.HIDDEN) {
            if (label != null) label.setVisibility(View.GONE);
            return;
        }
        if (label == null) {
            label = new TextView(subtitle.getContext());
            label.setTag(TAG);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setIncludeFontPadding(false);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            label.setClickable(false);
            label.setFocusable(false);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            layout.topMargin = Math.round(2 * subtitle.getResources().getDisplayMetrics().density);
            // No pre-draw listener: one per recycled row leaked into the window (code review M30),
            // and every rebind already clears and re-applies the label, including a toggle-off.
            parent.addView(label, parent.indexOfChild(subtitle) + 1, layout);
        }
        label.setTypeface(Typeface.create(subtitle.getTypeface(), Typeface.NORMAL));
        label.setTextColor(subtitle.getTextColors());
        label.setText(I18n.t(subtitle.getContext(), status == ProfileFollowStatus.Status.DOES_NOT_FOLLOW
                ? R.string.ig_profile_not_following_you : R.string.ig_profile_follows_you));
        label.setVisibility(View.VISIBLE);
    }

    private static void diagnostic(String message) {
        if (diagnostics.add(message)) ModuleLog.detail("(IE|FollowingStatus) " + message);
    }
}
