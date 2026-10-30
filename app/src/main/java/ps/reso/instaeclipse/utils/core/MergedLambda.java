package ps.reso.instaeclipse.utils.core;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public final class MergedLambda {

    private final Field group;
    private final ThreadLocal<Boolean> building = new ThreadLocal<>();
    private volatile int wanted = -1;

    private MergedLambda(Field group) {
        this.group = group;
    }

    public static MergedLambda watch(Member factory, Class<?> lambda) {
        Field group = HookTargetGuard.lambdaGroupField(lambda);
        MergedLambda watch = new MergedLambda(group);
        if (group == null) return watch;
        group.setAccessible(true);
        XposedBridge.hookMethod(factory, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                watch.building.set(Boolean.TRUE);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                watch.building.remove();
            }
        });
        XposedBridge.hookAllConstructors(lambda, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (watch.building.get() != null) watch.wanted = group.getInt(param.thisObject);
            }
        });
        return watch;
    }

    public boolean merged() {
        return group != null;
    }

    public boolean owns(Object lambda) throws IllegalAccessException {
        return group == null || group.getInt(lambda) == wanted;
    }

    public static Field fieldHolding(Object lambda, Class<?> type) throws IllegalAccessException {
        Field match = null;
        for (Field field : lambda.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            if (!type.isInstance(field.get(lambda))) continue;
            if (match != null) return null;
            match = field;
        }
        return match;
    }
}
