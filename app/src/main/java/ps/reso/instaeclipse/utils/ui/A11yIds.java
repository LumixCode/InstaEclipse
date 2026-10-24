package ps.reso.instaeclipse.utils.ui;

import android.annotation.SuppressLint;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;

public final class A11yIds {

    private static volatile Method childId;
    private static volatile Method parentId;

    private A11yIds() {}

    @SuppressLint("SoonBlockedPrivateApi")
    public static int childId(AccessibilityNodeInfo node, int index) throws ReflectiveOperationException {
        Method m = childId;
        if (m == null) {
            m = AccessibilityNodeInfo.class.getDeclaredMethod("getChildId", int.class);
            m.setAccessible(true);
            childId = m;
        }
        return virtualId((long) m.invoke(node, index));
    }

    @SuppressLint("SoonBlockedPrivateApi")
    public static int parentId(AccessibilityNodeInfo node) throws ReflectiveOperationException {
        Method m = parentId;
        if (m == null) {
            m = AccessibilityNodeInfo.class.getDeclaredMethod("getParentNodeId");
            m.setAccessible(true);
            parentId = m;
        }
        return virtualId((long) m.invoke(node));
    }

    static int virtualId(long nodeId) {
        return (int) (nodeId >> 32);
    }
}
