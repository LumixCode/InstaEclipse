package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class NoArgMethodCacheTest {
    public static class Screen { public String getModuleName() { return "direct_inbox"; } }
    public static class ThreadScreen extends Screen {
        @Override public String getModuleName() { return "direct_thread"; }
    }

    @Test public void foundMethodIsLookedUpOnceAndStaysInvokable() throws Exception {
        Method first = HideChatsHook.noArgMethod(Screen.class, "getModuleName");
        assertNotNull(first);
        // getMethod returns a new Method every call: the same instance means it came from the cache.
        assertSame(first, HideChatsHook.noArgMethod(Screen.class, "getModuleName"));
        assertEquals("direct_inbox", first.invoke(new Screen()));
    }

    @Test public void missingMethodStaysMissing() {
        assertNull(HideChatsHook.noArgMethod(Screen.class, "isResumed"));
        assertNull(HideChatsHook.noArgMethod(Screen.class, "isResumed"));
        assertNotNull(HideChatsHook.noArgMethod(Screen.class, "getModuleName"));
    }

    @Test public void eachClassResolvesItsOwnMethod() throws Exception {
        Method base = HideChatsHook.noArgMethod(Screen.class, "getModuleName");
        Method sub = HideChatsHook.noArgMethod(ThreadScreen.class, "getModuleName");
        assertEquals(ThreadScreen.class, sub.getDeclaringClass());
        assertEquals(Screen.class, base.getDeclaringClass());
        assertEquals("direct_thread", sub.invoke(new ThreadScreen()));
    }
}
