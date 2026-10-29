package ps.reso.instaeclipse.ui.theme;

import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

import ps.reso.instaeclipse.R;

public class AdvancedColorPickerDialog extends DialogFragment {

    private static final String ARG_TITLE = "title";
    private static final String ARG_COLOR = "color";
    private static final String ARG_SLOT = "slot";
    private static final String STATE_COLOR = "workingColor";

    private static final InputFilter HEX_DIGITS = (source, start, end, dest, dstart, dend) -> {
        StringBuilder kept = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = source.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) kept.append(Character.toUpperCase(c));
        }
        return kept.length() == end - start && kept.toString().contentEquals(source.subSequence(start, end)) ? null : kept;
    };

    public interface Listener {
        void onColorPicked(String slot, int color);
    }

    private AdvancedColorPickerView pickerView;
    private View previewSwatch;
    private TextInputLayout hexLayout;
    private EditText hexInput;
    private SeekBar alphaBar;
    private SeekBar redBar;
    private SeekBar greenBar;
    private SeekBar blueBar;
    private TextView rgbLabel;
    private TextView alphaLabel;

    public static AdvancedColorPickerDialog newInstance(String title, int color, String slotKey) {
        AdvancedColorPickerDialog dialog = new AdvancedColorPickerDialog();
        Bundle args = new Bundle();
        args.putString(ARG_TITLE, title);
        args.putInt(ARG_COLOR, color);
        args.putString(ARG_SLOT, slotKey);
        dialog.setArguments(args);
        return dialog;
    }

    static String formatHex(int color) {
        if ((color >>> 24) == 0xFF) return String.format(Locale.ROOT, "%06X", color & 0xFFFFFF);
        return String.format(Locale.ROOT, "%08X", color);
    }

    static Integer parseHex(String raw, int current) {
        String hex = raw == null ? "" : raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return null;
        for (int i = 0; i < hex.length(); i++) if (Character.digit(hex.charAt(i), 16) < 0) return null;
        long value = Long.parseLong(hex, 16);
        if (hex.length() == 8) return (int) value;
        return (current & 0xFF000000) | (int) value;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_advanced_color_picker, (ViewGroup) null);
        int initialColor = requireArguments().getInt(ARG_COLOR, Color.WHITE);
        int color = savedInstanceState != null ? savedInstanceState.getInt(STATE_COLOR, initialColor) : initialColor;
        String title = requireArguments().getString(ARG_TITLE, getString(R.string.theme_pick_color));

        pickerView = view.findViewById(R.id.color_picker_view);
        previewSwatch = view.findViewById(R.id.color_preview_swatch);
        hexLayout = view.findViewById(R.id.color_hex_layout);
        hexInput = view.findViewById(R.id.color_hex_input);
        alphaBar = view.findViewById(R.id.color_alpha_bar);
        redBar = view.findViewById(R.id.color_red_bar);
        greenBar = view.findViewById(R.id.color_green_bar);
        blueBar = view.findViewById(R.id.color_blue_bar);
        rgbLabel = view.findViewById(R.id.color_rgb_label);
        alphaLabel = view.findViewById(R.id.color_alpha_label);

        hexInput.setFilters(new InputFilter[]{HEX_DIGITS, new InputFilter.LengthFilter(8)});
        alphaBar.setMax(255);
        redBar.setMax(255);
        greenBar.setMax(255);
        blueBar.setMax(255);
        pickerView.setInitialColor(initialColor);
        pickerView.setColor(color);
        syncUiFromColor(color, true);
        pickerView.setOnColorChangedListener(c -> syncUiFromColor(c, true));
        bindHexInput();
        bindAlphaBar();
        bindRgbBar(redBar, 0);
        bindRgbBar(greenBar, 1);
        bindRgbBar(blueBar, 2);

        return new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(view)
                .setPositiveButton(R.string.theme_apply_color, (d, w) -> {
                    if (hexInput.hasFocus()) applyHex();
                    Listener l = getListener();
                    if (l != null) l.onColorPicked(requireArguments().getString(ARG_SLOT), pickerView.getColor());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pickerView != null) outState.putInt(STATE_COLOR, pickerView.getColor());
    }

    private void bindHexInput() {
        hexInput.setOnEditorActionListener((v, actionId, event) ->
                actionId == EditorInfo.IME_ACTION_DONE && !applyHex());
        hexInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && isAdded()) applyHex();
        });
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                hexLayout.setError(null);
            }
        });
    }

    private boolean applyHex() {
        Integer color = parseHex(String.valueOf(hexInput.getText()), pickerView.getColor());
        if (color == null) {
            hexLayout.setError(getString(R.string.theme_hex_invalid));
            return false;
        }
        pickerView.setColor(color);
        syncUiFromColor(color, false);
        return true;
    }

    private void bindAlphaBar() {
        alphaBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) pickerView.setAlphaChannel(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void bindRgbBar(SeekBar bar, int channel) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int color = pickerView.getColor();
                int a = Color.alpha(color);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                if (channel == 0) r = progress;
                else if (channel == 1) g = progress;
                else b = progress;
                int next = Color.argb(a, r, g, b);
                pickerView.setColor(next);
                syncUiFromColor(next, true);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void syncUiFromColor(int color, boolean writeHex) {
        previewSwatch.setBackgroundColor(color);
        rgbLabel.setText(getString(R.string.theme_rgba_format, Color.red(color), Color.green(color), Color.blue(color), Color.alpha(color)));
        alphaLabel.setText(getString(R.string.theme_alpha_format, Math.round((Color.alpha(color) / 255.0f) * 100.0f)));
        alphaBar.setProgress(Color.alpha(color));
        redBar.setProgress(Color.red(color));
        greenBar.setProgress(Color.green(color));
        blueBar.setProgress(Color.blue(color));
        if (writeHex) {
            hexInput.setText(formatHex(color));
            hexInput.setSelection(hexInput.getText().length());
        }
    }

    private Listener getListener() {
        if (getParentFragment() instanceof Listener) return (Listener) getParentFragment();
        if (requireActivity() instanceof Listener) return (Listener) requireActivity();
        return null;
    }
}
