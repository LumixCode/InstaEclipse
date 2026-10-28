package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.widget.TextView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.text.CopyFeedback;

/**
 * Story link stickers: when the open story has link stickers, the story ⋯ menu gets
 * "Copy Link" (one link) or "Story Links (n)" (several, listed in a dialog).
 *
 * Links come from Media's paramCount-0 List getters guarded by the "story_link_stickers" /
 * "story_cta" JSON keys (same idea as StoryMentionHook's "reel_mentions"), and the URLs are
 * collected by walking the sticker objects for http(s) strings, so no obfuscated names are used.
 */
public class StoryLinksHook {

    private static final String[] KEYS = {"story_link_stickers", "story_cta"};
    private static final List<Method> getters = new ArrayList<>();

    public void install(ClassLoader classLoader) {
        resolveGetters(classLoader);
        if (getters.isEmpty()) ModuleLog.error("(IE|StoryLinks) Could not turn on story links: link stickers were not found in this Instagram version");
        boolean menu = StoryMenu.install(classLoader);
        if (!menu) ModuleLog.error("(IE|StoryLinks) Could not turn on story links: the story menu was not found in this Instagram version");
        if (!menu || getters.isEmpty()) {
            if (FeatureFlags.storyLinks) FeatureStatusTracker.setBroken("StoryLinks");
            return;
        }
        StoryMenu.register(StoryMenu.LINKS, new StoryMenu.Row() {
            @Override
            public boolean enabled() {
                return FeatureFlags.storyLinks;
            }

            @Override
            public void rows(Object media, List<CharSequence> out) {
                if (media == null) return;
                List<String> links = linksOf(media);
                if (!links.isEmpty()) out.add(label(links.size()));
            }

            @Override
            public boolean owns(CharSequence tapped) {
                Context app = AndroidAppHelper.currentApplication();
                return StoryMenuRows.owns(tapped, I18n.t(app, R.string.ig_btn_copy_sticker_link), false)
                        || StoryMenuRows.owns(tapped, I18n.t(app, R.string.ig_sticker_links_title), true);
            }

            @Override
            public void tap(CharSequence tapped, Object media, Context ctx) {
                List<String> links = linksOf(media);
                if (links.size() == 1) CopyFeedback.copy(ctx, "link", links.get(0), R.string.ig_toast_link_copied);
                else if (!links.isEmpty()) showLinks(ctx, links);
            }
        });
        FeatureStatusTracker.setHooked("StoryLinks");
        ModuleLog.ok("(IE|StoryLinks) Ready");
    }

    // ── DexKit ────────────────────────────────────────────────────────────────

    private static void resolveGetters(ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("StoryLinkGetters", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) m.setAccessible(true);
                getters.addAll(cached);
                return;
            }
        }
        for (String key : KEYS) {
            try {
                List<Found> found = DexKitQueryCache.methods(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("com.instagram.feed.media.Media")
                                .paramCount(0)
                                .usingEqStrings(List.of(key))));
                for (Found md : found) {
                    if (md.getName().equals("<clinit>")) continue;
                    Method m = md.getMethodInstance(classLoader);
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    getters.add(m);
                    break;
                }
            } catch (Throwable t) {
                ModuleLog.detail("(IE|StoryLinks) getter " + key + ": " + t);
            }
        }
        if (!getters.isEmpty()) DexKitCache.saveMethods("StoryLinkGetters", getters);
    }

    // ── Link extraction ──────────────────────────────────────────────────────

    static List<String> linksOf(Object media) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Method g : getters) {
            try {
                Object r = g.invoke(media);
                if (r instanceof List<?> list)
                    for (Object sticker : list)
                        collectUrls(sticker, 0, out, Collections.newSetFromMap(new IdentityHashMap<>()));
            } catch (Throwable ignored) {}
        }
        return new ArrayList<>(out);
    }

    private static void collectUrls(Object o, int depth, Set<String> out, Set<Object> seen) {
        if (o == null || depth > 4) return;
        if (o instanceof CharSequence cs) {
            String s = unwrap(cs.toString().trim());
            if (s != null) out.add(s);
            return;
        }
        if (o instanceof Number || o instanceof Boolean || o.getClass().isPrimitive() || o.getClass().isEnum()) return;
        if (!seen.add(o)) return;
        if (o instanceof Map<?, ?> m) {
            for (Object v : m.values()) collectUrls(v, depth + 1, out, seen);
            return;
        }
        if (o instanceof Collection<?> c) {
            for (Object v : c) collectUrls(v, depth + 1, out, seen);
            return;
        }
        String n = o.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.") && !n.startsWith("com.facebook.")) return;
        for (Class<?> cls = o.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    collectUrls(f.get(o), depth + 1, out, seen);
                } catch (Throwable ignored) {}
            }
        }
    }

    /** The real target of an http(s) string; l.instagram.com redirects are unwrapped. */
    private static String unwrap(String s) {
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
        String target = ExternalLinkPolicy.shimTarget(s);
        if (target != null && target.startsWith("http")) return ExternalLinkPolicy.stripFbclid(target);
        try {
            String host = Uri.parse(s).getHost();
            if (host != null && (host.contains("cdninstagram.com") || host.contains("fbcdn.net"))) return null;
        } catch (Throwable ignored) {}
        return ExternalLinkPolicy.stripFbclid(s);
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private static String label(int count) {
        Context app = AndroidAppHelper.currentApplication();
        return count == 1 ? I18n.t(app, R.string.ig_btn_copy_sticker_link)
                : I18n.t(app, R.string.ig_sticker_links_title) + " (" + count + ")";
    }

    private static void showLinks(Context ctx, List<String> links) {
        try {
            linksDialog(ctx, links).show();
        } catch (Throwable t) {
            ModuleLog.error("(IE|StoryLinks) Could not show the story's links", t);
        }
    }

    static IeDialog linksDialog(Context ctx, List<String> links) {
        IeDialog dlg = new IeDialog(ctx)
                .title(I18n.t(ctx, R.string.ig_sticker_links_title))
                .message(I18n.t(ctx, R.string.ig_story_links_hint));
        for (String url : links) {
            TextView row = new TextView(ctx);
            row.setText(url);
            row.setTextColor(dlg.text);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setMaxLines(3);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(dlg.dp(16), dlg.dp(13), dlg.dp(14), dlg.dp(13));
            row.setBackground(dlg.cardBg());
            row.setOnClickListener(v -> CopyFeedback.copy(ctx, "link", url, R.string.ig_toast_link_copied));
            row.setOnLongClickListener(v -> {
                try {
                    ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable ignored) {}
                return true;
            });
            dlg.view(row);
        }
        dlg.primary(I18n.t(ctx, R.string.ig_mention_copy_all),
                () -> CopyFeedback.copy(ctx, "links", String.join("\n", links), R.string.ig_toast_links_copied));
        return dlg;
    }
}
