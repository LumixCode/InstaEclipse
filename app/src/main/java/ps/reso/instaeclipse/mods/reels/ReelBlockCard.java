package ps.reso.instaeclipse.mods.reels;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.ModuleResources;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.ui.IePalette;
import ps.reso.instaeclipse.utils.ui.ResIds;

public final class ReelBlockCard {

    private static final String REELS_ICON = "instagram_reels_pano_outline_24";
    private static final String MOON_ICON = "instagram_moon_pano_outline_24";

    private final IePalette palette = IePalette.compute(true, null, IePalette.MISSING);
    private final FrameLayout view;
    private final ImageView icon;
    private final TextView title;
    private final TextView detail;
    private final TextView pill;
    private final Rect pillBounds = new Rect();
    private boolean laidOut;

    public ReelBlockCard(Context context) {
        float dp = context.getResources().getDisplayMetrics().density;
        view = new FrameLayout(context);
        view.setBackgroundColor(palette.backdrop);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        int side = Math.round(32 * dp);
        column.setPadding(side, side, side, side);

        icon = new ImageView(context);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(Math.round(2 * dp), palette.text);
        icon.setBackground(ring);
        int inset = Math.round(18 * dp);
        icon.setPadding(inset, inset, inset, inset);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(icon, new LinearLayout.LayoutParams(Math.round(72 * dp), Math.round(72 * dp)));

        title = text(context, 20, palette.text, true);
        column.addView(title, below(Math.round(20 * dp)));
        detail = text(context, 14, palette.sub, false);
        column.addView(detail, below(Math.round(8 * dp)));

        pill = text(context, 14, palette.text, true);
        pill.setText(I18n.t(context, R.string.ig_reel_block_go_back));
        pill.setMinHeight(Math.round(48 * dp));
        pill.setMinWidth(Math.round(120 * dp));
        int pad = Math.round(24 * dp);
        pill.setPadding(pad, 0, pad, 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, rounded(palette.pressed, 24 * dp));
        background.addState(new int[0], rounded(palette.card, 24 * dp));
        pill.setBackground(background);
        column.addView(pill, below(Math.round(24 * dp)));

        view.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    public View view() {
        return view;
    }

    public void show(ReelBlockPolicy.Reason reason, int nightEndMinutes, int watched, int limit) {
        Context c = view.getContext();
        if (reason == ReelBlockPolicy.Reason.DISABLE_REELS) {
            icon.setImageDrawable(drawable(c, REELS_ICON, R.drawable.ic_movie));
            title.setText(I18n.t(c, R.string.ig_reel_block_off_title));
            detail.setText(I18n.t(c, R.string.ig_reel_block_off_detail, I18n.t(c, R.string.cat_title_focus)));
        } else if (reason == ReelBlockPolicy.Reason.NIGHTTIME) {
            icon.setImageDrawable(drawable(c, MOON_ICON, R.drawable.ic_timer));
            title.setText(I18n.t(c, R.string.ig_reel_block_night_title));
            detail.setText(I18n.t(c, R.string.ig_reel_block_night_detail, time(c, nightEndMinutes)));
        } else {
            icon.setImageDrawable(drawable(c, REELS_ICON, R.drawable.ic_movie));
            title.setText(I18n.t(c, R.string.ig_reel_block_limit_title));
            detail.setText(I18n.t(c, R.string.ig_reel_block_limit_detail, watched, limit));
        }
        laidOut = false;
    }

    public void fit(int width, int height) {
        if (laidOut && view.getWidth() == width && view.getHeight() == height) return;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        laidOut = true;
    }

    public void touch(MotionEvent event, Runnable back) {
        boolean inside = onPill(event.getX(), event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> pill.setPressed(inside);
            case MotionEvent.ACTION_MOVE -> {
                if (!inside) pill.setPressed(false);
            }
            case MotionEvent.ACTION_UP -> {
                boolean go = pill.isPressed() && inside;
                pill.setPressed(false);
                if (go) back.run();
            }
            case MotionEvent.ACTION_CANCEL -> pill.setPressed(false);
            default -> { }
        }
    }

    private static String time(Context context, int minutes) {
        Calendar at = Calendar.getInstance();
        at.set(Calendar.HOUR_OF_DAY, minutes / 60);
        at.set(Calendar.MINUTE, minutes % 60);
        return android.text.format.DateFormat.getTimeFormat(context).format(at.getTime());
    }

    private boolean onPill(float x, float y) {
        if (!laidOut) return false;
        pill.getDrawingRect(pillBounds);
        view.offsetDescendantRectToMyCoords(pill, pillBounds);
        return pillBounds.contains(Math.round(x), Math.round(y));
    }

    private Drawable drawable(Context context, String igName, int fallback) {
        Drawable d = null;
        int id = ResIds.drawable(context, igName);
        if (id != 0) {
            try {
                d = context.getDrawable(id);
            } catch (Throwable ignored) {
            }
        }
        if (d == null) d = ModuleResources.drawable(context, fallback);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(palette.text);
        return d;
    }

    private static TextView text(Context context, float sp, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static LinearLayout.LayoutParams below(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }
}
