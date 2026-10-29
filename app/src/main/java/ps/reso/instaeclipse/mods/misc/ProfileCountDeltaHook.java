package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.IeColors;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.WatchlistStore;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class ProfileCountDeltaHook {
    private static final String TAG = "(IE|CountDelta) ";
    private static final String KEY = "ProfileCountDelta";
    private static final String METHODS_KEY = "ProfileCountDelta_methods_v1";
    private static final String ID_KEY = "ProfileCountDelta_id_v1";
    private static final String METRICS_KEY = "ProfileCountDelta_metrics_v1";
    private static final String STATE_KEY = "ProfileCountDelta_state_v1";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String FOLLOWERS_VALUE = "profile_header_familiar_followers_value";
    private static final String FOLLOWING_VALUE = "profile_header_familiar_following_value";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> diagnostics = Collections.synchronizedSet(new HashSet<>());

    static final ProfileCountDelta.Seen SEEN = new ProfileCountDelta.Seen();
    private static final ThreadLocal<String> BINDING = new ThreadLocal<>();

    private static int stateArg = -1;
    private static Field metricsField, profileIdField;
    private static int cellArg = -1, countArg = -1, sessionArg = -1;
    private static final List<Integer> textArgs = new ArrayList<>();
    private static Method userId;

    private static volatile Map<String, int[]> baselines = Collections.emptyMap();
    private static volatile String baselinesSelf;
    private static volatile long baselinesVersion = -1;
    private static boolean loading;
    private static final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<>());

    public void install(ClassLoader loader) {
        try {
            userId = Class.forName(SESSION, false, loader).getMethod("getUserId");
            List<Method> methods = cached(loader);
            if (methods == null) {
                Found step = single("bindUserMetrics");
                Found helper = single("UserMetricsBinderHelper.shrinkVanityMetrics");
                methods = List.of(step.getMethodInstance(loader), helper.getMethodInstance(loader));
                resolveMetrics(step, methods.get(0), loader);
                save(methods);
            }
            Method stepMethod = methods.get(0);
            Method cellMethod = methods.get(1);
            resolveCell(cellMethod);
            XposedBridge.hookMethod(stepMethod, STEP);
            XposedBridge.hookMethod(cellMethod, CELL);
            WatchlistStore.setCountSource(ProfileCountDeltaHook::countsAtAnalysis);
            FeatureStatusTracker.setHooked(KEY);
            ModuleLog.ok(TAG + "Ready");
            ModuleLog.detail(TAG + "metrics " + stepMethod.getDeclaringClass().getName() + "." + stepMethod.getName()
                    + " (state arg " + stateArg + ", id " + profileIdField.getDeclaringClass().getName() + "." + profileIdField.getName()
                    + "), cell " + cellMethod.getDeclaringClass().getName() + "." + cellMethod.getName());
        } catch (Throwable t) {
            if (FeatureFlags.profileAnalyzer) FeatureStatusTracker.setBroken(KEY, R.string.ig_pa_count_delta);
            ModuleLog.error(TAG + "Could not turn on follower count changes on profiles", t);
        }
    }

    private static List<Method> cached(ClassLoader loader) {
        if (!DexKitCache.isCacheValid()) return null;
        try {
            List<Method> methods = DexKitCache.loadMethods(METHODS_KEY, loader);
            Field id = DexKitCache.loadField(ID_KEY, loader);
            String state = DexKitCache.loadString(STATE_KEY);
            if (methods == null || methods.size() != 2 || id == null || state == null) return null;
            int space = state.indexOf(' ');
            int arg = Integer.parseInt(state.substring(0, space));
            Class<?> metrics = Class.forName(state.substring(space + 1), false, loader);
            Class<?>[] parameters = methods.get(0).getParameterTypes();
            if (arg < 0 || arg >= parameters.length || !id.getDeclaringClass().isAssignableFrom(metrics)) return null;
            Field holder = null;
            if (parameters[arg] != metrics) {
                holder = DexKitCache.loadField(METRICS_KEY, loader);
                if (holder == null || holder.getType() != metrics
                        || !holder.getDeclaringClass().isAssignableFrom(parameters[arg])) return null;
                holder.setAccessible(true);
            }
            id.setAccessible(true);
            stateArg = arg;
            metricsField = holder;
            profileIdField = id;
            return methods;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void save(List<Method> methods) {
        Class<?> metrics = metricsField != null ? metricsField.getType() : methods.get(0).getParameterTypes()[stateArg];
        DexKitCache.saveMethods(METHODS_KEY, methods);
        DexKitCache.saveField(ID_KEY, profileIdField);
        if (metricsField != null) DexKitCache.saveField(METRICS_KEY, metricsField);
        DexKitCache.saveString(STATE_KEY, stateArg + " " + metrics.getName());
    }

    private static Found single(String trace) {
        List<Found> found = DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(List.of(trace)).returnType(void.class)));
        if (found.size() != 1 || !found.get(0).isMethod())
            throw new IllegalStateException("'" + trace + "' matched " + found.size() + " methods");
        return found.get(0);
    }

    private static void resolveMetrics(Found step, Method stepMethod, ClassLoader loader) throws Exception {
        if (!Modifier.isStatic(stepMethod.getModifiers())) throw new IllegalStateException("metrics step is not static");
        Map<Class<?>, Set<Field>> integers = new LinkedHashMap<>();
        for (var usage : step.data().getUsingFields()) {
            if (!"java.lang.Integer".equals(usage.getField().getTypeName())) continue;
            Field field = usage.getField().getFieldInstance(loader);
            if (Modifier.isStatic(field.getModifiers())) continue;
            Set<Field> owned = integers.get(field.getDeclaringClass());
            if (owned == null) integers.put(field.getDeclaringClass(), owned = new HashSet<>());
            owned.add(field);
        }
        Class<?> metrics = null;
        for (Map.Entry<Class<?>, Set<Field>> e : integers.entrySet()) {
            if (e.getValue().size() < 3) continue;
            if (metrics != null) throw new IllegalStateException("metrics class ambiguous");
            metrics = e.getKey();
        }
        if (metrics == null) throw new IllegalStateException("metrics class missing");
        for (Field field : SafeReflect.fields(metrics)) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            if (profileIdField != null) throw new IllegalStateException("metrics id ambiguous");
            field.setAccessible(true);
            profileIdField = field;
        }
        if (profileIdField == null) throw new IllegalStateException("metrics id missing");
        Class<?>[] parameters = stepMethod.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i];
            if (type == metrics) { claimState(i, null); continue; }
            String name = type.getName();
            if (type.isPrimitive() || name.startsWith("java.") || name.startsWith("android.")
                    || name.startsWith("androidx.") || name.startsWith("kotlin")) continue;
            for (Field field : SafeReflect.fields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != metrics) continue;
                field.setAccessible(true);
                claimState(i, field);
            }
        }
        if (stateArg < 0) throw new IllegalStateException("metrics argument missing");
    }

    private static void claimState(int arg, Field field) {
        if (stateArg >= 0) throw new IllegalStateException("metrics argument ambiguous");
        stateArg = arg;
        metricsField = field;
    }

    private static void resolveCell(Method cell) {
        if (!Modifier.isStatic(cell.getModifiers())) throw new IllegalStateException("cell helper is not static");
        Class<?>[] parameters = cell.getParameterTypes();
        List<Integer> views = new ArrayList<>(), integers = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i];
            if (type == View.class) views.add(i);
            else if (TextView.class.isAssignableFrom(type)) textArgs.add(i);
            else if (type == Integer.class) integers.add(i);
            else if (type.getName().equals(SESSION)) sessionArg = i;
        }
        if (views.size() != 1 || integers.size() != 1 || textArgs.size() < 2)
            throw new IllegalStateException("cell helper shape changed: " + java.util.Arrays.toString(parameters));
        cellArg = views.get(0);
        countArg = integers.get(0);
    }

    private static final XC_MethodHook STEP = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            String profile = null;
            try {
                Object holder = param.args[stateArg];
                Object metrics = metricsField == null || holder == null ? holder : metricsField.get(holder);
                Object id = metrics != null ? profileIdField.get(metrics) : null;
                if (id instanceof String s && !s.isEmpty()) profile = s;
            } catch (Throwable t) {
                diagnostic("profile id unreadable: " + t);
            }
            if (profile == null) diagnostic("header counts bound without a profile id");
            BINDING.set(profile);
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            BINDING.remove();
        }
    };

    private static final XC_MethodHook CELL = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            try {
                TextView value = null;
                boolean followers = false;
                for (int i : textArgs) {
                    if (!(param.args[i] instanceof TextView text)) continue;
                    int id = text.getId();
                    if (id == 0 || id == View.NO_ID) continue;
                    if (id == ResIds.id(text.getContext(), FOLLOWERS_VALUE)) { value = text; followers = true; }
                    else if (id == ResIds.id(text.getContext(), FOLLOWING_VALUE)) { value = text; followers = false; }
                }
                if (value == null) return;
                Integer count = (Integer) param.args[countArg];
                String profile = BINDING.get();
                if (profile != null && count != null) SEEN.put(profile, followers, count, System.currentTimeMillis());
                bind(value, param.args[cellArg] instanceof View v ? v : null, profile,
                        viewerOf(sessionArg >= 0 ? param.args[sessionArg] : null), followers, count);
            } catch (Throwable t) {
                diagnostic("cell bind failed: " + t);
            }
        }
    };

    private static int[] countsAtAnalysis(String id, long now) {
        int[] counts = SEEN.counts(id, now);
        ModuleLog.detail(TAG + "baseline for " + id + ": " + (counts != null
                ? counts[0] + " followers, " + counts[1] + " following"
                : "none (its header didn't show both counts in the last 30 min)"));
        return counts;
    }

    private static String viewerOf(Object session) {
        try {
            return session != null && userId != null ? (String) userId.invoke(session) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void diagnostic(String message) {
        if (diagnostics.add(message)) ModuleLog.detail(TAG + message);
    }

    private static void bind(TextView value, View cell, String profile, String viewer, boolean followers, Integer count) {
        Cell state = value.getTag(R.id.ie_count_delta_cell) instanceof Cell c ? c : null;
        if (state == null) {
            if (!FeatureFlags.profileAnalyzer) return;
            ViewGroup host = cell != null && cell.getParent() instanceof ViewGroup row ? row
                    : value.getParent() instanceof ViewGroup parent ? parent : null;
            if (host == null) return;
            state = new Cell(value, Row.of(host));
            value.setTag(R.id.ie_count_delta_cell, state);
            diagnostic("header counts bound (" + (followers ? "followers" : "following") + ")");
        }
        state.profile = profile;
        state.viewer = viewer;
        state.followers = followers;
        state.count = count;
        state.shown = String.valueOf(value.getText());
        state.row.host.invalidate();
    }

    private static int[] baseline(Context ctx, String viewer, String profile) {
        Context app = ctx.getApplicationContext();
        String self = viewer != null ? viewer : WatchlistStore.self(app);
        if (self == null) return null;
        long version = WatchlistStore.indexVersion();
        boolean sameSelf = self.equals(baselinesSelf);
        if (!sameSelf || version != baselinesVersion) load(app, self, version);
        return sameSelf ? baselines.get(profile) : null;
    }

    private static void load(Context app, String self, long version) {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            Map<String, int[]> map = new HashMap<>();
            try {
                for (WatchlistStore.Entry e : WatchlistStore.loadIndex(app, self)) {
                    if (e.baseAt > 0) map.put(e.id, new int[]{e.baseFollowers, e.baseFollowing});
                }
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not read the watchlist, so count changes are not shown: " + t);
            }
            MAIN.post(() -> {
                baselines = map;
                baselinesSelf = self;
                baselinesVersion = version;
                loading = false;
                for (View host : new ArrayList<>(hosts)) host.invalidate();
            });
        }, "IE-CountDelta").start();
    }

    private static final class Row {
        final ViewGroup host;
        final List<Cell> cells = new ArrayList<>(2);

        private Row(ViewGroup host) {
            this.host = host;
            ViewTreeObserver.OnPreDrawListener sync = () -> {
                for (Cell cell : cells) cell.sync();
                return true;
            };
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    view.getViewTreeObserver().addOnPreDrawListener(sync);
                }

                @Override public void onViewDetachedFromWindow(View view) {
                    view.getViewTreeObserver().removeOnPreDrawListener(sync);
                }
            });
            if (host.isAttachedToWindow()) host.getViewTreeObserver().addOnPreDrawListener(sync);
            hosts.add(host);
        }

        static Row of(ViewGroup host) {
            if (host.getTag(R.id.ie_count_delta_row) instanceof Row row) {
                row.prune();
                return row;
            }
            Row row = new Row(host);
            host.setTag(R.id.ie_count_delta_row, row);
            return row;
        }

        private void prune() {
            float[] unused = new float[2];
            for (int i = cells.size() - 1; i >= 0; i--) {
                Cell cell = cells.get(i);
                if (offset(cell.value, host, unused)) continue;
                cells.remove(i);
                host.getOverlay().remove(cell.text);
                cell.value.setTag(R.id.ie_count_delta_cell, null);
            }
        }
    }

    private static final class Cell {
        final TextView value;
        final Row row;
        final DeltaText text = new DeltaText();
        String profile, viewer, shown;
        boolean followers;
        Integer count;
        private Typeface source, normal;
        private String keyProfile, keyLabel;
        private boolean keyFollowers;
        private int keyCount = -1, keyWas = -1, keyColor;
        private final float[] at = new float[2];

        Cell(TextView value, Row row) {
            this.value = value;
            this.row = row;
            row.cells.add(this);
            row.host.getOverlay().add(text);
        }

        void sync() {
            String label = null;
            Layout layout = value.getLayout();
            if (FeatureFlags.profileAnalyzer && profile != null && count != null && layout != null
                    && value.isShown() && shown != null && shown.contentEquals(value.getText())) {
                int[] base = baseline(value.getContext(), viewer, profile);
                if (base != null) {
                    int was = followers ? base[0] : base[1];
                    if (!profile.equals(keyProfile) || followers != keyFollowers || count != keyCount || was != keyWas) {
                        keyProfile = profile;
                        keyFollowers = followers;
                        keyCount = count;
                        keyWas = was;
                        keyLabel = ProfileCountDelta.label(count, was, locale(value));
                        keyColor = color(value.getContext(), keyLabel);
                        ModuleLog.probe(TAG + profile + (followers ? " followers " : " following ") + count + " vs " + was
                                + " at the last analysis: " + (keyLabel != null ? keyLabel : "unchanged"));
                    }
                    label = keyLabel;
                }
            }
            boolean changed = label != null && offset(value, row.host, at) ? place(label, layout) : text.hide();
            if (changed) row.host.invalidate();
        }

        private boolean place(String label, Layout layout) {
            Typeface typeface = value.getTypeface();
            if (normal == null || typeface != source) {
                source = typeface;
                normal = Typeface.create(typeface, Typeface.NORMAL);
            }
            float size = value.getTextSize() * 0.8f;
            float gap = size * 0.3f;
            float left = at[0] + value.getCompoundPaddingLeft() - value.getScrollX();
            float x = value.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? left + layout.getLineLeft(0) - gap - text.width(label, size, normal)
                    : left + layout.getLineRight(0) + gap;
            return text.show(label, keyColor, size, normal, x, at[1] + value.getBaseline());
        }
    }

    private static int color(Context ctx, String label) {
        int bg = IePalette.of(ctx).backdrop;
        return ProfileCountDelta.color(label, IeColors.success(ctx, bg), IeColors.destructive(ctx, bg));
    }

    private static Locale locale(View v) {
        try {
            return v.getResources().getConfiguration().getLocales().get(0);
        } catch (Throwable t) {
            return Locale.getDefault();
        }
    }

    private static boolean offset(View v, View host, float[] out) {
        float x = 0, y = 0;
        View c = v;
        while (c != host) {
            x += c.getLeft() + c.getTranslationX();
            y += c.getTop() + c.getTranslationY();
            if (!(c.getParent() instanceof View parent)) return false;
            if (parent != host) {
                x -= parent.getScrollX();
                y -= parent.getScrollY();
            }
            c = parent;
        }
        out[0] = x;
        out[1] = y;
        return true;
    }

    private static final class DeltaText extends Drawable {
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private String label;
        private float x, y;

        float width(String label, float size, Typeface typeface) {
            measure.setTextSize(size);
            measure.setTypeface(typeface);
            return measure.measureText(label);
        }

        boolean show(String label, int color, float size, Typeface typeface, float x, float y) {
            if (label.equals(this.label) && color == paint.getColor() && size == paint.getTextSize()
                    && typeface == paint.getTypeface() && x == this.x && y == this.y) return false;
            invalidateSelf();
            this.label = label;
            this.x = x;
            this.y = y;
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(typeface);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            setBounds((int) Math.floor(x), (int) Math.floor(y + metrics.top),
                    (int) Math.ceil(x + paint.measureText(label)), (int) Math.ceil(y + metrics.bottom));
            invalidateSelf();
            return true;
        }

        boolean hide() {
            if (label == null) return false;
            invalidateSelf();
            label = null;
            return true;
        }

        @Override public void draw(@NonNull Canvas canvas) {
            if (label != null) canvas.drawText(label, x, y, paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
