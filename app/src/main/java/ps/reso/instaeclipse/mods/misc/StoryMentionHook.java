package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.TextView;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.text.CopyFeedback;

public class StoryMentionHook {

    private static volatile Method rawMentionsGetter;
    private static volatile Method mentionsConverter;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void install(ClassLoader classLoader) {
        resolveMentionPipeline(classLoader);
        boolean menu = StoryMenu.install(classLoader);
        if (!menu || rawMentionsGetter == null || mentionsConverter == null) {
            if (!menu) ModuleLog.error("(IE|Mention) Could not turn on story mentions: the story menu was not found in this Instagram version");
            if (FeatureFlags.enableStoryMentions) FeatureStatusTracker.setBroken("StoryMentions");
            return;
        }
        StoryMenu.register(StoryMenu.MENTIONS, new StoryMenu.Row() {
            @Override
            public boolean enabled() {
                return FeatureFlags.enableStoryMentions;
            }

            @Override
            public void rows(Object media, List<CharSequence> out) {
                int count = resolveMentions(media).size();
                if (count > 0) out.add(label() + " (" + count + ")");
            }

            @Override
            public boolean owns(CharSequence tapped) {
                return StoryMenuRows.owns(tapped, label(), true);
            }

            @Override
            public void tap(CharSequence tapped, Object media, Context ctx) {
                showMentionsDialog(ctx, resolveMentions(media));
            }
        });
        FeatureStatusTracker.setHooked("StoryMentions");
        ModuleLog.ok("(IE|Mention) Ready");
    }

    private static String label() {
        return I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_btn_view_mentions);
    }

    // ── DexKit: resolve the two-step mention pipeline ────────────────────────

    private static void resolveMentionPipeline(ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method g = DexKitCache.loadMethod("MentionsRawGetter", classLoader);
            Method c = DexKitCache.loadMethod("MentionsConverter", classLoader);
            if (g != null && c != null) {
                rawMentionsGetter = g;
                mentionsConverter = c;
                ModuleLog.detail("(IE|Mention) pipeline resolved from cache");
                return;
            }
        }

        try {
            List<Found> getters = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("com.instagram.feed.media.Media")
                            .paramCount(0)
                            .usingEqStrings(List.of("reel_mentions"))));
            for (Found md : getters) {
                if (md.getName().equals("<clinit>")) continue;
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    rawMentionsGetter = m;
                    DexKitCache.saveMethod("MentionsRawGetter", m);
                    break;
                } catch (Throwable ignored) {}
            }
            if (rawMentionsGetter == null) ModuleLog.error("(IE|Mention) Could not turn on story mentions: the story's mention list was not found in this Instagram version");
        } catch (Throwable t) {
            ModuleLog.error("(IE|Mention) Could not turn on story mentions: the story's mention list could not be looked up: " + t);
        }

        try {
            List<Found> converters = DexKitQueryCache.methods(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramCount(1)
                            .usingEqStrings(List.of("MentionTappableObject.user is null; dropping mention sticker"))));
            for (Found md : converters) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    mentionsConverter = m;
                    DexKitCache.saveMethod("MentionsConverter", m);
                    break;
                } catch (Throwable ignored) {}
            }
            if (mentionsConverter == null) ModuleLog.error("(IE|Mention) Could not turn on story mentions: the mention reader was not found in this Instagram version");
        } catch (Throwable t) {
            ModuleLog.error("(IE|Mention) Could not turn on story mentions: the mention reader could not be looked up: " + t);
        }

        if (rawMentionsGetter != null && mentionsConverter != null) {
            ModuleLog.detail("(IE|Mention) pipeline resolved: " + rawMentionsGetter.getName() + " -> " + mentionsConverter.getName());
        }
    }

    private static Object findFieldByType(Object obj, String typeName) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(cls)) {
                if (f.getType().getName().equals(typeName)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static List<String> resolveMentions(Object media) {
        List<String> usernames = new ArrayList<>();
        try {
            if (!rawMentionsGetter.getDeclaringClass().isInstance(media)) return usernames;

            Object rawResult = rawMentionsGetter.invoke(media);
            if (!(rawResult instanceof List<?> raw) || raw.isEmpty()) return usernames;

            Object convertedResult = mentionsConverter.invoke(null, raw);
            if (!(convertedResult instanceof List<?> list)) return usernames;

            for (Object item : list) {
                if (item == null) continue;
                // The converter returns Interactive stickers, not raw Users — the mentioned
                // User lives in one of Interactive's own fields (unless a future version
                // returns Users directly, which this also handles).
                Object user = item.getClass().getName().equals("com.instagram.user.model.User")
                        ? item : findFieldByType(item, "com.instagram.user.model.User");
                if (user == null) continue;
                String username = UserUtils.callUsernameGetter(user);
                if (username != null && !username.isEmpty()) usernames.add(username);
            }
        } catch (Throwable t) {
            ModuleLog.error("(IE|Mention) Could not read the story's mentions: " + t);
        }
        return usernames;
    }

    // ── Mentions dialog ───────────────────────────────────────────────────────

    private static void showMentionsDialog(Context ctx, List<String> usernames) {
        mainHandler.post(() -> {
            try {
                mentionsDialog(ctx, usernames).show();
            } catch (Throwable t) {
                ModuleLog.error("(IE|Mention) Could not show the mentions", t);
            }
        });
    }

    static IeDialog mentionsDialog(Context ctx, List<String> usernames) {
        IeDialog dlg = new IeDialog(ctx)
                .title(I18n.t(ctx, R.string.ig_mention_dialog_title))
                .message(usernames.isEmpty()
                        ? I18n.t(ctx, R.string.ig_mention_no_mentions)
                        : count(ctx, usernames.size()));
        for (String username : usernames) {
            TextView row = new TextView(ctx);
            row.setText("@" + username);
            row.setTextColor(dlg.text);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.setTypeface(null, Typeface.BOLD);
            row.setPadding(dlg.dp(16), dlg.dp(13), dlg.dp(14), dlg.dp(13));
            row.setBackground(dlg.cardBg());
            row.setOnClickListener(v -> ProfileAnalyzer.openProfile(ctx, username));
            row.setOnLongClickListener(v -> {
                CopyFeedback.copy(ctx, "username", username, I18n.t(ctx, R.string.ig_toast_mention_copied, username));
                return true;
            });
            dlg.view(row);
        }
        if (usernames.size() > 1) {
            dlg.primary(I18n.t(ctx, R.string.ig_mention_copy_all), () -> {
                StringBuilder sb = new StringBuilder();
                for (String u : usernames) sb.append("@").append(u).append("\n");
                CopyFeedback.copy(ctx, "mentions", sb.toString().trim(), R.string.ig_toast_all_mentions_copied);
            });
        }
        return dlg;
    }

    private static String count(Context ctx, int n) {
        try {
            return I18n.moduleResources(ctx).getQuantityString(R.plurals.ig_mention_count, n, n);
        } catch (Throwable t) {
            return String.valueOf(n);
        }
    }

}
