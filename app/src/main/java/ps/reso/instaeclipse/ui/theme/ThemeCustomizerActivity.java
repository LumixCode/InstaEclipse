package ps.reso.instaeclipse.ui.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.LinkedHashMap;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.theme.IgThemePalette;
import ps.reso.instaeclipse.mods.ui.theme.ThemePreset;
import ps.reso.instaeclipse.mods.ui.theme.ThemePresets;
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper;
import ps.reso.instaeclipse.utils.core.CompanionSettings;

public class ThemeCustomizerActivity extends AppCompatActivity implements AdvancedColorPickerDialog.Listener {

    private static final String KEY_ENABLED = "customThemeEnabled";
    private static final String KEY_PRESET_ID = "themePresetId";
    private static final String KEY_PALETTE_JSON = "themePaletteJson";
    private static final String KEY_LIGHT_PRESET_ID = "themeLightPresetId";

    private static final int[] SLOT_LABELS = {
            R.string.theme_slot_background, R.string.theme_slot_surface, R.string.theme_slot_primary_text,
            R.string.theme_slot_secondary_text, R.string.theme_slot_accent, R.string.theme_slot_button,
            R.string.theme_slot_icon, R.string.theme_slot_glyph, R.string.theme_slot_divider,
            R.string.theme_slot_border, R.string.theme_slot_status_bar, R.string.theme_slot_navigation,
            R.string.theme_slot_link, R.string.theme_slot_error, R.string.theme_slot_destructive
    };
    private static final String STATE_PRESETS_EXPANDED = "presets_expanded";
    private static final String STATE_CUSTOM_EXPANDED = "custom_expanded";

