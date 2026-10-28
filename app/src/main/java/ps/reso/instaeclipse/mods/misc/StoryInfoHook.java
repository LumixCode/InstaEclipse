package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.utils.text.TimestampFormat;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;

import static ps.reso.instaeclipse.mods.misc.StoryInfoPolicy.find;
import static ps.reso.instaeclipse.mods.misc.StoryInfoPolicy.num;
import static ps.reso.instaeclipse.mods.misc.StoryInfoPolicy.plain;
import static ps.reso.instaeclipse.mods.misc.StoryInfoPolicy.str;

/**
 * Story ⋯ menu extras: "Story Info" (exact times, music, location, hashtags, reshare source, app,
 * alt text, size, countdown, question, highlights) plus "Quiz Answer" / "Poll Results" on stories
 * that carry those stickers.
 *
 * Each story field is read through Media's paramCount-0 getter guarded by its JSON key (found by
 * DexKit). The returned models are turned into plain maps with their JSON keys through the
 * models' own 0-arg Map serializer, so every value below is looked up by its API key name,
 * never by an obfuscated field name.
 */
public class StoryInfoHook {

    private static final String[] KEYS = {
            "taken_at", "expiring_at", "story_edited_at", "is_story_edited",
            "music_metadata", "story_music_stickers", "story_music_lyric_stickers", "clips_metadata",
            "story_locations", "story_hashtags",
            "reshared_story_media_author", "story_feed_media", "story_app_attribution",
            "accessibility_caption", "original_width", "original_height", "has_audio",
            "story_countdowns", "story_questions", "story_mention_request_info",
            "story_quizs", "story_polls", "story_sliders", "story_prompts", "sponsor_tags", "is_paid_partnership", "coauthor_producers",
    };
    private static final Map<String, Method> getters = new LinkedHashMap<>();
    private static String[] pendingKeys;
    private static ClassLoader pendingLoader;

    public void install(ClassLoader classLoader) {
        boolean resolved = resolveGetters(classLoader);
        if (!resolved) ModuleLog.error("(IE|StoryInfo) Could not turn on story info: the story details were not found in this Instagram version");
        boolean menu = StoryMenu.install(classLoader);
        if (!menu) ModuleLog.error("(IE|StoryInfo) Could not turn on story info: the story menu was not found in this Instagram version");
        if (!menu || !resolved) {
            if (FeatureFlags.storyInfo) FeatureStatusTracker.setBroken("StoryInfo");
            return;
        }
        StoryMenu.register(StoryMenu.INFO, new StoryMenu.Row() {
            @Override
            public boolean enabled() {
                return FeatureFlags.storyInfo;
            }

            @Override
            public void rows(Object media, List<CharSequence> out) {
                if (media == null) return;
                ensureGetters();
                Context app = AndroidAppHelper.currentApplication();
                out.add(I18n.t(app, R.string.ig_btn_story_info));
                if (!list(media, "story_quizs").isEmpty()) out.add(I18n.t(app, R.string.ig_btn_quiz_answer));
                if (!list(media, "story_polls").isEmpty()) out.add(I18n.t(app, R.string.ig_btn_poll_results));
                if (!list(media, "story_sliders").isEmpty()) out.add(I18n.t(app, R.string.ig_btn_slider_results));
            }

            @Override
            public boolean owns(CharSequence tapped) {
                return which(tapped) >= 0;
            }

            @Override
            public void tap(CharSequence tapped, Object media, Context ctx) {
                ensureGetters();
                int which = which(tapped);
                if (which == 0) showInfo(ctx, media);
                else if (which == 1) showQuiz(ctx, media);
                else if (which == 2) showPolls(ctx, media);
                else if (which == 3) showSliders(ctx, media);
            }
        });
        FeatureStatusTracker.setHooked("StoryInfo");
        ModuleLog.ok("(IE|StoryInfo) Ready");
    }

    private static int which(CharSequence tapped) {
        Context app = AndroidAppHelper.currentApplication();
        if (StoryMenuRows.owns(tapped, I18n.t(app, R.string.ig_btn_story_info), false)) return 0;
        if (StoryMenuRows.owns(tapped, I18n.t(app, R.string.ig_btn_quiz_answer), false)) return 1;
        if (StoryMenuRows.owns(tapped, I18n.t(app, R.string.ig_btn_poll_results), false)) return 2;
        if (StoryMenuRows.owns(tapped, I18n.t(app, R.string.ig_btn_slider_results), false)) return 3;
        return -1;
    }

