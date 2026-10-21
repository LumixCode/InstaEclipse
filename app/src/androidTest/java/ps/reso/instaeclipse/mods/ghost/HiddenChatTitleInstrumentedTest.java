package ps.reso.instaeclipse.mods.ghost;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class HiddenChatTitleInstrumentedTest {
    @Test public void displayNameTakesPrecedenceOverUsernameShapedStatus() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (String name : new String[]{"Jane Smith", "Chloé Müller", "友達"}) {
                LinearLayout header = header();
                addText(header, name);
                addText(header, "Active");
                assertEquals(name, HideChatsHook.threadTitle(header));
            }
        });
    }

    @Test public void hiddenAndBlankHeaderLabelsAreIgnored() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LinearLayout header = header();
            addText(header, "Old username").setVisibility(View.GONE);
            addText(header, " ");
            addText(header, "  Jane Smith  ");
            addText(header, "Active");
            assertEquals("Jane Smith", HideChatsHook.threadTitle(header));
        });
    }

    private static LinearLayout header() {
        return new LinearLayout(InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    private static TextView addText(LinearLayout header, String value) {
        TextView label = new TextView(header.getContext());
        label.setText(value);
        header.addView(label);
        return label;
    }
}
