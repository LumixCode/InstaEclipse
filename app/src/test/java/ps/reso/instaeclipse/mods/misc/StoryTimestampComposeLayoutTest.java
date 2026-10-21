package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class StoryTimestampComposeLayoutTest {
    @Test public void acceptsOnlyVerifiedParameterLayout() {
        List<String> parameters = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e", "f", "g",
                "java.lang.String", "kotlin.jvm.functions.Function1", "int", "int", "int", "int",
                "int", "int", "int", "long", "long", "long", "boolean"));
        assertTrue(StoryTimestampComposeLayout.matches(parameters));
        parameters.set(11, "long");
        assertFalse(StoryTimestampComposeLayout.matches(parameters));
        assertFalse(StoryTimestampComposeLayout.matches(parameters.subList(0, 19)));
    }

    @Test public void wrapsOnlyThisCallAndPreservesTextStyleCallbacksAndOtherDefaults() {
        Object[] arguments = new Object[20];
        for (int i = 0; i < arguments.length; i++) arguments[i] = new Object();
        arguments[15] = 0xabf8;
        Object[] original = arguments.clone();
        StoryTimestampComposeLayout.wrap(arguments);
        assertEquals(0xbff8, arguments[15]);
        assertEquals(0xabf8, original[15]);
        for (int i = 0; i < arguments.length; i++) {
            if (i != 15) assertSame(original[i], arguments[i]);
        }
    }
}
