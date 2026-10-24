package ps.reso.instaeclipse.fragments;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;

public class FeatureRowCard extends MaterialCardView {

    @Nullable private CompoundButton toggle;

    public FeatureRowCard(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    void setToggle(@Nullable CompoundButton toggle) {
        this.toggle = toggle;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName((toggle != null ? Switch.class : Button.class).getName());
        info.setCheckable(toggle != null);
        info.setChecked(toggle != null && toggle.isChecked());
    }

    @Override
    public void onInitializeAccessibilityEvent(@NonNull AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (toggle == null) return;
        event.setClassName(Switch.class.getName());
        event.setChecked(toggle.isChecked());
    }
}
