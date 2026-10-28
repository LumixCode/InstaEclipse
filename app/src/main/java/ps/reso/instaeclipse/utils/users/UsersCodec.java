package ps.reso.instaeclipse.utils.users;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

final class UsersCodec {

    private UsersCodec() {}

    static String text(JSONArray a, int i) {
        return a == null || a.isNull(i) ? null : a.optString(i);
    }

    static String text(JSONObject o, String key) {
        return o == null || o.isNull(key) ? null : o.optString(key);
    }

    static void read(JSONObject users, Map<String, String[]> into) {
        if (users == null) return;
        for (Iterator<String> it = users.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONArray u = users.optJSONArray(id);
            into.put(id, new String[]{text(u, 0), text(u, 1)});
        }
    }

    static JSONObject write(Map<String, String[]> users) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String[]> e : users.entrySet()) {
            JSONArray u = new JSONArray();
            String[] v = e.getValue();
            u.put(v != null && v.length > 0 && v[0] != null ? v[0] : JSONObject.NULL);
            u.put(v != null && v.length > 1 && v[1] != null ? v[1] : JSONObject.NULL);
            o.put(e.getKey(), u);
        }
        return o;
    }
}
