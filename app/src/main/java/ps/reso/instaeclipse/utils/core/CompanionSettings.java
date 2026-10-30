package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.feature.RestartPromptRule;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.ipc.CompanionBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class CompanionSettings {

    public static final String ACTION_UPDATE_PREF = "ps.reso.instaeclipse.ACTION_UPDATE_PREF";
    public static final String ACTION_UPDATE_PREF_INT = "ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT";
    public static final String ACTION_UPDATE_PREF_STRING = "ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING";

    private static volatile BiConsumer<Context, Intent> sender = CompanionSettings::deliver;

    private CompanionSettings() {}

    public static String actionFor(Object value) {
        if (value instanceof Boolean) return ACTION_UPDATE_PREF;
        if (value instanceof Integer) return ACTION_UPDATE_PREF_INT;
        if (value instanceof String) return ACTION_UPDATE_PREF_STRING;
        throw new IllegalArgumentException("Unsupported setting value: "
                + (value == null ? "null" : value.getClass().getName()));
    }

    public static Set<String> put(Context context, String key, Object value) {
        String action = actionFor(value);
        long now = System.currentTimeMillis();
        SharedPreferences cache = CommonUtils.companionCache(context);
        Map<String, Object> before = current(cache, FeatureCatalog.restartInputs(Collections.singleton(key)));
        SharedPreferences.Editor editor = cache.edit();
        write(editor, key, value);
        editor.putLong(PrefsMerge.TS + key, now).apply();
        Intent intent = new Intent(action).putExtra("key", key).putExtra("ts", now);
        if (value instanceof Boolean) intent.putExtra("value", (boolean) (Boolean) value);
        else if (value instanceof Integer) intent.putExtra("value", (int) (Integer) value);
        else intent.putExtra("value", (String) value);
        sendToInstagram(context, intent);
        return restartNeeded(cache, before, Collections.singletonMap(key, value));
    }

    public static Set<String> putAll(Context context, LinkedHashMap<String, ?> values) {
        for (Object value : values.values()) actionFor(value);
        if (values.isEmpty()) return Collections.emptySet();
        long now = System.currentTimeMillis();
        SharedPreferences cache = CommonUtils.companionCache(context);
        Map<String, Object> before = current(cache, FeatureCatalog.restartInputs(values.keySet()));
        SharedPreferences.Editor editor = cache.edit();
        Bundle bundle = new Bundle();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            write(editor, entry.getKey(), entry.getValue());
            editor.putLong(PrefsMerge.TS + entry.getKey(), now);
            putValue(bundle, entry.getKey(), entry.getValue());
        }
        editor.apply();
        sendToInstagram(context, new Intent(CommonUtils.ACTION_APPLY_SETTINGS)
                .putExtra("values", bundle).putExtra("ts", now));
        return restartNeeded(cache, before, values);
    }

    public static void sendToInstagram(Context context, Intent intent) {
        intent.setPackage(BrokerPayload.HOST);
        sender.accept(context, intent);
    }

    public static void overrideSendForTest(BiConsumer<Context, Intent> testSender) {
        sender = testSender != null ? testSender : CompanionSettings::deliver;
    }

    private static void deliver(Context context, Intent intent) {
        try {
            if (CompanionBridge.isTrustedHost(context, BrokerPayload.HOST)) context.sendBroadcast(intent);
        } catch (Throwable ignored) {}
    }

    private static Set<String> restartNeeded(SharedPreferences cache, Map<String, Object> before, Map<String, ?> after) {
        Set<String> waiting = waiting(cache);
        Set<String> out = new LinkedHashSet<>();
        for (String key : FeatureCatalog.restartKeys(before, after)) {
            FeatureCatalog.Item item = FeatureCatalog.item(key);
            if (item != null && item.kind == FeatureCatalog.Kind.SWITCH && waiting.contains(key)) {
                ModuleLog.detail("(InstaEclipse) Restart: " + key + " is back to what Instagram started with, so no restart is asked");
                continue;
            }
            out.add(key);
        }
        return out;
    }

    private static Set<String> waiting(SharedPreferences cache) {
        Object stored;
        try {
            stored = cache.getString(SettingsManager.RESTART_PENDING, "");
        } catch (ClassCastException e) {
            stored = null;
        }
        return RestartPromptRule.parse(stored);
    }

    private static Map<String, Object> current(SharedPreferences cache, Collection<String> keys) {
        Map<String, ?> all = cache.getAll();
        Map<String, Object> out = new HashMap<>();
        for (String key : keys) out.put(key, all.containsKey(key) ? all.get(key) : SettingsSchema.defaultValue(key));
        return out;
    }

    private static void write(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else editor.putString(key, (String) value);
    }

    private static void putValue(Bundle bundle, String key, Object value) {
        if (value instanceof Boolean) bundle.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) bundle.putInt(key, (Integer) value);
        else bundle.putString(key, (String) value);
    }
}
