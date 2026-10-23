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

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

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
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.WatchlistStore;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Profile analyzer: how an analyzed profile's follower and following counts changed since its last
 * analysis, drawn right after each count in its profile header: "+5" in green, "-30" in red.
 * Unchanged counts, and profiles whose last analysis has no counts, show nothing.
 *
 * <p>Instagram binds the header's counts in one step of the profile header binder: the static
 * method with the "bindUserMetrics" trace (X.09ZU.A07 on IG 451). Its header state holds the
 * metrics (X.0Aoq: the profile id and the exact posts, followers and following Integers, built from
 * the profile's "follower_count" / "following_count" fields). Each count cell then goes through one
 * helper, the static method with the "UserMetricsBinderHelper.shrinkVanityMetrics" trace
 * (X.0AeX.A00), which gets the cell, its label and value TextViews, the session and the exact
 * Integer it writes, abbreviated, into the value TextView. So:
 * <ul>
 *   <li>before the metrics step, the profile id is read from its metrics: the one class whose three
 *       Integer fields the step reads, whose only String field is the id;</li>
 *   <li>after each cell, its exact count is kept; the cell is the followers or the following one by
 *       its value TextView's id (profile_header_familiar_followers_value / _following_value). The
 *       counts are also remembered per profile for WatchlistStore, so the next analysis of that
 *       profile that completes keeps them as its baseline.</li>
 * </ul>
 *
 * <p>The change is drawn in the overlay of the row holding the cells, at the number's baseline,
 * not appended to the number: Instagram auto-sizes the value text to its cell (8 to 16 sp), so a
 * longer text would shrink the number, and the cells stay untouched (tapping a count opens the
 * follow list, which the analyzer itself relies on). The header is reused across profiles: every
 * bind replaces a cell's profile and count, and an attach-scoped pre-draw sync (it returns true)
 * places the change and hides it once the value text is no longer the one its count was bound
 * with (another profile's header, or a count changed outside a bind).
 */
public final class ProfileCountDeltaHook {
    private static final String TAG = "(IE|CountDelta) ";
    private static final String KEY = "ProfileCountDelta";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final String FOLLOWERS_VALUE = "profile_header_familiar_followers_value";
    private static final String FOLLOWING_VALUE = "profile_header_familiar_following_value";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> diagnostics = Collections.synchronizedSet(new HashSet<>());

    /** Counts headers showed, per profile id: the baseline source of WatchlistStore.apply. */
    static final ProfileCountDelta.Seen SEEN = new ProfileCountDelta.Seen();
    /** The profile whose counts the metrics step is binding right now (it runs every cell's helper). */
    private static final ThreadLocal<String> BINDING = new ThreadLocal<>();

    // Metrics step: argument holding the header state, its metrics field (null: the argument is
    // the metrics), and the metrics' profile id.
    private static int stateArg = -1;
    private static Field metricsField, profileIdField;
    // Cell helper: argument positions.
    private static int cellArg = -1, countArg = -1, sessionArg = -1;
    private static final List<Integer> textArgs = new ArrayList<>();
    private static Method userId; // UserSession.getUserId()

    // Baselines of the index last loaded (profile id → {followers, following}). Read on the main
    // thread; a loader thread builds a new map and the main thread swaps it in.
    private static volatile Map<String, int[]> baselines = Collections.emptyMap();
    private static volatile String baselinesSelf;
    private static volatile long baselinesVersion = -1;
    private static boolean loading; // main thread
    private static final Set<View> hosts = Collections.newSetFromMap(new WeakHashMap<>()); // main thread

    public void install(DexKitBridge bridge, ClassLoader loader) {
        try {
            userId = Class.forName(SESSION, false, loader).getMethod("getUserId");
            MethodData step = single(bridge, "bindUserMetrics");
            MethodData helper = single(bridge, "UserMetricsBinderHelper.shrinkVanityMetrics");
            Method stepMethod = step.getMethodInstance(loader);
            Method cellMethod = helper.getMethodInstance(loader);
            resolveMetrics(step, stepMethod, loader);
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

    private static MethodData single(DexKitBridge bridge, String trace) {
        MethodDataList found = DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                .usingEqStrings(List.of(trace)).returnType(void.class)));
        if (found.size() != 1 || !found.get(0).isMethod())
            throw new IllegalStateException("'" + trace + "' matched " + found.size() + " methods");
        return found.get(0);
    }

    /** The metrics: the one class with three Integer fields the step reads (posts, followers,
     *  following); its only String field is the profile id. Then the step argument holding it. */
    private static void resolveMetrics(MethodData step, Method stepMethod, ClassLoader loader) throws Exception {
        if (!Modifier.isStatic(stepMethod.getModifiers())) throw new IllegalStateException("metrics step is not static");
        Map<Class<?>, Set<Field>> integers = new LinkedHashMap<>();
        for (var usage : step.getUsingFields()) {
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

    /** The helper's cell (a plain View), its TextViews, the one Integer it shows and the session. */
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

    // ---- hooks ----

    private static final XC_MethodHook STEP = new XC_MethodHook() {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
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
            JitGuard.tick(param.method);
            BINDING.remove();
        }
    };

    private static final XC_MethodHook CELL = new XC_MethodHook() {
        @Override protected void afterHookedMethod(MethodHookParam param) {
            JitGuard.tick(param.method);
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
                if (value == null) return; // the posts cell
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

    /** WatchlistStore.apply: the counts headers showed for {@code id} around this analysis. */
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

    // ---- the header ----

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
        state.row.host.invalidate(); // the pre-draw sync places it once laid out
    }

    /** The baseline {followers, following} of {@code profile} in {@code viewer}'s watchlist, or null. */
    private static int[] baseline(Context ctx, String viewer, String profile) {
        Context app = ctx.getApplicationContext();
        String self = viewer != null ? viewer : WatchlistStore.self(app);
        if (self == null) return null;
        long version = WatchlistStore.indexVersion();
        boolean sameSelf = self.equals(baselinesSelf);
        if (!sameSelf || version != baselinesVersion) load(app, self, version);
        // While a newer index loads, the older one still answers for the same account.
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

    /** The row holding the count cells: draws their changes in its overlay. */
    private static final class Row {
        final ViewGroup host;
        final List<Cell> cells = new ArrayList<>(2);

        private Row(ViewGroup host) {
            this.host = host;
            // Registered only while attached, like ProfileFollowStatusHook's label sync.
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
            // addOnAttachStateChangeListener doesn't replay an attach that already happened.
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

        /** Drops cells whose TextView Instagram has since replaced or moved out of this row. */
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

    /** One count (followers or following) of a header, as last bound, and its change. */
    private static final class Cell {
        final TextView value;
        final Row row;
        final DeltaText text = new DeltaText();
        String profile, viewer, shown;
        boolean followers;
        Integer count;
        private Typeface source, normal;
        // The last comparison: its label is formatted (and logged) once, not on every frame.
        private String keyProfile, keyLabel;
        private boolean keyFollowers;
        private int keyCount = -1, keyWas = -1;
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
                        ModuleLog.probe(TAG + profile + (followers ? " followers " : " following ") + count + " vs " + was
                                + " at the last analysis: " + (keyLabel != null ? keyLabel : "unchanged"));
                    }
                    label = keyLabel;
                }
            }
            boolean changed = label != null && offset(value, row.host, at) ? place(label, layout) : text.hide();
            // An overlay drawable that moves may not invalidate the area it moved into.
            if (changed) row.host.invalidate();
        }

        /** Right after the number on its line (left of it in RTL), at the number's baseline. */
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
            return text.show(label, ProfileCountDelta.color(label), size, normal, x, at[1] + value.getBaseline());
        }
    }

    private static Locale locale(View v) {
        try {
            return v.getResources().getConfiguration().getLocales().get(0);
        } catch (Throwable t) {
            return Locale.getDefault();
        }
    }

    /** {@code v}'s top-left in {@code host}'s overlay coordinates; false when it isn't inside it. */
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

    /** The change's text, drawn at a baseline position in the host's overlay. */
    private static final class DeltaText extends Drawable {
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG); // leaves paint's state for show()
        private String label;
        private float x, y;

        float width(String label, float size, Typeface typeface) {
            measure.setTextSize(size);
            measure.setTypeface(typeface);
            return measure.measureText(label);
        }

        /** Shows {@code label} at ({@code x}, baseline {@code y}); true when anything changed. */
        boolean show(String label, int color, float size, Typeface typeface, float x, float y) {
            if (label.equals(this.label) && color == paint.getColor() && size == paint.getTextSize()
                    && typeface == paint.getTypeface() && x == this.x && y == this.y) return false;
            invalidateSelf(); // the area it leaves
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
