package ps.reso.instaeclipse.mods.location;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.CompanionSettings;
import ps.reso.instaeclipse.utils.feature.FeatureCatalog;
import ps.reso.instaeclipse.utils.ui.CompanionRestartHint;
import ps.reso.instaeclipse.utils.ui.Links;

public class LocationPickerActivity extends AppCompatActivity {

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String RESULT_LAT = "result_lat";
    public static final String RESULT_LNG = "result_lng";
    public static final String EXTRA_ENABLE_ON_PICK = "enable_on_pick";

    public interface Fetch {
        String get(String url) throws Exception;
    }

    private static volatile Fetch fetchOverride;

    private static final long REVERSE_DELAY_MS = 900;
    private static final String OSM_COPYRIGHT = "https://www.openstreetmap.org/copyright";
    private static final String STATE_LAT = "lat";
    private static final String STATE_LNG = "lng";
    private static final String STATE_ZOOM = "zoom";
    private static final String STATE_LABEL = "label";
    private static final String STATE_LABEL_LOCKED = "labelLocked";
    private static final String STATE_TARGET = "target";
    private static final double WORLD_LAT = 20.0;
    private static final double WORLD_LNG = 0.0;
    private static final double WORLD_ZOOM = 3.0;

    private MapView map;
    private TextView placeName;
    private TextView coordText;
    private TextInputEditText searchInput;
    private CircularProgressIndicator searchProgress;
    private MaterialCardView resultsCard;
    private LinearLayout resultsList;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String currentLabel = "";
    private boolean labelLocked;
    private GeoPoint target;
    private final Runnable reverseTask = this::reverseGeocodeCenter;

    public static void overrideFetchForTest(Fetch fetch) {
        fetchOverride = fetch;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            SharedPreferences prefs = getApplicationContext()
                    .getSharedPreferences(getApplicationContext().getPackageName() + "_preferences", MODE_PRIVATE);
            Configuration.getInstance().load(getApplicationContext(), prefs);
            Configuration.getInstance().setUserAgentValue(getPackageName());
        } catch (Throwable ignored) {}

        setContentView(R.layout.activity_location_picker);

        map = findViewById(R.id.map);
        placeName = findViewById(R.id.place_name);
        coordText = findViewById(R.id.coord_text);
        searchInput = findViewById(R.id.search_input);
        searchProgress = findViewById(R.id.search_progress);
        resultsCard = findViewById(R.id.results_card);
        resultsList = findViewById(R.id.results_list);
        ImageButton back = findViewById(R.id.btn_back);
        ImageButton clearBtn = findViewById(R.id.search_clear);
        MaterialButton useBtn = findViewById(R.id.btn_use);

        back.setOnClickListener(v -> finish());

