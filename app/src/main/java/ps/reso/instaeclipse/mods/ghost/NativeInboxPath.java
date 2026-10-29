package ps.reso.instaeclipse.mods.ghost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class NativeInboxPath {
    private static final ClassLoader BOOT = Object.class.getClassLoader();

    final Class<?> generator;
    final Method submit;
    private final Field owner;
    private final List<Field> consumers;

    private NativeInboxPath(Class<?> generator, Method submit, Field owner, List<Field> consumers) {
        this.generator = generator;
        this.submit = submit;
        this.owner = owner;
        this.consumers = consumers;
    }

    static NativeInboxPath of(Class<?> generator, Method submit) {
        Class<?> lambda = submit.getDeclaringClass();
        Field owner = null;
        for (Class<?> type = lambda; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isAssignableFrom(generator)) continue;
                if (owner != null) return null;
                owner = field;
            }
        }
        List<Field> consumers = new ArrayList<>();
        for (Field field : interfaceFields(generator)) {
            if (field.getType().isAssignableFrom(lambda)) consumers.add(field);
        }
        if (owner == null || consumers.isEmpty()) return null;
        owner.setAccessible(true);
        for (Field field : consumers) field.setAccessible(true);
        submit.setAccessible(true);
        return new NativeInboxPath(generator, submit, owner, consumers);
    }

    static List<Method> callbacks(Class<?> generator, Collection<Class<?>> constructed) {
        Set<Method> found = new LinkedHashSet<>();
        List<Field> fields = interfaceFields(generator);
        for (Class<?> type : constructed) {
            for (Field field : fields) {
                Method callback = singleCallback(field.getType());
                if (callback == null || !field.getType().isAssignableFrom(type)) continue;
                try {
                    found.add(type.getDeclaredMethod(callback.getName(), callback.getParameterTypes()));
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return new ArrayList<>(found);
    }

    Object rowGenerator(Object consumer, Object value) throws IllegalAccessException {
        if (!(value instanceof List)) return null;
        Object model = owner.get(consumer);
        return generator.isInstance(model) && consumersOf(model).contains(consumer) ? model : null;
    }

    List<Object> consumersOf(Object model) throws IllegalAccessException {
        List<Object> values = new ArrayList<>(consumers.size());
        for (Field field : consumers) values.add(field.get(model));
        return values;
    }

    static final class Getter {
        final Field holder;
        final Method method;

        private Getter(Field holder, Method method) {
            this.holder = holder;
            this.method = method;
        }

        Object generatorOf(Object fragment) throws ReflectiveOperationException {
            Object controller = holder.get(fragment);
            return controller == null ? null : method.invoke(controller);
        }
    }

    static Getter getter(Class<?> fragment, Class<?> generator) {
        Getter found = null;
        for (Class<?> type = fragment; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Class<?> holder = field.getType();
                if (Modifier.isStatic(field.getModifiers()) || holder.isPrimitive()
                        || holder.getClassLoader() == BOOT) continue;
                Method[] methods;
                try {
                    methods = holder.getDeclaredMethods();
                } catch (LinkageError ignored) {
                    continue;
                }
                for (Method method : methods) {
                    if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0
                            || method.getReturnType() != generator) continue;
                    if (found != null) return null;
                    found = new Getter(field, method);
                }
            }
        }
        if (found != null) {
            found.holder.setAccessible(true);
            found.method.setAccessible(true);
        }
        return found;
    }

    private static List<Field> interfaceFields(Class<?> generator) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = generator; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && field.getType().isInterface()) fields.add(field);
            }
        }
        return fields;
    }

    private static Method singleCallback(Class<?> type) {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers())) continue;
            if (found != null) return null;
            found = method;
        }
        return found != null && found.getParameterCount() == 1 ? found : null;
    }
}
