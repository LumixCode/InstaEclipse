package ps.reso.instaeclipse.fragments;

import android.content.Context;
import android.text.Layout;
import android.text.method.TransformationMethod;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class FitButtonRow extends LinearLayout {

    private static final int GAP_DP = 12;

    public FitButtonRow(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int gap = Math.round(GAP_DP * getResources().getDisplayMetrics().density);
        int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        boolean stack = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && !fits(width, gap);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        boolean first = true;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int start = stack || first ? 0 : gap;
            lp.width = stack ? LayoutParams.MATCH_PARENT : 0;
            lp.weight = stack ? 0 : 1;
            lp.setMarginStart(start);
            lp.setMarginEnd(0);
            lp.leftMargin = rtl ? 0 : start;
            lp.rightMargin = rtl ? start : 0;
            lp.topMargin = stack && !first ? gap / 2 : 0;
            first = false;
        }
        setOrientation(stack ? VERTICAL : HORIZONTAL);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private boolean fits(int width, int gap) {
        int count = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) count++;
        if (count < 2) return true;
        float share = (width - gap * (count - 1)) / (float) count;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE && child instanceof TextView && needed((TextView) child) > share) return false;
        }
        return true;
    }

    private static float needed(TextView button) {
        CharSequence text = button.getText();
        TransformationMethod method = button.getTransformationMethod();
        if (method != null) text = method.getTransformation(text, button);
        return Layout.getDesiredWidth(text, button.getPaint())
                + button.getCompoundPaddingLeft() + button.getCompoundPaddingRight();
    }
}