        TextView attribution = findViewById(R.id.osm_attribution_text);
        GradientDrawable scrim = new GradientDrawable();
        scrim.setColor(ColorUtils.setAlphaComponent(
                MaterialColors.getColor(attribution, com.google.android.material.R.attr.colorSurface), 0xCC));
        scrim.setCornerRadius(getResources().getDisplayMetrics().density * 8);
        attribution.setBackground(scrim);
        findViewById(R.id.osm_attribution).setOnClickListener(v -> Links.open(this, OSM_COPYRIGHT));

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            String q = v.getText() != null ? v.getText().toString().trim() : "";
            if (!q.isEmpty()) onSearch(q);
            return true;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean empty = s == null || s.length() == 0;
                clearBtn.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (empty) resultsCard.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        clearBtn.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.requestFocus();
        });

        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(15.0);

        double startLat = getIntent().getDoubleExtra(EXTRA_LAT, 0.0);
        double startLng = getIntent().getDoubleExtra(EXTRA_LNG, 0.0);
        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_LAT)) {
            startLat = savedInstanceState.getDouble(STATE_LAT);
            startLng = savedInstanceState.getDouble(STATE_LNG);
            map.getController().setZoom(savedInstanceState.getDouble(STATE_ZOOM, 15.0));
            currentLabel = savedInstanceState.getString(STATE_LABEL, "");
            labelLocked = savedInstanceState.getBoolean(STATE_LABEL_LOCKED) && !currentLabel.isEmpty();
            if (labelLocked) placeName.setText(currentLabel);
            if (savedInstanceState.getBoolean(STATE_TARGET)) target = new GeoPoint(startLat, startLng);
        } else if (!LocationPresets.valid(startLat, startLng)) {
            startLat = WORLD_LAT;
            startLng = WORLD_LNG;
            map.getController().setZoom(WORLD_ZOOM);
        } else {
            target = new GeoPoint(startLat, startLng);
        }
        map.getController().setCenter(new GeoPoint(startLat, startLng));
        onCenterChanged();

        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent e) { onCenterChanged(); return false; }
            @Override public boolean onZoom(ZoomEvent e) { onCenterChanged(); return false; }
        });

        map.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                labelLocked = false;
                target = null;
                resultsCard.setVisibility(View.GONE);
            }
            return false;
        });

        buildRecentChips();

        useBtn.setOnClickListener(v -> onUse());
    }

    private void onUse() {
        map.getController().stopAnimation(true);
        IGeoPoint c = target != null ? target : map.getMapCenter();
        LocationPresets.Preset picked = new LocationPresets.Preset(c.getLatitude(), c.getLongitude(), currentLabel);
        if (getIntent().getBooleanExtra(EXTRA_ENABLE_ON_PICK, false)) {
            turnOn(picked);
        } else if (CommonUtils.companionCache(this).getBoolean("spoofLocation", false)) {
            LocationPresets.applyFromCompanion(this, picked);
            finishWith(picked);
        } else {
            askToTurnOn(picked);
        }
    }

    private void askToTurnOn(LocationPresets.Preset picked) {
        String message = getString(R.string.loc_picker_spoof_off);
        FeatureCatalog.Item spoof = FeatureCatalog.item("spoofLocation");
        if (spoof != null && spoof.restartOn != FeatureCatalog.Restart.NONE) {
            message += " " + getString(R.string.loc_picker_needs_restart);
        }
        new MaterialAlertDialogBuilder(this)
                .setMessage(message)
                .setPositiveButton(R.string.loc_picker_turn_on, (d, w) -> turnOn(picked))
                .setNegativeButton(R.string.loc_picker_only_save, (d, w) -> {
                    LocationPresets.applyFromCompanion(this, picked);
                    finishWith(picked);
                })
                .show();
    }

    private void turnOn(LocationPresets.Preset picked) {
        LinkedHashMap<String, Object> values = LocationPresets.placeValues(this, picked);
        values.put("spoofLocation", true);
        Set<String> restart = CompanionSettings.putAll(this, values);
        setResultFor(picked);
        CompanionRestartHint.show(this, restart, this::finish);
    }

    private void finishWith(LocationPresets.Preset picked) {
        setResultFor(picked);
        finish();
    }

    private void setResultFor(LocationPresets.Preset picked) {
        Intent data = new Intent();
        data.putExtra(RESULT_LAT, picked.lat);
        data.putExtra(RESULT_LNG, picked.lng);
        setResult(RESULT_OK, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (map == null) return;
        IGeoPoint c = target != null ? target : map.getMapCenter();
        outState.putDouble(STATE_LAT, c.getLatitude());
        outState.putDouble(STATE_LNG, c.getLongitude());
        outState.putBoolean(STATE_TARGET, target != null);
        outState.putDouble(STATE_ZOOM, map.getZoomLevelDouble());
        outState.putString(STATE_LABEL, currentLabel);
        outState.putBoolean(STATE_LABEL_LOCKED, labelLocked);
    }

    private void onCenterChanged() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        coordText.setText(LocationPresets.coords(c.getLatitude(), c.getLongitude()));
        if (labelLocked) return;
        currentLabel = "";
        placeName.setText(R.string.loc_picker_locating);
        main.removeCallbacks(reverseTask);
        main.postDelayed(reverseTask, REVERSE_DELAY_MS);
    }

    private void moveTo(double lat, double lng, String label) {
        target = new GeoPoint(lat, lng);
        labelLocked = label != null && !label.isEmpty();
        currentLabel = labelLocked ? label : "";
        if (labelLocked) placeName.setText(label);
        map.getController().setZoom(16.0);
        map.getController().animateTo(new GeoPoint(lat, lng));
        coordText.setText(LocationPresets.coords(lat, lng));
        if (!labelLocked) onCenterChanged();
    }

    private void buildRecentChips() {
        ChipGroup group = findViewById(R.id.recent_chips);
        HorizontalScrollView scroll = findViewById(R.id.recent_scroll);
        List<LocationPresets.Preset> recent = LocationPresets.parse(
                CommonUtils.companionCache(this).getString("spoofRecent", ""));
        group.removeAllViews();
        for (LocationPresets.Preset p : recent) {
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(shorten(p.title()));
            chip.setChipIconResource(R.drawable.ic_history);
            chip.setChipIconVisible(true);
            chip.setOnClickListener(v -> moveTo(p.lat, p.lng, p.label));
            group.addView(chip);
        }
        scroll.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String shorten(String s) {
        int comma = s.indexOf(',');
        String first = comma > 0 ? s.substring(0, comma) : s;
        return first.length() > 28 ? first.substring(0, 27) + "…" : first;
    }

    private void onSearch(String query) {
        hideKeyboard();
        double[] coords = LocationPresets.parseCoordinates(query);
        if (coords != null) {
            resultsCard.setVisibility(View.GONE);
            moveTo(coords[0], coords[1], null);
            return;
        }
        searchProgress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONArray arr = new JSONArray(fetch("https://nominatim.openstreetmap.org/search?format=json&limit=6&q="
                        + URLEncoder.encode(query, "UTF-8")));
                main.post(() -> showResults(arr));
            } catch (Throwable t) {
                main.post(() -> {
                    searchProgress.setVisibility(View.GONE);
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, LocationPresets.offline(t)
                            ? R.string.loc_picker_no_connection : R.string.loc_picker_search_unavailable,
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showResults(JSONArray arr) {
        searchProgress.setVisibility(View.GONE);
        resultsList.removeAllViews();
        if (arr.length() == 0) {
            resultsCard.setVisibility(View.GONE);
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.loc_picker_search_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            double lat = o.optDouble("lat"), lng = o.optDouble("lon");
            String name = o.optString("display_name", "");
            if (!LocationPresets.valid(lat, lng)) continue;

            View row = inflater.inflate(android.R.layout.simple_list_item_2, resultsList, false);
            TextView t1 = row.findViewById(android.R.id.text1);
            TextView t2 = row.findViewById(android.R.id.text2);
            int comma = name.indexOf(',');
            t1.setText(comma > 0 ? name.substring(0, comma) : name);
            t2.setText(comma > 0 ? name.substring(comma + 1).trim() : LocationPresets.coords(lat, lng));
            t2.setMaxLines(1);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int pad = Math.round(getResources().getDisplayMetrics().density * 20);
            row.setPadding(pad, row.getPaddingTop(), pad, row.getPaddingBottom());
            row.setBackgroundResource(resolveSelectable());
            row.setOnClickListener(v -> {
                resultsCard.setVisibility(View.GONE);
                moveTo(lat, lng, name);
            });
            resultsList.addView(row);
        }
        resultsCard.setVisibility(View.VISIBLE);
    }

    private int resolveSelectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private void reverseGeocodeCenter() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double lat = c.getLatitude(), lng = c.getLongitude();
        io.execute(() -> {
            String name = "";
            try {
                JSONObject o = new JSONObject(fetch(String.format(Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?format=json&zoom=16&lat=%.6f&lon=%.6f", lat, lng)));
                name = o.optString("display_name", "");
            } catch (Throwable ignored) {}
            String result = name;
            main.post(() -> {
                GeoPoint now = (GeoPoint) map.getMapCenter();
                if (labelLocked || Math.abs(now.getLatitude() - lat) > 1e-6 || Math.abs(now.getLongitude() - lng) > 1e-6) {
                    return;
                }
                currentLabel = result;
                placeName.setText(result.isEmpty() ? getString(R.string.loc_picker_unnamed) : result);
            });
        });
    }

    private String fetch(String url) throws Exception {
        Fetch override = fetchOverride;
        if (override != null) return override.get(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", getPackageName() + "/1.0 (LocationPicker)");
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.5");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && searchInput != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (map != null) map.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        try { io.shutdownNow(); } catch (Throwable ignored) {}
    }
}