    private MaterialSwitch enableSwitch;
    private final CompoundButton.OnCheckedChangeListener enableListener = (buttonView, isChecked) -> persist();
    private View presetsContent;
    private View customContent;
    private ImageView presetsExpandIcon;
    private ImageView customExpandIcon;
    private PresetAdapter presetAdapter;
    private ColorSlotAdapter slotAdapter;
    private IgThemePalette workingPalette;
    private boolean customMode;
    private boolean presetsExpanded = true;
    private boolean customExpanded = true;
    private int selectedPresetId = 1;
    // The gallery follows the phone's mode: in dark mode it lists and saves the dark theme
    // (themePresetId), in light mode the light theme (themeLightPresetId). The activity is
    // recreated when the mode changes, so this is re-read every time.
    private boolean lightMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_customizer);

        MaterialToolbar toolbar = findViewById(R.id.theme_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        lightMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        toolbar.setSubtitle(lightMode ? R.string.theme_set_light : R.string.theme_set_dark);

        enableSwitch = findViewById(R.id.theme_enable_switch);
        presetsContent = findViewById(R.id.theme_presets_content);
        customContent = findViewById(R.id.theme_custom_content);
        presetsExpandIcon = findViewById(R.id.theme_presets_expand_icon);
        customExpandIcon = findViewById(R.id.theme_custom_expand_icon);
        RecyclerView presetList = findViewById(R.id.theme_preset_list);
        RecyclerView colorSlots = findViewById(R.id.theme_color_slots);
        MaterialButton resetButton = findViewById(R.id.theme_reset_custom);

        if (savedInstanceState != null) {
            presetsExpanded = savedInstanceState.getBoolean(STATE_PRESETS_EXPANDED, true);
            customExpanded = savedInstanceState.getBoolean(STATE_CUSTOM_EXPANDED, true);
        }
        setupCollapsibleSection(findViewById(R.id.theme_presets_header), presetsContent, presetsExpandIcon, presetsExpanded);
        setupCollapsibleSection(findViewById(R.id.theme_custom_header), customContent, customExpandIcon, customExpanded);

        reloadPaletteState();
        boolean enabled = cache().getBoolean(KEY_ENABLED, false);
        enableSwitch.setChecked(enabled);
        enableSwitch.setOnCheckedChangeListener(enableListener);
        findViewById(R.id.theme_enable_card).setOnClickListener(v -> enableSwitch.toggle());

        List<ThemePreset> modePresets = new java.util.ArrayList<>();
        for (ThemePreset p : ThemePresets.all())
            if (ThemePresets.isLightPreset(p.id) == lightMode) modePresets.add(p);
        presetAdapter = new PresetAdapter(modePresets);
        presetList.setLayoutManager(new GridLayoutManager(this, 2));
        presetList.setAdapter(presetAdapter);

        slotAdapter = new ColorSlotAdapter();
        colorSlots.setLayoutManager(new LinearLayoutManager(this));
        colorSlots.setAdapter(slotAdapter);

        if (lightMode) {
            // Custom colors edit the dark theme's palette; the light theme is chosen from the set.
            findViewById(R.id.theme_custom_header).setVisibility(View.GONE);
            customContent.setVisibility(View.GONE);
            findViewById(R.id.theme_light_note).setVisibility(View.VISIBLE);
        }

        resetButton.setOnClickListener(v -> resetCustom());
    }

    private void setEnabledSilently(boolean enabled) {
        enableSwitch.setOnCheckedChangeListener(null);
        enableSwitch.setChecked(enabled);
        enableSwitch.setOnCheckedChangeListener(enableListener);
    }

    private void resetCustom() {
        IgThemePalette previousPalette = workingPalette.copy();
        boolean previousCustom = customMode;
        int previousPreset = selectedPresetId;
        workingPalette = ThemePresets.getById(1).palette.copy();
        customMode = true;
        selectedPresetId = 0;
        presetAdapter.notifyDataSetChanged();
        slotAdapter.notifyDataSetChanged();
        persist();
        Snackbar.make(findViewById(R.id.theme_root), R.string.theme_custom_reset, Snackbar.LENGTH_LONG)
                .setAction(R.string.theme_undo, v -> {
                    workingPalette = previousPalette;
                    customMode = previousCustom;
                    selectedPresetId = previousPreset;
                    presetAdapter.notifyDataSetChanged();
                    slotAdapter.notifyDataSetChanged();
                    persist();
                })
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadPaletteState();
        if (presetAdapter != null) presetAdapter.notifyDataSetChanged();
        if (slotAdapter != null) slotAdapter.notifyDataSetChanged();
    }

    private SharedPreferences cache() {
        return ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(this);
    }

    private void reloadPaletteState() {
        if (lightMode) {
            selectedPresetId = cache().getInt(KEY_LIGHT_PRESET_ID, -1);
            customMode = false;
            int shown = selectedPresetId > 0 ? selectedPresetId : firstLightPresetId();
            workingPalette = ThemePresets.getById(shown).palette.copy();
            return;
        }
        selectedPresetId = cache().getInt(KEY_PRESET_ID, 1);
        String paletteJson = cache().getString(KEY_PALETTE_JSON, "");
        customMode = ThemeSettingsHelper.isCustomMode(selectedPresetId);
        workingPalette = ThemeSettingsHelper.resolveEffectivePalette(selectedPresetId, paletteJson);
    }

    private static int firstLightPresetId() {
        for (ThemePreset p : ThemePresets.all()) if (ThemePresets.isLightPreset(p.id)) return p.id;
        return 1;
    }

    private IgThemePalette activePalette() {
        if (!customMode && selectedPresetId > 0) return ThemePresets.getById(selectedPresetId).palette;
        return workingPalette;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PRESETS_EXPANDED, presetsExpanded);
        outState.putBoolean(STATE_CUSTOM_EXPANDED, customExpanded);
    }

    private void setupCollapsibleSection(View header, View content, ImageView icon, boolean expanded) {
        setSectionExpanded(content, icon, expanded);
        header.setOnClickListener(v -> {
            if (content == presetsContent) {
                presetsExpanded = !presetsExpanded;
                setSectionExpanded(content, icon, presetsExpanded);
            } else {
                customExpanded = !customExpanded;
                setSectionExpanded(content, icon, customExpanded);
            }
        });
    }

    private void setSectionExpanded(View content, ImageView icon, boolean expanded) {
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        icon.setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        icon.setContentDescription(getString(expanded ? R.string.theme_collapse_section : R.string.theme_expand_section));
        ViewCompat.setStateDescription((View) icon.getParent(),
                getString(expanded ? R.string.help_faq_expanded : R.string.help_faq_collapsed));
    }

    private static String formatColorHex(int color) {
        if (Color.alpha(color) == 255) return String.format("#%06X", 0xFFFFFF & color);
        return String.format("#%08X", color);
    }

    private void persist() {
        boolean enabled = enableSwitch.isChecked();
        if (lightMode) {
            persistLight(enabled);
            return;
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put(KEY_ENABLED, enabled);
        values.put(KEY_PRESET_ID, customMode ? 0 : selectedPresetId);
        values.put(KEY_PALETTE_JSON, workingPalette.copy().toJson());
        CompanionSettings.putAll(this, values);
    }

    /** Light mode: only the enable switch and the light theme id; the dark theme is left as is. */
    private void persistLight(boolean enabled) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put(KEY_ENABLED, enabled);
        if (selectedPresetId > 0) values.put(KEY_LIGHT_PRESET_ID, selectedPresetId);
        CompanionSettings.putAll(this, values);
    }

    @Override
    public void onColorPicked(String slot, int color) {
        if (slot == null) return;
        workingPalette.set(slot, color);
        customMode = true;
        selectedPresetId = 0;
        setEnabledSilently(true);
        presetAdapter.notifyDataSetChanged();
        slotAdapter.notifyDataSetChanged();
        persist();
    }

    private void selectPreset(ThemePreset preset) {
        selectedPresetId = preset.id;
        customMode = false;
        workingPalette = preset.palette.copy();
        setEnabledSilently(true);
        presetAdapter.notifyDataSetChanged();
        slotAdapter.notifyDataSetChanged();
        persist();
    }

    private void openPicker(String slotKey, int color, String label) {
        AdvancedColorPickerDialog.newInstance(label, color, slotKey).show(getSupportFragmentManager(), "colorPicker");
    }

    private String slotLabel(int position) {
        if (position < 0 || position >= SLOT_LABELS.length) return "";
        return getString(SLOT_LABELS[position]);
    }

    private void bindPreview(LinearLayout container, int[] colors) {
        IgThemePalette.bindCardPreview(this, container, colors);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.Holder> {
        private final List<ThemePreset> presets;

        PresetAdapter(List<ThemePreset> presets) {
            this.presets = presets;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_theme_preset, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ThemePreset preset = presets.get(position);
            holder.name.setText(ThemePresets.getBaseName(preset.id));
            bindPreview(holder.preview, preset.palette.previewColors());
            boolean selected = !customMode && preset.id == selectedPresetId;
            int stroke = selected
                    ? MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorPrimary)
                    : MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOutline);
            holder.card.setStrokeColor(stroke);
            holder.card.setStrokeWidth(selected ? dp(2) : dp(1));
            holder.card.setChecked(selected);
            ViewCompat.setStateDescription(holder.card, selected ? getString(R.string.theme_preset_selected) : null);
            holder.card.setOnClickListener(v -> selectPreset(preset));
        }

        @Override
        public int getItemCount() {
            return presets.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final LinearLayout preview;
            final TextView name;

            Holder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.theme_preset_card);
                preview = itemView.findViewById(R.id.theme_preset_preview);
                name = itemView.findViewById(R.id.theme_preset_name);
            }
        }
    }

    private class ColorSlotAdapter extends RecyclerView.Adapter<ColorSlotAdapter.Holder> {

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_theme_color_slot, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            String key = IgThemePalette.SLOT_KEYS[position];
            int color = activePalette().get(key);
            holder.label.setText(slotLabel(position));
            holder.hex.setText(formatColorHex(color));
            GradientDrawable swatch = new GradientDrawable();
            swatch.setCornerRadius(dp(10));
            swatch.setColor(color);
            holder.swatch.setBackground(swatch);
            holder.itemView.setOnClickListener(v -> {
                if (!customMode && selectedPresetId > 0) {
                    workingPalette = ThemePresets.getById(selectedPresetId).palette.copy();
                }
                openPicker(key, color, slotLabel(position));
            });
        }

        @Override
        public int getItemCount() {
            return IgThemePalette.SLOT_KEYS.length;
        }

        class Holder extends RecyclerView.ViewHolder {
            final View swatch;
            final TextView label;
            final TextView hex;

            Holder(View itemView) {
                super(itemView);
                swatch = itemView.findViewById(R.id.theme_slot_swatch);
                label = itemView.findViewById(R.id.theme_slot_label);
                hex = itemView.findViewById(R.id.theme_slot_hex);
            }
        }
    }
}
