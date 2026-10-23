package ps.reso.instaeclipse.mods.misc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.utils.core.AtomicFiles;

final class McOverridesJson {

    private McOverridesJson() {}

    static Map<String, List<String>> parse(String json) throws JSONException {
        return configs(new JSONObject(json));
    }

    static String toJson(Map<String, List<String>> configs) throws JSONException {
        return object(configs).toString();
    }

    static String marker(Map<String, List<String>> replaced) throws JSONException {
        return new JSONObject().put("v", 1).put("replaced", object(replaced)).toString();
    }

    static Map<String, List<String>> replaced(String marker) throws JSONException {
        JSONObject replaced = new JSONObject(marker).optJSONObject("replaced");
        return replaced != null ? configs(replaced) : new LinkedHashMap<>();
    }

    static boolean sync(File file, File marker, boolean on, String add) throws Exception {
        if (!on && !marker.exists()) return false;
        Map<String, List<String>> replaced = McOverrides.replacedIn(marker.exists() ? read(marker) : null, McOverridesJson::replaced);
        Map<String, List<String>> json = parse(file.exists() ? read(file) : "{}");
        McOverrides.Sync change = McOverrides.sync(on, json, parse(add), replaced);
        if (change == null) return false;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (change.replaced != null) AtomicFiles.write(marker, marker(change.replaced));
        AtomicFiles.write(file, toJson(change.json));
        if (change.replaced == null) //noinspection ResultOfMethodCallIgnored
            marker.delete();
        return true;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> configs(JSONObject o) throws JSONException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String config = it.next();
            if (!(o.get(config) instanceof JSONArray entries)) throw new JSONException(config + " is not a list");
            List<String> list = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                if (!(entries.get(i) instanceof String entry)) throw new JSONException(config + " holds a value that is not text");
                list.add(entry);
            }
            out.put(config, list);
        }
        return out;
    }

    private static JSONObject object(Map<String, List<String>> configs) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, List<String>> e : configs.entrySet()) out.put(e.getKey(), new JSONArray(e.getValue()));
        return out;
    }
}