    private static synchronized void ensureGetters() {
        String[] keys = pendingKeys;
        if (keys == null) return;
        pendingKeys = null;
        for (String key : keys) {
            if (key.isEmpty()) continue;
            Method m = DexKitCache.loadMethod("StoryInfo_" + key, pendingLoader);
            if (m != null) { m.setAccessible(true); getters.put(key, m); }
        }
        pendingLoader = null;
        if (getters.isEmpty()) ModuleLog.error("(IE|StoryInfo) Could not show the story info: the saved story details no longer match this Instagram version");
    }

    private static synchronized boolean resolveGetters(ClassLoader classLoader) {
        String queried = String.join(",", KEYS);
        if (DexKitCache.isCacheValid() && queried.equals(DexKitCache.loadString("StoryInfoQueried"))) {
            String found = DexKitCache.loadString("StoryInfoKeys");
            if (found != null && !found.replace(",", "").isEmpty()) {
                pendingKeys = found.split(",");
                pendingLoader = classLoader;
                return true;
            }
        }
        StringBuilder keys = new StringBuilder();
        for (String key : KEYS) {
            try {
                List<Found> found = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("com.instagram.feed.media.Media")
                                .paramCount(0)
                                .usingEqStrings(List.of(key))));
                for (Found md : found) {
                    if (md.getName().startsWith("<")) continue;
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() == void.class || Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    getters.put(key, m);
                    DexKitCache.saveMethod("StoryInfo_" + key, m);
                    keys.append(key).append(',');
                    break;
                }
            } catch (Throwable t) {
                ModuleLog.detail("(IE|StoryInfo) getter " + key + ": " + t);
            }
        }
        DexKitCache.saveString("StoryInfoKeys", keys.toString());
        DexKitCache.saveString("StoryInfoQueried", queried);
        if (!getters.isEmpty()) ModuleLog.detail("(IE|StoryInfo) getters: " + getters.keySet());
        return !getters.isEmpty();
    }

    // ── Reading values ───────────────────────────────────────────────────────

    private static Object raw(Object media, String key) {
        Method g = getters.get(key);
        if (g == null) return null;
        try { return g.invoke(media); } catch (Throwable t) { return null; }
    }

    /** The field as a JSON-like tree: Map (JSON keys), List, String, Number, Boolean. */
    static Object value(Object media, String key) {
        return tree(raw(media, key), 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    static List<Object> list(Object media, String key) {
        Object v = value(media, key);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o);
            return out;
        }
        return v == null ? Collections.emptyList() : Collections.singletonList(v);
    }

    private static final Map<Class<?>, Method> mapMethods = new ConcurrentHashMap<>();
    private static final Method NONE;
    static { try { NONE = Object.class.getMethod("toString"); } catch (NoSuchMethodException e) { throw new RuntimeException(e); } }

    private static final Map<Class<?>, Object> serializerArgs = new ConcurrentHashMap<>();

    /**
     * The model's own serializer to a JSON-keyed Map: the synthetic {@code Map x(Options)} (full map,
     * its options object has a no-arg constructor) or the synthetic {@code Map x()}.
     */
    private static Method mapMethod(Class<?> c) {
        Method m = mapMethods.get(c);
        if (m == null) {
            Method zero = null, one = null;
            for (Method cand : ps.reso.instaeclipse.utils.core.SafeReflect.methods(c)) {
                if (cand.getReturnType() != Map.class || Modifier.isStatic(cand.getModifiers()) || !cand.isSynthetic()) continue;
                if (cand.getParameterCount() == 0 && zero == null) zero = cand;
                else if (cand.getParameterCount() == 1 && one == null
                        && cand.getParameterTypes()[0].getName().startsWith("X.") && serializerArg(cand.getParameterTypes()[0]) != null) one = cand;
            }
            m = one != null ? one : zero != null ? zero : NONE;
            if (m != NONE) m.setAccessible(true);
            mapMethods.put(c, m);
        }
        return m == NONE ? null : m;
    }

    private static Object serializerArg(Class<?> type) {
        Object a = serializerArgs.get(type);
        if (a == null) {
            try {
                java.lang.reflect.Constructor<?> k = type.getDeclaredConstructor();
                k.setAccessible(true);
                a = k.newInstance();
            } catch (Throwable t) {
                a = NONE;
            }
            serializerArgs.put(type, a);
        }
        return a == NONE ? null : a;
    }

    private static Object tree(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 7) return null;
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) return o instanceof CharSequence ? o.toString() : o;
        if (o instanceof Enum<?> e) return e.toString();
        if (!seen.add(o)) return null;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.equals("dash_manifest")) continue;
                Object v = tree(e.getValue(), depth + 1, seen);
                if (v != null) out.put(key, v);
            }
            return out;
        }
        if (o instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object v : c) { Object t = tree(v, depth + 1, seen); if (t != null) out.add(t); }
            return out;
        }
        String n = o.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.") && !n.startsWith("com.facebook.")) return o.toString();
        Method mm = mapMethod(o.getClass());
        if (mm != null) {
            try {
                Object r = mm.getParameterCount() == 0 ? mm.invoke(o)
                        : mm.invoke(o, serializerArg(mm.getParameterTypes()[0]));
                if (r instanceof Map) return tree(r, depth, seen);
            } catch (Throwable ignored) {}
        }
        // No serializer (e.g. sticker wrappers): keep its non-null fields under their raw names,
        // so the named models inside them are still reachable by find().
        Map<String, Object> out = new LinkedHashMap<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = tree(f.get(o), depth + 1, seen);
                    if (v != null) out.put("_" + f.getName(), v);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    private static String time(Context ctx, Object v) {
        Long secs = num(v);
        if (secs == null || secs <= 0) return null;
        if (secs > 100_000_000_000L) secs /= 1000;
        return TimestampFormat.dialogDateTime(ctx, secs * 1000);
    }

    // ── Story Info ───────────────────────────────────────────────────────────

    private static void showInfo(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_story_info))
                .message(I18n.t(ctx, R.string.ig_story_info_hint));
        int rows = 0;

        rows += row(dlg, ctx, R.string.ig_si_posted, time(ctx, value(media, "taken_at")), null);
        rows += row(dlg, ctx, R.string.ig_si_expires, time(ctx, value(media, "expiring_at")), null);
        String edited = time(ctx, value(media, "story_edited_at"));
        if (edited == null && Boolean.TRUE.equals(value(media, "is_story_edited"))) edited = I18n.t(ctx, R.string.ig_si_yes);
        rows += row(dlg, ctx, R.string.ig_si_edited, edited, null);

        if (addMusic(dlg, ctx, media)) rows++;

        List<String> places = new ArrayList<>();
        for (Object loc : list(media, "story_locations")) {
            String name = str(find(loc, "location"), "name");
            if (name == null) name = str(loc, "name");
            if (name != null && !places.contains(name)) places.add(name);
        }
        rows += row(dlg, ctx, R.string.ig_si_location, join(places, "\n"), null);

        List<String> tags = new ArrayList<>();
        for (Object h : list(media, "story_hashtags")) {
            String name = str(find(h, "hashtag"), "name");
            if (name == null) name = str(h, "name");
            if (name != null && !tags.contains("#" + name)) tags.add("#" + name);
        }
        rows += row(dlg, ctx, R.string.ig_si_hashtags, join(tags, " "), null);

        Object authorUser = raw(media, "reshared_story_media_author");
        String author = authorUser == null ? null : ps.reso.instaeclipse.utils.users.UserUtils.callUsernameGetter(authorUser);
        List<String> posts = new ArrayList<>();
        for (Object fm : list(media, "story_feed_media")) {
            String code = str(fm, "media_code");
            if (code == null) code = str(fm, "code");
            boolean reel = "clips".equals(str(fm, "product_type"));
            if (code != null) posts.add("https://www.instagram.com/" + (reel ? "reel/" : "p/") + code + "/");
            if (author == null) author = str(find(fm, "user"), "username");
        }
        rows += row(dlg, ctx, R.string.ig_si_shared_from, author == null ? null : "@" + author, null);
        for (String p : posts) rows += row(dlg, ctx, R.string.ig_si_original_post, p, p);

        // Paid partnership / collab partners are not stickers, so Mentions never lists them.
        List<String> sponsors = usernames(raw(media, "sponsor_tags"));
        for (String u : sponsors)
            rows += row(dlg, ctx, R.string.ig_si_paid_partnership, "@" + u, "https://www.instagram.com/" + u + "/");
        if (sponsors.isEmpty() && Boolean.TRUE.equals(value(media, "is_paid_partnership")))
            rows += row(dlg, ctx, R.string.ig_si_paid_partnership, I18n.t(ctx, R.string.ig_si_yes), null);
        for (String u : usernames(raw(media, "coauthor_producers")))
            if (!sponsors.contains(u))
                rows += row(dlg, ctx, R.string.ig_si_collaborators, "@" + u, "https://www.instagram.com/" + u + "/");

        Object app = value(media, "story_app_attribution");
        String appName = str(app, "name");
        if (appName == null) appName = str(app, "app_action_text");
        String appLink = str(app, "link");
        if (appLink == null) appLink = str(app, "content_url");
        rows += row(dlg, ctx, R.string.ig_si_posted_with,
                appName == null ? appLink : (appLink == null ? appName : appName + "\n" + appLink), appLink);

        rows += row(dlg, ctx, R.string.ig_si_alt_text, plain(value(media, "accessibility_caption")), null);

        Long w = num(value(media, "original_width")), h = num(value(media, "original_height"));
        String size = w != null && h != null && w > 0 && h > 0 ? w + " × " + h : null;
        Object audio = value(media, "has_audio");
        if (audio instanceof Boolean b)
            size = (size == null ? "" : size + " · ") + I18n.t(ctx, b ? R.string.ig_si_sound_on : R.string.ig_si_sound_off);
        rows += row(dlg, ctx, R.string.ig_si_size, size, null);

        for (Object cd : list(media, "story_countdowns")) {
            Object c = find(cd, "countdown_sticker");
            if (c == null) c = cd;
            String text = str(c, "text");
            String end = time(ctx, find(c, "end_ts"));
            rows += row(dlg, ctx, R.string.ig_si_countdown,
                    text == null ? end : (end == null ? text : text + "\n" + end), null);
        }
        for (Object q : list(media, "story_questions")) {
            rows += row(dlg, ctx, R.string.ig_si_question, str(q, "question"), null);
        }
        for (Object pr : list(media, "story_prompts")) {
            Object sticker = find(pr, "prompt_sticker");
            if (sticker == null) sticker = pr;
            StringBuilder sb = new StringBuilder();
            String text = str(sticker, "text");
            if (text != null) sb.append(text).append('\n');
            Long count = num(find(sticker, "participant_count"));
            if (count != null) sb.append(java.text.NumberFormat.getIntegerInstance().format(count))
                    .append(' ').append(I18n.t(ctx, R.string.ig_si_joined));
            List<String> faces = new ArrayList<>();
            Object top = find(sticker, "facepile_top_participants");
            if (top instanceof List<?> users)
                for (Object u : users) { String n = str(u, "username"); if (n != null && !faces.contains("@" + n)) faces.add("@" + n); }
            if (!faces.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append(String.join(", ", faces));
            rows += row(dlg, ctx, R.string.ig_si_add_yours, sb.toString().trim(), null);
        }
        Object req = value(media, "story_mention_request_info");
        if (req != null && !(req instanceof List<?> l && l.isEmpty()))
            rows += row(dlg, ctx, R.string.ig_si_mention_request, I18n.t(ctx, R.string.ig_si_yes), null);

        if (rows == 0) dlg.message(I18n.t(ctx, R.string.ig_si_none));
        dlg.show();
    }

    /** Adds the Music row and, for licensed songs, the streaming buttons; for posts and reels
     *  (Media Info) as well as stories. Returns false when the media has no music. */
    public static boolean addMusic(IeDialog dlg, Context ctx, Object media) {
        ensureGetters();
        String[] song;
        try { song = StoryInfoPolicy.music(k -> value(media, k)); } catch (Throwable t) { return false; }
        if (song == null) return false;
        row(dlg, ctx, R.string.ig_si_music, song[0], null);
        if (song[1] != null) dlg.view(musicLinks(dlg, ctx, song[1]));
        return true;
    }

    /** Spotify / YouTube Music / Apple Music search buttons for a song. */
    private static LinearLayout musicLinks(IeDialog dlg, Context ctx, String query) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        String q = Uri.encode(query);
        Object[][] apps = {
                {"Spotify", "https://open.spotify.com/search/" + q, R.drawable.ic_logo_spotify},
                {"YouTube Music", "https://music.youtube.com/search?q=" + q, R.drawable.ic_logo_ytmusic},
                {"Apple Music", "https://music.apple.com/search?term=" + q, R.drawable.ic_logo_applemusic},
        };
        for (int i = 0; i < apps.length; i++) {
            Object[] a = apps[i];
            String url = (String) a[1];
            android.widget.ImageView b = new android.widget.ImageView(ctx);
            // In its own colours (no tint); read from the loaded module APK.
            android.graphics.drawable.Drawable logo = ModuleResources.drawable(ctx, (Integer) a[2]);
            if (logo != null) b.setImageDrawable(logo);
            b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            b.setContentDescription((String) a[0]);
            b.setPadding(dlg.dp(6), dlg.dp(9), dlg.dp(6), dlg.dp(9));
            b.setBackground(dlg.cardBg());
            b.setOnClickListener(v -> open(ctx, url));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dlg.dp(50), 1f);
            if (i > 0) lp.setMarginStart(dlg.dp(8));
            bar.addView(b, lp);
        }
        return bar;
    }

    private static String join(List<String> parts, String sep) {
        return parts.isEmpty() ? null : String.join(sep, parts);
    }

    /** One "label / value" row; tap copies the value, long-press opens {@code link}. */
    private static int row(IeDialog dlg, Context ctx, int labelRes, String value, String link) {
        if (value == null || value.isEmpty()) return 0;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dlg.dp(16), dlg.dp(10), dlg.dp(14), dlg.dp(11));
        box.setBackground(dlg.cardBg());
        TextView label = new TextView(ctx);
        label.setText(I18n.t(ctx, labelRes));
        label.setTextColor(dlg.accent);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setPadding(0, 0, 0, dlg.dp(2));
        TextView val = new TextView(ctx);
        val.setText(value);
        val.setTextColor(dlg.text);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        val.setMaxLines(8);
        box.addView(label);
        box.addView(val);
        box.setOnClickListener(v -> copy(ctx, value));
        if (link != null) box.setOnLongClickListener(v -> { open(ctx, link); return true; });
        dlg.view(box);
        return 1;
    }

    // ── Quiz / polls ─────────────────────────────────────────────────────────

    private static List<Object> stickers(Object media, String key, String inner) {
        List<Object> out = new ArrayList<>();
        for (Object item : list(media, key)) {
            Object sticker = find(item, inner);
            out.add(sticker == null ? item : sticker);
        }
        return out;
    }

    private static void showQuiz(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_quiz_answer));
        for (Object quiz : stickers(media, "story_quizs", "quiz_sticker")) {
            String question = str(quiz, "question");
            if (question != null) dlg.view(heading(dlg, ctx, question));
            List<StoryInfoPolicy.Choice> choices = StoryInfoPolicy.quiz(quiz);
            for (StoryInfoPolicy.Choice c : choices) {
                int fill = c.correct ? 0x8C30D158 : c.picked ? withAlpha(dlg.accent, 0x8C) : withAlpha(dlg.text, 0x22);
                dlg.view(bar(dlg, ctx, c, fill));
            }
            if (StoryInfoPolicy.anyPicked(choices))
                dlg.view(note(dlg, ctx, "•  " + I18n.t(ctx, R.string.ig_si_your_answer)));
        }
        dlg.show();
    }

    private static void showPolls(Context ctx, Object media) {
        pollDialog(ctx, stickers(media, "story_polls", "poll_sticker")).show();
    }

    static IeDialog pollDialog(Context ctx, List<Object> polls) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_poll_results));
        for (Object poll : polls) {
            String question = str(poll, "question");
            if (question != null) dlg.view(heading(dlg, ctx, question));
            List<StoryInfoPolicy.Choice> choices = StoryInfoPolicy.poll(poll);
            if (choices.isEmpty()) continue;
            for (StoryInfoPolicy.Choice c : choices) {
                int fill = c.picked ? withAlpha(dlg.accent, 0x8C) : withAlpha(dlg.text, c.bold ? 0x33 : 0x1C);
                dlg.view(bar(dlg, ctx, c, fill));
            }
            if (StoryInfoPolicy.anyPicked(choices))
                dlg.view(note(dlg, ctx, "•  " + I18n.t(ctx, R.string.ig_poll_your_vote)));
            long total = StoryInfoPolicy.totalVotes(poll);
            if (total > 0) dlg.view(note(dlg, ctx, votesLine(ctx, total)));
        }
        return dlg;
    }

    private static void showSliders(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_slider_results));
        for (Object slider : stickers(media, "story_sliders", "slider_sticker")) {
            dlg.view(heading(dlg, ctx, StoryInfoPolicy.sliderHeading(slider)));
            for (StoryInfoPolicy.Choice c : StoryInfoPolicy.slider(slider,
                    I18n.t(ctx, R.string.ig_si_average), I18n.t(ctx, R.string.ig_si_your_answer))) {
                dlg.view(bar(dlg, ctx, c, c.picked ? withAlpha(dlg.accent, 0x8C) : withAlpha(dlg.text, 0x33)));
            }
            Long count = StoryInfoPolicy.sliderVotes(slider);
            if (count != null) dlg.view(note(dlg, ctx, votesLine(ctx, count)));
        }
        dlg.show();
    }

    private static String votesLine(Context ctx, long n) {
        String w = I18n.t(ctx, R.string.ig_si_votes);
        return (w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1)) + ": " + n;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static android.view.View bar(IeDialog dlg, Context ctx, StoryInfoPolicy.Choice choice, int fill) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dlg.dp(16), dlg.dp(13), dlg.dp(16), dlg.dp(13));
        android.graphics.drawable.ClipDrawable filled = new android.graphics.drawable.ClipDrawable(
                dlg.round(fill, 14), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        filled.setLevel((int) Math.round(Math.max(0, Math.min(1, choice.share)) * 10000));
        row.setBackground(new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{dlg.round(dlg.field, 14), filled}));

        TextView l = new TextView(ctx);
        l.setText(choice.label);
        l.setTextColor(dlg.text);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (choice.bold) l.setTypeface(null, Typeface.BOLD);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView r = new TextView(ctx);
        r.setText(choice.votes);
        r.setTextColor(dlg.text);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        r.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMarginStart(dlg.dp(10));
        row.addView(r, rp);

        row.setOnClickListener(v -> copy(ctx, choice.copy));
        return row;
    }

    private static TextView note(IeDialog dlg, Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(dlg.sub);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dlg.dp(4), 0, dlg.dp(4), dlg.dp(4));
        return t;
    }

    private static TextView heading(IeDialog dlg, Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(dlg.text);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(dlg.dp(4), dlg.dp(6), dlg.dp(4), dlg.dp(2));
        return t;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void copy(Context ctx, String text) {
        CopyFeedback.copy(ctx, "story", text, R.string.ig_si_copied);
    }

    private static void open(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (url.startsWith("https://www.instagram.com/")) i.setPackage(ctx.getPackageName());
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** Usernames of every User found in a raw model graph (User objects or JSON-keyed user maps). */
    private static List<String> usernames(Object root) {
        List<String> out = new ArrayList<>();
        collectUsers(root, 0, out, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (out.isEmpty()) {
            Object t = tree(root, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            collectUsernameKeys(t, out);
        }
        return out;
    }

    private static void collectUsers(Object o, int depth, List<String> out, Set<Object> seen) {
        if (o == null || depth > 5 || o instanceof CharSequence || o instanceof Number || o instanceof Boolean) return;
        if (!seen.add(o)) return;
        if (o.getClass().getName().equals("com.instagram.user.model.User")) {
            String u = ps.reso.instaeclipse.utils.users.UserUtils.callUsernameGetter(o);
            if (u != null && !u.isEmpty() && !out.contains(u)) out.add(u);
            return;
        }
        if (o instanceof Collection<?> c) { for (Object v : c) collectUsers(v, depth + 1, out, seen); return; }
        if (o instanceof Map<?, ?> m) { for (Object v : m.values()) collectUsers(v, depth + 1, out, seen); return; }
        String n = o.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.")) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try { f.setAccessible(true); collectUsers(f.get(o), depth + 1, out, seen); } catch (Throwable ignored) {}
            }
    }

    private static void collectUsernameKeys(Object t, List<String> out) {
        if (t instanceof Map<?, ?> m) {
            Object u = m.get("username");
            if (u instanceof String s && !s.isEmpty() && !out.contains(s)) out.add(s);
            for (Object v : m.values()) collectUsernameKeys(v, out);
        } else if (t instanceof List<?> l) {
            for (Object v : l) collectUsernameKeys(v, out);
        }
    }
}
