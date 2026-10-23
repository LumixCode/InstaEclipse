package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import ps.reso.instaeclipse.utils.ui.ResIds;

/** Profile-bound relationship metadata; never infers the viewed profile from unrelated requests. */
public final class ProfileFollowStatusHook {
    private static final String KEY = "FollowStatusLabel";
    private static final String TAG = "ie_profile_follow_status";
    private static final String USER = "com.instagram.user.model.User";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String USER_DICT = "com.instagram.user.model.LiveTreeUserDict";
    private static final String FRIENDSHIP = "com.instagram.user.model.FriendshipStatus";
    private static final String VIEW_MODEL = "com.instagram.profile.fragment.UserDetailViewModel";
    private static final Set<String> diagnostics = Collections.synchronizedSet(new HashSet<>());
    private static final String[] NAME_IDS = {"profile_header_full_name", "profile_header_full_name_above_vanity"};
    /** igds_color_secondary_text attr id; -1 until resolved (0 = Instagram has none). */
    private static volatile int secondaryTextAttr = -1;

    /** Returns whether the header binder was hooked; ProfileFollowsYouHook runs either way. */
    public boolean install(DexKitBridge bridge, ClassLoader loader) {
        try {
            final Method viewerId = Class.forName(SESSION, false, loader).getMethod("getUserId");
            List<Method> binders = resolveBinders(bridge, loader);
            int installed = 0;
            for (Method binder : binders) {
                if (Modifier.isStatic(binder.getModifiers())) continue;
                int viewIndex = -1;
                Class<?>[] parameters = binder.getParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (parameters[i] == View.class) viewIndex = i;
                }
                final Field session = uniqueField(binder.getDeclaringClass(), SESSION);
                if (viewIndex < 0 || session == null) continue;
                final ProfileReader reader = resolveReader(bridge, loader, binder);
                final int rootArg = viewIndex;
                XposedBridge.hookMethod(binder, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        JitGuard.tick(param.method);
                        if (FeatureFlags.showFollowStatusLabel) diagnostic("Native profile binder reached");
                        View root = (View) param.args[rootArg];
                        if (root == null || findAnchor(root) == null) return;
                        if (FeatureFlags.showFollowStatusLabel) diagnostic("Native profile metadata row found");
                        ProfileFollowStatus.Status status = ProfileFollowStatus.Status.HIDDEN;
                        String profile = null;
                        try {
                            if (FeatureFlags.showFollowStatusLabel) {
                                profile = reader.profileId(param.args);
                                Object account = session.get(param.thisObject);
                                if (account != null) status = reader.read((String) viewerId.invoke(account), param.args);
                                if (status == ProfileFollowStatus.Status.HIDDEN) diagnostic("Profile relationship hidden, self, or not available yet");
                            }
                        } catch (Throwable error) {
                            // A partial model must clear a recycled profile's old label, not imply "no".
                            diagnostic("Profile relationship read failed: " + error.getClass().getSimpleName());
                        }
                        bindLabel(root, status, profile);
                        if (status != ProfileFollowStatus.Status.HIDDEN) diagnostic("Profile follow label bound");
                    }
                });
                installed++;
            }
            if (installed == 0) throw new IllegalStateException("Profile binding boundary missing");
            FeatureStatusTracker.setHooked(KEY);
            ModuleLog.ok("(IE|FollowLabel) Ready");
            return true;
        } catch (Throwable t) {
            if (FeatureFlags.showFollowStatusLabel) FeatureStatusTracker.setBroken(KEY, R.string.ig_dialog_misc_profile_follows_label);
            ModuleLog.error("(IE|FollowLabel) Could not turn on the follow label on profiles: the profile header was not found", t);
            return false;
        }
    }

    /** Whether this header already shows the local label, so the network-based fallback stays out. */
    public static boolean isShowing(View root) {
        View label = root.findViewWithTag(TAG);
        return label != null && label.getVisibility() == View.VISIBLE
                && label.getTag(R.id.ie_follow_status_state) != ProfileFollowStatus.Status.HIDDEN;
    }

    private static void diagnostic(String message) {
        if (diagnostics.add(message)) ModuleLog.detail("(IE|FollowLabel) " + message);
    }

    private interface ProfileReader {
        ProfileFollowStatus.Status read(String viewerId, Object[] arguments) throws Exception;

        /** The bound profile's id, or null when the bind doesn't identify one profile. */
        String profileId(Object[] arguments) throws Exception;
    }

    private static ProfileReader resolveReader(DexKitBridge bridge, ClassLoader loader, Method binder) throws Exception {
        Method username = resolve(bridge, loader, "ProfileFollowUsername_v3", MethodMatcher.create()
                .declaredClass(VIEW_MODEL).usingStrings("INVALID_USER_NAME").paramCount(0).returnType(String.class));
        for (var invocation : bridge.getMethodData(username).getInvokes()) {
            if (invocation.isConstructor() || !invocation.getReturnTypeName().equals(String.class.getName())
                    || !invocation.getParamTypeNames().equals(List.of("int"))
                    || Modifier.isStatic(invocation.getModifiers())) continue;
            Method strings = invocation.getMethodInstance(loader);
            Class<?> schema = strings.getDeclaringClass();
            try { schema.getMethod("getTypeName"); }
            catch (NoSuchMethodException unsupported) { continue; }
            for (var usage : bridge.getMethodData(username).getUsingFields()) {
                Field nested = usage.getField().getFieldInstance(loader);
                if (nested.getType() != schema || Modifier.isStatic(nested.getModifiers())) continue;
                nested.setAccessible(true);
                List<Field> fields = boundFields(bridge, loader, binder, nested.getDeclaringClass());
                if (fields.isEmpty()) continue;
                Method children = resolveSchemaChild(bridge, loader, schema);
                Method booleans = uniqueIntGetter(schema, Boolean.class);
                diagnostic("Using bound profile schema projections");
                return new ProfileReader() {
                    @Override public ProfileFollowStatus.Status read(String viewerId, Object[] args) throws Exception {
                        return ProfileFollowStatus.fromSchemas(viewerId,
                                ProfileFollowStatus.boundSchemas(fields, nested, args), strings, children, booleans);
                    }

                    @Override public String profileId(Object[] args) throws Exception {
                        return ProfileFollowStatus.profileIdOf(ProfileFollowStatus.boundSchemas(fields, nested, args), strings);
                    }
                };
            }
        }

        // Older Instagram keeps the displayed User directly in the bound header.
        Class<?> userType = Class.forName(USER, false, loader);
        List<Field> fields = boundFields(bridge, loader, binder, userType);
        if (fields.isEmpty()) throw new NoSuchFieldException("Bound profile User missing");
        Method userId = userType.getMethod("getId");
        Field dict = uniqueField(userType, USER_DICT);
        Method friendship = uniqueGetter(dict == null ? userType : dict.getType(), FRIENDSHIP);
        Method toMap = resolve(bridge, loader, "ProfileFollowMap_v1", MethodMatcher.create()
                .paramTypes(FRIENDSHIP).returnType(Map.class).usingStrings("followed_by", "following"));
        if (!Modifier.isStatic(toMap.getModifiers())) throw new IllegalStateException("Friendship mapper is not static");
        return new ProfileReader() {
            @Override public ProfileFollowStatus.Status read(String viewerId, Object[] args) throws Exception {
                Object user = ProfileFollowStatus.boundUser(fields, args);
                if (user == null) return ProfileFollowStatus.Status.HIDDEN;
                Object data = dict == null ? user : dict.get(user);
                Object relationship = data == null ? null : friendship.invoke(data);
                return ProfileFollowStatus.from(viewerId, (String) userId.invoke(user),
                        relationship == null ? null : (Map<?, ?>) toMap.invoke(null, relationship));
            }

            @Override public String profileId(Object[] args) throws Exception {
                Object user = ProfileFollowStatus.boundUser(fields, args);
                return user == null ? null : (String) userId.invoke(user);
            }
        };
    }

    private static List<Field> boundFields(DexKitBridge bridge, ClassLoader loader, Method binder, Class<?> type)
            throws Exception {
        List<Field> fields = new ArrayList<>();
        for (var usage : bridge.getMethodData(binder).getUsingFields()) {
            Field field = usage.getField().getFieldInstance(loader);
            if (!type.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())
                    || field.getDeclaringClass() == binder.getDeclaringClass() || fields.contains(field)) continue;
            field.setAccessible(true);
            fields.add(field);
        }
        return fields;
    }

    private static Method resolveSchemaChild(DexKitBridge bridge, ClassLoader loader, Class<?> schema) throws Exception {
        String key = "ProfileFollowSchemaChild_v3";
        Method cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethod(key, loader) : null;
        if (cached != null) return cached;
        Set<Method> common = null;
        // Required child accessors differ across native callers. Their shared accessor is the
        // nullable friendship_status child, so absent relationship data never becomes false.
        for (var caller : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingNumbers("friendship_status".hashCode(), "followed_by".hashCode())))) {
            Set<Method> children = new HashSet<>();
            for (var invocation : caller.getInvokes()) {
                if (invocation.isConstructor() || !invocation.getClassName().equals(schema.getName())
                        || !invocation.getReturnTypeName().equals(schema.getName())
                        || !invocation.getParamTypeNames().equals(List.of("int"))
                        || Modifier.isStatic(invocation.getModifiers())) continue;
                Method method = invocation.getMethodInstance(loader);
                children.add(method);
            }
            if (children.isEmpty()) continue;
            if (common == null) common = children;
            else common.retainAll(children);
        }
        if (common == null || common.size() != 1) throw new NoSuchMethodException("Nullable relationship accessor ambiguous");
        Method result = common.iterator().next();
        result.setAccessible(true);
        DexKitCache.saveMethod(key, result);
        return result;
    }

    private static Method uniqueIntGetter(Class<?> type, Class<?> returnType) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != returnType
                    || method.getParameterCount() != 1 || method.getParameterTypes()[0] != int.class) continue;
            if (found != null) throw new NoSuchMethodException("Schema getter ambiguous");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Schema getter missing");
        found.setAccessible(true);
        return found;
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

    private static List<Method> resolveBinders(DexKitBridge bridge, ClassLoader loader) throws Exception {
        List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods("ProfileFollowBind_v2", loader) : null;
        if (cached != null && !cached.isEmpty()) return cached;
        List<Method> methods = new ArrayList<>();
        for (var match : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings("ProfileHeaderBinderGroup.bindView").returnType(void.class)))) {
            methods.add(match.getMethodInstance(loader));
        }
        DexKitCache.saveMethods("ProfileFollowBind_v2", methods);
        return methods;
    }

    private static Field uniqueField(Class<?> type, String fieldType) {
        Field found = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !field.getType().getName().equals(fieldType)) continue;
            if (found != null) return null;
            field.setAccessible(true);
            found = field;
        }
        return found;
    }

    private static Method uniqueGetter(Class<?> type, String returnType) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                    || !method.getReturnType().getName().equals(returnType)) continue;
            if (found != null) throw new NoSuchMethodException("Ambiguous friendship getter");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException("Friendship getter missing");
        found.setAccessible(true);
        return found;
    }

    static void bindLabel(View root, ProfileFollowStatus.Status status) {
        bindLabel(root, status, null);
    }

    /**
     * IG binds the header rows one by one and again on every refresh, and only some binds carry
     * the relationship. A bind of the profile the label already shows that lacks it keeps the
     * label: hiding it there and showing it on the next bind made the header line flicker. Another
     * profile (a recycled header), your own profile or conflicting data still hide it.
     */
    static void bindLabel(View root, ProfileFollowStatus.Status status, String profile) {
        View existing = root.findViewWithTag(TAG);
        TextView label = existing instanceof TextView ? (TextView) existing : null;
        if (status == ProfileFollowStatus.Status.HIDDEN) {
            if (label != null) {
                if (profile != null && profile.equals(label.getTag(R.id.ie_follow_status_profile))
                        && label.getTag(R.id.ie_follow_status_state) != ProfileFollowStatus.Status.HIDDEN) return;
                label.setTag(R.id.ie_follow_status_state, status);
                applyVisibility(label, false);
            }
            return;
        }
        View anchor = findAnchor(root);
        if (anchor == null) return;
        if (label == null) {
            LinearLayout parent = anchor instanceof LinearLayout row
                    ? row : (LinearLayout) anchor.getParent();
            label = new TextView(root.getContext());
            label.setTag(TAG);
            label.setTag(R.id.ie_follow_status_inline_row, parent == anchor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setGravity(Gravity.START);
            label.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            label.setIncludeFontPadding(false);
            label.setPaddingRelative(anchor.getPaddingStart(), 0, anchor.getPaddingEnd(), 0);
            int gap = Math.round(4 * root.getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                    parent == anchor ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            layout.topMargin = gap;
            layout.bottomMargin = gap;
            if (parent != anchor && anchor.getLayoutParams() instanceof ViewGroup.MarginLayoutParams source) {
                layout.setMarginStart(source.getMarginStart());
                layout.setMarginEnd(source.getMarginEnd());
            }
            parent.addView(label, parent == anchor ? parent.getChildCount() : parent.indexOfChild(anchor) + 1, layout);
            TextView added = label;
            // Registered only while attached: a window-wide listener per visited profile would
            // outlive its header and run on every frame (code review M30).
            ViewTreeObserver.OnPreDrawListener sync = () -> {
                syncLabel(root, added);
                return true;
            };
            label.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    view.getViewTreeObserver().addOnPreDrawListener(sync);
                }
                @Override public void onViewDetachedFromWindow(View view) {
                    view.getViewTreeObserver().removeOnPreDrawListener(sync);
                    restoreRow(added, parent);
                }
            });
            // addView already dispatched the attach before the listener above existed.
            if (label.isAttachedToWindow()) label.getViewTreeObserver().addOnPreDrawListener(sync);
        }
        TextView name = findName(root);
        if (name != null) label.setTypeface(Typeface.create(name.getTypeface(), Typeface.NORMAL));
        label.setTextColor(secondaryTextColor(root.getContext(), name != null ? name : anchor));
        label.setTag(R.id.ie_follow_status_state, status);
        label.setTag(R.id.ie_follow_status_profile, profile);
        int text = status == ProfileFollowStatus.Status.MUTUAL ? R.string.ig_profile_follow_mutual
                : status == ProfileFollowStatus.Status.FOLLOWS_YOU ? R.string.ig_profile_follows_you
                : R.string.ig_profile_not_following_you;
        label.setText(I18n.t(root.getContext(), text));
        applyVisibility(label, FeatureFlags.showFollowStatusLabel);
    }

    /** Applies a live toggle-off and keeps the label within the header width once it is laid out. */
    static void syncLabel(View root, TextView label) {
        boolean visible = FeatureFlags.showFollowStatusLabel
                && label.getTag(R.id.ie_follow_status_state) != ProfileFollowStatus.Status.HIDDEN;
        applyVisibility(label, visible);
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        if (width > 0 && label.getMaxWidth() != width) label.setMaxWidth(width);
    }

    private static void applyVisibility(TextView label, boolean visible) {
        if (Boolean.TRUE.equals(label.getTag(R.id.ie_follow_status_inline_row))
                && label.getParent() instanceof View row) {
            if (visible && row.getVisibility() != View.VISIBLE) {
                label.setTag(R.id.ie_follow_status_row_visibility, row.getVisibility());
                row.setVisibility(View.VISIBLE);
            } else if (!visible) restoreRow(label, row);
        }
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (label.getVisibility() != visibility) label.setVisibility(visibility);
    }

    private static void restoreRow(TextView label, View row) {
        Object previous = label.getTag(R.id.ie_follow_status_row_visibility);
        if (!(previous instanceof Integer)) return;
        // Restore before IG binds again so a new profile's native visibility takes precedence.
        if (row.getVisibility() == View.VISIBLE) row.setVisibility((Integer) previous);
        label.setTag(R.id.ie_follow_status_row_visibility, null);
    }

    // Ids are resolved once (ResIds): the binder runs for every header row on each profile list
    // refresh, label on or off, and getIdentifier scans Instagram's 36.7k unsorted resource keys.
    private static View findAnchor(View root) {
        TextView name = findName(root);
        if (name != null && name.getParent() instanceof LinearLayout parent
                && parent.getOrientation() == LinearLayout.VERTICAL) return name;
        // Current IG uses a ConstraintLayout header with a dedicated inline metadata row.
        int rowId = ResIds.id(root.getContext(), "internal_only_badges");
        View row = rowId == 0 ? null : root.findViewById(rowId);
        return row instanceof LinearLayout ? row : null;
    }

    private static TextView findName(View root) {
        for (String name : NAME_IDS) {
            int id = ResIds.id(root.getContext(), name);
            View candidate = id == 0 ? null : root.findViewById(id);
            if (candidate instanceof TextView) return (TextView) candidate;
        }
        return null;
    }

    @SuppressLint("DiscouragedApi")
    private static ColorStateList secondaryTextColor(Context context, View anchor) {
        int attribute = secondaryTextAttr;
        if (attribute < 0) { // once per process, like the ids above
            attribute = context.getResources().getIdentifier("igds_color_secondary_text", "attr", context.getPackageName());
            secondaryTextAttr = attribute;
        }
        TypedValue color = new TypedValue();
        if (attribute != 0 && context.getTheme().resolveAttribute(attribute, color, true)) {
            if (color.resourceId != 0) return context.getColorStateList(color.resourceId);
            if (color.type >= TypedValue.TYPE_FIRST_COLOR_INT && color.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return ColorStateList.valueOf(color.data);
            }
        }
        return anchor instanceof TextView text ? text.getTextColors() : new TextView(context).getTextColors();
    }
}
