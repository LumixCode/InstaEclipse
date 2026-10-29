package ps.reso.instaeclipse.xposedcompat;

import java.lang.reflect.Member;
import java.util.Map;

public interface HookBackend {

    XC_MethodHook.Unhook hook(Member method, XC_MethodHook callback);

    Object invokeOriginal(Member method, Object thisObject, Object[] args) throws Throwable;

    void log(String message, Throwable t);

    Map<String, ?> companionPrefs();

    String name();
}
