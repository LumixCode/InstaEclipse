package ps.reso.instaeclipse.mods.location;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionSettings;

public final class LocationPresets {

    public static final int MAX_RECENT = 6;
    static final double SAME_PLACE_METERS = 75;

    public static final class Preset {
        public final double lat;
        public final double lng;
        public final String label;

        public Preset(double lat, double lng, String label) {
            this.lat = lat;
            this.lng = lng;
            this.label = label == null ? "" : label;
        }

        public String title() {
            return label.isEmpty() ? coords(lat, lng) : label;
        }
    }

    private LocationPresets() {}

    public static String coords(double lat, double lng) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    public static List<Preset> parse(String json) {
        List<Preset> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && out.size() < MAX_RECENT; i++) {
                JSONObject o = arr.getJSONObject(i);
                double lat = o.getDouble("lat"), lng = o.getDouble("lng");
                if (valid(lat, lng)) out.add(new Preset(lat, lng, o.optString("label", "")));
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static String serialize(List<Preset> presets) {
        JSONArray arr = new JSONArray();
        try {
            for (Preset p : presets) {
                arr.put(new JSONObject().put("lat", p.lat).put("lng", p.lng).put("label", p.label));
            }
        } catch (Throwable ignored) {}
        return arr.toString();
    }

    public static List<Preset> remember(List<Preset> recent, Preset picked) {
        List<Preset> out = new ArrayList<>();
        out.add(picked);
        for (Preset p : recent) {
            if (out.size() >= MAX_RECENT) break;
            if (distanceMeters(p.lat, p.lng, picked.lat, picked.lng) > SAME_PLACE_METERS) out.add(p);
        }
        return out;
    }

    public static boolean valid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(lat == 0.0 && lng == 0.0);
    }

    public static double[] parseCoordinates(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")
                .matcher(text);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1)), lng = Double.parseDouble(m.group(2));
        return valid(lat, lng) ? new double[]{lat, lng} : null;
    }

    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static void applyFromCompanion(Context context, Preset picked) {
        CompanionSettings.putAll(context, placeValues(context, picked));
    }

    public static LinkedHashMap<String, Object> placeValues(Context context, Preset picked) {
        android.content.SharedPreferences cache = CommonUtils.companionCache(context);
        String recent = serialize(remember(parse(cache.getString("spoofRecent", "")), picked));
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put("spoofLat", String.valueOf(picked.lat));
        values.put("spoofLng", String.valueOf(picked.lng));
        values.put("spoofLabel", picked.label);
        values.put("spoofRecent", recent);
        return values;
    }

    public static boolean offline(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    static final double JITTER_METERS = 4.0;

    static double[] jitter(double lat, double lng, double u1, double u2) {
        double r = JITTER_METERS * Math.sqrt(u1);
        double theta = 2 * Math.PI * u2;
        double dLat = (r * Math.cos(theta)) / 111_320.0;
        double dLng = (r * Math.sin(theta)) / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        return new double[]{lat + dLat, lng + dLng};
    }
}
