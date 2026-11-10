package ps.reso.instaeclipse.utils.dialog;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ui.IePalette;

import static ps.reso.instaeclipse.utils.dialog.IeRows.dp;
import static ps.reso.instaeclipse.utils.dialog.IeRows.hairline;

final class SearchBox {

    final View view;
    private final Context context;
    private final EditText field;

    SearchBox(Context context, String hint, Consumer<String> onChange) {
        this.context = context;
        IePalette p = IePalette.of(context);
        field = new EditText(context);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(hint);
        field.setTextColor(p.text);
        field.setHintTextColor(p.sub);
        field.setTextSize(16);
        field.setMinHeight(dp(context, 48));
        field.setGravity(Gravity.CENTER_VERTICAL);
        Drawable glass = IeRows.icon(context, R.drawable.ic_search, p.sub);
        if (glass != null) {
            glass.setBounds(0, 0, dp(context, 20), dp(context, 20));
            field.setCompoundDrawablesRelative(glass, null, null, null);
        }
        field.setCompoundDrawablePadding(dp(context, 10));
        field.setPaddingRelative(dp(context, 14), dp(context, 6), dp(context, 48), dp(context, 6));
        GradientDrawable background = new GradientDrawable();
        background.setColor(p.field);
        background.setCornerRadius(dp(context, 24));
        background.setStroke(hairline(context), p.line);
        field.setBackground(background);

        ImageView clear = new ImageView(context);
        Drawable cross = IeRows.icon(context, R.drawable.ic_close, p.sub);
        if (cross != null) clear.setImageDrawable(cross);
        clear.setScaleType(ImageView.ScaleType.CENTER);
        clear.setContentDescription(I18n.t(context, R.string.menu_search_clear));
        clear.setBackground(IeRows.ripple(context, 24));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> field.setText(""));

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                onChange.accept(s.toString());
            }
        });
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });

        FrameLayout holder = new FrameLayout(context);
        holder.setFocusableInTouchMode(true);
        holder.setPadding(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 6));
        holder.addView(field, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(clear, new FrameLayout.LayoutParams(dp(context, 48), dp(context, 48),
                Gravity.END | Gravity.CENTER_VERTICAL));
        view = holder;
    }

    String text() {
        return field.getText().toString();
    }

    boolean clear() {
        if (field.getText().length() == 0) return false;
        field.setText("");
        hideKeyboard();
        return true;
    }

    void hideKeyboard() {
        InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }
}
