package ps.reso.instaeclipse.mods.feed;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.mods.ui.ProfileSpoofHook;

final class PostCountStore {

    static final String[] FIELDS = {"likes", "comments", "reposts", "shares"};

    record Row(String[] shown, String author) {}

    private final ConcurrentHashMap<String, int[]> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> real = new ConcurrentHashMap<>();

    static String key(String id) {
        int cut = id.indexOf('_');
        return cut > 0 ? id.substring(0, cut) : id;
    }

    static String label(int count) {
        return count == 0 ? "" : ProfileSpoofHook.shortCount(count);
    }

    boolean isEmpty() {
        return counts.isEmpty();
    }

    int[] counts(String id) {
        return counts.get(id);
    }

    void put(String id, int[] edited, String owner) {
        counts.put(id, edited);
    }

    void remove(String id) {
        counts.remove(id);
    }

    void clear() {
        counts.clear();
    }

    void remember(String id, int which, int value, long now) {
        long[] v = real.computeIfAbsent(id, x -> new long[]{-1, -1, -1, -1, 0});
        v[which] = value;
        v[4] = now;
    }

    int[] realOf(String id) {
        long[] v = real.get(id);
        int[] out = new int[FIELDS.length];
        for (int k = 0; k < FIELDS.length; k++) out[k] = v == null ? -1 : (int) v[k];
        return out;
    }

    boolean showsPost(Row row, String id, String owner, int[] before) {
        long[] v = real.get(id);
        if (v == null) return false;
        boolean any = false;
        for (int k = 0; k < FIELDS.length; k++) {
            String shown = row.shown()[k];
            if (shown == null) continue;
            String r = v[k] < 0 ? null : label((int) v[k]);
            String b = before == null || before[k] < 0 ? null : ProfileSpoofHook.shortCount(before[k]);
            if (!(shown.equals(r) || shown.equals(b) || (v[k] == 0 && shown.equals("0")))) return false;
            any = true;
        }
        return any;
    }

    int[] byContent(Row row, String self) {
        if (self != null && row.author() != null && !row.author().equalsIgnoreCase(self)) return null;
        String best = null;
        long bestTime = -1;
        for (Map.Entry<String, long[]> e : real.entrySet()) {
            int[] edited = counts.get(e.getKey());
            if (edited == null) continue;
            long[] v = e.getValue();
            boolean ok = true;
            for (int k = 0; k < FIELDS.length && ok; k++) {
                String shown = row.shown()[k];
                if (shown == null || v[k] < 0) continue;
                String ours = edited[k] < 0 ? null : ProfileSpoofHook.shortCount(edited[k]);
                ok = shown.equals(label((int) v[k])) || shown.equals(ours) || (v[k] == 0 && shown.equals("0"));
            }
            if (ok && v[4] > bestTime) {
                best = e.getKey();
                bestTime = v[4];
            }
        }
        return best == null ? null : counts.get(best);
    }

    void read(JSONObject o) throws JSONException {
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONObject v = o.getJSONObject(k);
            int[] c = new int[FIELDS.length];
            for (int i = 0; i < FIELDS.length; i++) c[i] = v.optInt(FIELDS[i], -1);
            counts.put(k, c);
        }
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            JSONObject v = new JSONObject();
            for (int i = 0; i < FIELDS.length; i++) if (e.getValue()[i] >= 0) v.put(FIELDS[i], e.getValue()[i]);
            o.put(e.getKey(), v);
        }
        return o;
    }
}
