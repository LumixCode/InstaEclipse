package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.VoiceMessageDownloadHook;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.UnsentIdentity;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.TimestampFormat;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;

public class VoiceMessagesHook {

    private static final String TAG = "(IE|VoiceList) ";
    private static final String STATUS = "VoiceMessagesList";
    private static final String CACHE_KEY = "VoiceMessagesList_detailsPage";
    private static final String PAGE_ANCHOR = "ThreadDetailHomePageFragment";
    private static final String SECTIONS_ANCHOR = "shortcutsContainer";
    private static final String THREAD_ARG = "DirectFragment.DIRECT_FRAGMENT_ARGUMENT_THREAD_ID";
    private static final String THREAD_ID_PARCELABLE = "com.instagram.model.direct.threadkey.util.ThreadIdParcelable";
    private static final String VOICE = "voice_media";
    private static final String MENU_KEY = "voice";
    private static final String ROW_TAG = "ie_voice_messages_row";
    private static final String PLAY = "▶︎";
    private static final String PAUSE = "❚❚";
    private static final String LOADING = "…";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean readerMissing;
    private static volatile boolean placeMissing;

    private record Voice(String url, String sender, String user, long time, long durationMs, String id) {}

    public void install(ClassLoader loader) {
        ThreadHeaderInjector.install(loader);
        ThreadHeaderInjector.register(MENU_KEY, false,
                (activity, threadId) -> !FeatureFlags.voiceMessagesList || menuEntry(activity));
        try {
            Method created = detailsPage(loader);
            if (created == null) {
                ModuleLog.warn(TAG + "Will not work on the chat details page: the page was not found in this Instagram version. Voice messages is still in the chat menu");
                if (FeatureFlags.voiceMessagesList) FeatureStatusTracker.setBroken(STATUS);
                return;
            }
            XposedBridge.hookMethod(created, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.voiceMessagesList) return;
                    try {
                        if (!(param.args[0] instanceof View root)) return;
                        WeakReference<Object> page = new WeakReference<>(param.thisObject);
                        root.post(() -> addRow(root, page));
                    } catch (Throwable t) {
                        ModuleLog.detail(TAG + "chat details page: " + t);
                    }
                }
            });
            FeatureStatusTracker.setHooked(STATUS);
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "chat details page: " + created.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Could not add Voice messages to the chat details page", t);
            if (FeatureFlags.voiceMessagesList) FeatureStatusTracker.setBroken(STATUS);
        }
    }

    private static Method detailsPage(ClassLoader loader) throws ClassNotFoundException {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(CACHE_KEY, loader);
            if (cached != null) return cached;
        }
        Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, loader);
        Set<Class<?>> pages = fragmentsUsing(PAGE_ANCHOR, fragment, loader);
        if (pages.size() > 1) pages.retainAll(fragmentsUsing(SECTIONS_ANCHOR, fragment, loader));
        if (pages.size() != 1) {
            ModuleLog.detail(TAG + pages.size() + " fragments use \"" + PAGE_ANCHOR + "\"");
            return null;
        }
        Class<?> page = pages.iterator().next();
        for (Method method : SafeReflect.methods(page)) {
            Class<?>[] params = method.getParameterTypes();
            if (!method.getName().equals("onViewCreated") || params.length != 2
                    || params[0] != View.class || params[1] != Bundle.class) continue;
            DexKitCache.saveMethod(CACHE_KEY, method);
            return method;
        }
        ModuleLog.detail(TAG + page.getName() + " has no onViewCreated of its own");
        return null;
    }

    private static Set<Class<?>> fragmentsUsing(String anchor, Class<?> fragment, ClassLoader loader) {
        Set<Class<?>> out = new LinkedHashSet<>();
        for (Found found : DexKitQueryCache.methods(FindMethod.create().matcher(MethodMatcher.create()
                .usingStrings(anchor)))) {
            try {
                Class<?> type = Class.forName(found.getClassName(), false, loader);
                if (fragment.isAssignableFrom(type)) out.add(type);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static boolean menuEntry(Activity activity) {
        if (!ChatExportHook.ready()) return false;
        if (ThreadHeaderMenu.has(activity, MENU_KEY)) return true;
        return ThreadHeaderMenu.add(activity, MENU_KEY, I18n.t(activity, R.string.ig_voice_messages),
                () -> openFromChat(activity));
    }

    private static boolean readerReady() {
        boolean missing = !ChatExportHook.ready();
        if (missing == readerMissing) return !missing;
        readerMissing = missing;
        if (missing) {
            ModuleLog.warn(TAG + "Will not work: the messages a chat has loaded can't be read in this Instagram version");
            FeatureStatusTracker.setBroken(STATUS);
        } else {
            ModuleLog.detail(TAG + "the message reader is ready now");
            FeatureStatusTracker.setHooked(STATUS);
        }
        return !missing;
    }

    private static void addRow(View root, WeakReference<Object> page) {
        try {
            if (!FeatureFlags.voiceMessagesList || !readerReady() || root.findViewWithTag(ROW_TAG) != null) return;
            LinearLayout parent = null;
            int index = -1;
            View shared = child(root, "shared_section");
            if (shared != null && shared.getParent() instanceof LinearLayout group && vertical(group)) {
                parent = group;
                index = group.indexOfChild(shared);
            } else if (child(root, "thread_details_header") instanceof LinearLayout header && vertical(header)) {
                parent = header;
            }
            if (parent == null) {
                if (!placeMissing) {
                    placeMissing = true;
                    ModuleLog.warn(TAG + "Could not place Voice messages on the chat details page in this Instagram version. It is still in the chat menu");
                }
                return;
            }
            Context context = root.getContext();
            View row = row(context, child(root, "thread_title") instanceof TextView title
                    ? title.getCurrentTextColor() : IePalette.of(context).text, page);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index >= 0) parent.addView(row, index, params);
            else parent.addView(row, params);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not add Voice messages to the chat details page", t);
        }
    }

    private static View child(View root, String name) {
        int id = ResIds.id(root.getContext(), name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static boolean vertical(LinearLayout layout) {
        return layout.getOrientation() == LinearLayout.VERTICAL;
    }

    private static View row(Context context, int color, WeakReference<Object> page) {
        float density = context.getResources().getDisplayMetrics().density;
        int side = Math.round(16 * density);
        int edge = Math.round(14 * density);
        LinearLayout row = new LinearLayout(context);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(side, edge, side, edge);
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                && ripple.resourceId != 0) {
            row.setBackgroundResource(ripple.resourceId);
        }

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(ModuleResources.icon(context, R.drawable.ic_mic, color));
        int iconSize = Math.round(24 * density);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.setMarginEnd(edge);
        row.addView(icon, iconParams);

        TextView label = new TextView(context);
        label.setText(I18n.t(context, R.string.ig_voice_messages));
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView chevron = new ImageView(context);
        chevron.setImageDrawable(ModuleResources.icon(context, R.drawable.ic_chevron_right, color));
        int chevronSize = Math.round(20 * density);
        row.addView(chevron, new LinearLayout.LayoutParams(chevronSize, chevronSize));

        row.setOnClickListener(v -> openFromDetails(page.get()));
        return row;
    }

    private static void openFromDetails(Object page) {
        try {
            if (!FeatureFlags.voiceMessagesList || page == null) return;
            if (!(HideChatsHook.invokeNoArg(page, "getActivity") instanceof Activity activity)) return;
            open(activity, sessionOf(page), threadIdOf(page));
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not open the voice messages list", t);
        }
    }

    private static void openFromChat(Activity activity) {
        try {
            if (!FeatureFlags.voiceMessagesList) return;
            String threadId = UnsentThreadButtonHook.openChat(activity);
            if (threadId == null) threadId = UnsentThreadButtonHook.openThread.get();
            open(activity, sessionOf(CurrentThreadTracker.fragment(activity, fragment -> true)), threadId);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not open the voice messages list", t);
        }
    }

    private static Object sessionOf(Object owner) {
        Object session = HideChatsHook.invokeNoArg(owner, "getSession");
        return session != null && UnsentIdentity.SESSION.equals(session.getClass().getName()) ? session : null;
    }

    private static String threadIdOf(Object page) {
        try {
            if (!(XposedHelpers.getObjectField(page, "mArguments") instanceof Bundle arguments)) return null;
            arguments.setClassLoader(page.getClass().getClassLoader());
            Object parcel = arguments.get(THREAD_ARG);
            if (parcel == null || !THREAD_ID_PARCELABLE.equals(parcel.getClass().getName())) return null;
            for (Field field : SafeReflect.fields(parcel.getClass())) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                String id = CurrentThreadTracker.firstStringField(field.get(parcel));
                if (id != null) return id;
            }
        } catch (Throwable t) {
            ModuleLog.detail(TAG + "chat id of the details page: " + t);
        }
        return null;
    }

    private static void open(Activity activity, Object session, String threadId) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new Thread(() -> {
            List<Voice> voices = null;
            try {
                voices = voices(activity, session, threadId);
                ModuleLog.detail(TAG + "chat " + threadId + " session=" + (session != null) + ": "
                        + (voices == null ? "messages not found" : voices.size() + " voice messages"));
            } catch (Throwable t) {
                ModuleLog.warn(TAG + "Could not read this chat's voice messages", t);
            }
            List<Voice> found = voices;
            MAIN.post(() -> show(activity, found));
        }, "ie-voice-list").start();
    }

    private static List<Voice> voices(Context context, Object session, String threadId)
            throws ReflectiveOperationException {
        ChatExportHook.LoadedChat chat = ChatExportHook.loadedChat(session, threadId,
                message -> VOICE.equals(message.optString("item_type", "")));
        if (chat == null) return null;
        List<Voice> voices = new ArrayList<>();
        for (JSONObject message : chat.messages()) {
            String[] audio = ChatExportHook.mediaForMessage(message, VOICE);
            if (audio == null || !audio[0].startsWith("https://")) continue;
            JSONObject payload = message.optJSONObject(VOICE);
            JSONObject media = payload == null ? null : payload.optJSONObject("media");
            String senderId = message.optString("user_id", "");
            String user = chat.names().get(senderId);
            if (user == null || user.isEmpty()) user = owner(media);
            String sender = !senderId.isEmpty() && senderId.equals(chat.selfId())
                    ? I18n.t(context, R.string.ig_voice_you)
                    : user != null ? user : I18n.t(context, R.string.ig_voice_sender_unknown);
            voices.add(new Voice(audio[0], sender, user, ChatExportHook.timestampMs(message),
                    durationMs(payload), mediaId(media, message)));
        }
        Collections.reverse(voices);
        return voices;
    }

    private static String owner(JSONObject media) {
        JSONObject user = media == null ? null : media.optJSONObject("user");
        String name = user == null ? "" : user.optString("username", "");
        return name.isEmpty() ? null : name;
    }

    private static long durationMs(JSONObject payload) {
        try {
            return (long) Double.parseDouble(ChatExportHook.first(payload, "duration_ms", "duration"));
        } catch (RuntimeException unknown) {
            return 0;
        }
    }

    private static String mediaId(JSONObject media, JSONObject message) {
        String id = media == null ? "" : media.optString("id", "");
        if (id.isEmpty()) id = message.optString("item_id", "");
        return id.isEmpty() ? null : id.split("_")[0];
    }

    private static void show(Activity activity, List<Voice> voices) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (voices == null) {
                IeToast.makeText(activity, I18n.t(activity, R.string.ig_voice_messages_no_chat), Toast.LENGTH_LONG).show();
                return;
            }
            IeDialog dialog = new IeDialog(activity).title(I18n.t(activity, R.string.ig_voice_messages));
            if (voices.isEmpty()) dialog.message(I18n.t(activity, R.string.ig_voice_messages_empty));
            TextView note = new TextView(activity);
            note.setText(I18n.t(activity, R.string.ig_voice_messages_loaded));
            note.setTextColor(dialog.sub);
            note.setTextSize(13);
            dialog.view(note);
            Player player = new Player(activity);
            SimpleDateFormat format = TimestampFormat.dialogDateFormat(activity);
            for (Voice voice : voices) dialog.view(card(activity, dialog, player, voice, format));
            Lifetime lifetime = new Lifetime(activity, player, dialog);
            dialog.onDismiss(lifetime::end);
            if (dialog.show().isShowing()) activity.getApplication().registerActivityLifecycleCallbacks(lifetime);
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not show the voice messages list", t);
        }
    }

    private static View card(Activity activity, IeDialog dialog, Player player, Voice voice, SimpleDateFormat format) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dialog.dp(12), dialog.dp(10), dialog.dp(12), dialog.dp(10));
        card.setBackground(dialog.cardBg());

        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView play = new TextView(activity);
        play.setTextColor(IePalette.of(activity).onAccent);
        play.setTextSize(15);
        play.setGravity(Gravity.CENTER);
        play.setBackground(dialog.round(dialog.accent, 18));
        player.label(play, PLAY, R.string.ig_voice_play);
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dialog.dp(36), dialog.dp(36));
        playParams.setMarginEnd(dialog.dp(12));
        top.addView(play, playParams);

        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView sender = new TextView(activity);
        sender.setText(voice.sender());
        sender.setTextColor(dialog.text);
        sender.setTextSize(14);
        sender.setTypeface(Typeface.DEFAULT_BOLD);
        sender.setSingleLine(true);
        sender.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(sender);
        TextView when = new TextView(activity);
        when.setText(whenText(activity, voice, format));
        when.setTextColor(dialog.sub);
        when.setTextSize(12);
        info.addView(when);
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView save = new ImageView(activity);
        save.setImageDrawable(ModuleResources.icon(activity, R.drawable.ic_download, dialog.sub));
        save.setPadding(dialog.dp(8), dialog.dp(8), dialog.dp(8), dialog.dp(8));
        save.setContentDescription(I18n.t(activity, R.string.ig_download_choice_title));
        save.setOnClickListener(v -> save(activity, voice));
        top.addView(save, new LinearLayout.LayoutParams(dialog.dp(40), dialog.dp(40)));
        card.addView(top);

        SeekBar bar = new SeekBar(activity);
        bar.setVisibility(View.GONE);
        bar.setProgressTintList(ColorStateList.valueOf(dialog.accent));
        bar.setThumbTintList(ColorStateList.valueOf(dialog.accent));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.topMargin = dialog.dp(6);
        card.addView(bar, barParams);

        View.OnClickListener toggle = v -> player.toggle(voice.url(), play, bar);
        play.setOnClickListener(toggle);
        card.setOnClickListener(toggle);
        return card;
    }

    private static void save(Activity activity, Voice voice) {
        try {
            VoiceMessageDownloadHook.saveVoice(activity, voice.url(), voice.user(), voice.id());
        } catch (Throwable t) {
            ModuleLog.warn(TAG + "Could not save this voice message", t);
        }
    }

    private static String whenText(Context context, Voice voice, SimpleDateFormat format) {
        String when = voice.time() > 0 ? format.format(new Date(voice.time()))
                : I18n.t(context, R.string.ig_voice_time_unknown);
        return voice.durationMs() > 0 ? when + " · " + clock(voice.durationMs()) : when;
    }

    private static String clock(long ms) {
        long seconds = Math.round(ms / 1000.0);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static final class Lifetime implements Application.ActivityLifecycleCallbacks {
        private final Activity activity;
        private final Player player;
        private final IeDialog dialog;

        Lifetime(Activity activity, Player player, IeDialog dialog) {
            this.activity = activity;
            this.player = player;
            this.dialog = dialog;
        }

        void end() {
            try {
                player.release();
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "closing the list: " + t);
            }
        }

        @Override
        public void onActivityPaused(Activity paused) {
            if (paused == activity) player.release();
        }

        @Override
        public void onActivityDestroyed(Activity destroyed) {
            if (destroyed != activity) return;
            end();
            dialog.dismiss();
        }

        @Override
        public void onActivityCreated(Activity created, Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity started) {
        }

        @Override
        public void onActivityResumed(Activity resumed) {
        }

        @Override
        public void onActivityStopped(Activity stopped) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity saved, Bundle state) {
        }
    }

    private static final class Player {
        private final Activity activity;
        private final Runnable tick = this::progress;
        private MediaPlayer media;
        private String url;
        private TextView button;
        private SeekBar bar;
        private boolean prepared;

        Player(Activity activity) {
            this.activity = activity;
        }

        void label(TextView target, String glyph, int description) {
            target.setText(glyph);
            target.setContentDescription(I18n.t(activity, description));
        }

        void toggle(String address, TextView play, SeekBar seek) {
            try {
                if (media != null && address.equals(url)) {
                    resumeOrPause(play);
                    return;
                }
                release();
                url = address;
                button = play;
                bar = seek;
                label(play, LOADING, R.string.ig_voice_play);
                seek.setProgress(0);
                seek.setVisibility(View.VISIBLE);
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar changed, int position, boolean fromUser) {
                        try {
                            if (fromUser && prepared && media != null && changed == bar) media.seekTo(position);
                        } catch (Throwable ignored) {
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar touched) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar touched) {
                    }
                });
                MediaPlayer player = new MediaPlayer();
                media = player;
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                player.setDataSource(address);
                player.setOnPreparedListener(this::started);
                player.setOnCompletionListener(this::finished);
                player.setOnErrorListener(this::failed);
                player.prepareAsync();
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "playback did not start: " + t);
                fail();
            }
        }

        private void resumeOrPause(TextView play) {
            if (!prepared) return;
            if (media.isPlaying()) {
                media.pause();
                label(play, PLAY, R.string.ig_voice_play);
            } else {
                media.start();
                label(play, PAUSE, R.string.ig_voice_pause);
                MAIN.post(tick);
            }
        }

        private void started(MediaPlayer player) {
            if (media != player) return;
            try {
                prepared = true;
                bar.setMax(Math.max(1, player.getDuration()));
                player.start();
                label(button, PAUSE, R.string.ig_voice_pause);
                MAIN.post(tick);
            } catch (Throwable t) {
                ModuleLog.detail(TAG + "playback did not start: " + t);
                fail();
            }
        }

        private void finished(MediaPlayer player) {
            if (media != player) return;
            try {
                MAIN.removeCallbacks(tick);
                label(button, PLAY, R.string.ig_voice_play);
                bar.setProgress(0);
                player.seekTo(0);
            } catch (Throwable ignored) {
            }
        }

        private boolean failed(MediaPlayer player, int what, int extra) {
            ModuleLog.detail(TAG + "playback error " + what + "/" + extra);
            if (media == player) fail();
            return true;
        }

        private void fail() {
            release();
            try {
                IeToast.makeText(activity, I18n.t(activity, R.string.ig_voice_play_failed), Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }

        private void progress() {
            try {
                if (media == null || !prepared || bar == null) return;
                bar.setProgress(media.getCurrentPosition());
                if (media.isPlaying()) MAIN.postDelayed(tick, 200);
            } catch (Throwable ignored) {
            }
        }

        void release() {
            MAIN.removeCallbacks(tick);
            MediaPlayer player = media;
            media = null;
            url = null;
            prepared = false;
            try {
                if (button != null) label(button, PLAY, R.string.ig_voice_play);
                if (bar != null) {
                    bar.setOnSeekBarChangeListener(null);
                    bar.setProgress(0);
                    bar.setVisibility(View.GONE);
                }
            } catch (Throwable ignored) {
            }
            button = null;
            bar = null;
            if (player == null) return;
            try {
                player.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
