package ps.reso.instaeclipse.mods.ghost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where Instagram's Direct inbox generator receives its complete row list, found from class shape
 * only. The generator (446 X.05Ho, 451 X.07Ln) builds one shared multi-variant lambda per input
 * stream (446 X.0AJc, 451 X.09TE) into final interface fields (446 A0E-A0J, 451 A0F-A0L); the
 * lambda keeps the generator in an Object field. On 446 and 451 the generator's only consumer
 * that receives a List is its inbox row stream (446 A0J, 451 A0L).
 */
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

    /** Null unless {@code submit}'s lambda has one field for its generator and the generator holds such lambdas. */
    static NativeInboxPath of(Class<?> generator, Method submit) {
        Class<?> lambda = submit.getDeclaringClass();
        Field owner = null;
        for (Class<?> type = lambda; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isAssignableFrom(generator)) continue;
                if (owner != null) return null; // owner field ambiguous
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

    /**
     * Callback implementations of the lambda classes the generator's constructor builds into its
     * interface fields; {@code constructed} are the classes that constructor instantiates.
     */
    static List<Method> callbacks(Class<?> generator, Collection<Class<?>> constructed) {
        Set<Method> found = new LinkedHashSet<>();
        List<Field> fields = interfaceFields(generator);
        for (Class<?> type : constructed) {
            for (Field field : fields) {
                Method callback = singleCallback(field.getType());
                if (callback == null || !field.getType().isAssignableFrom(type)) continue;
                try {
                    found.add(type.getDeclaredMethod(callback.getName(), callback.getParameterTypes()));
                } catch (NoSuchMethodException ignored) {} // inherited implementation: not a stream lambda
            }
        }
        return new ArrayList<>(found);
    }

    /** The generator when {@code value} is a List delivered through one of that generator's own consumers. */
    Object rowGenerator(Object consumer, Object value) throws IllegalAccessException {
        if (!(value instanceof List)) return null;
        Object model = owner.get(consumer);
        return generator.isInstance(model) && consumersOf(model).contains(consumer) ? model : null;
    }

    /** The generator's consumer lambdas, in field order. */
    List<Object> consumersOf(Object model) throws IllegalAccessException {
        List<Object> values = new ArrayList<>(consumers.size());
        for (Field field : consumers) values.add(field.get(model));
        return values;
    }

    /** A field of the inbox Fragment and its type's no-arg getter returning the generator. */
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

    /** 446 X.04Za.A0H -> X.04g6.A09(), 451 X.04Gm.A0H -> X.07DI.A06(); null when absent or ambiguous. */
    static Getter getter(Class<?> fragment, Class<?> generator) {
        Getter found = null;
        for (Class<?> type = fragment; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Class<?> holder = field.getType();
                // Framework types (View, Handler, Bundle...) never return an Instagram generator.
                // Their loader is the boot one: null on a JVM, the BootClassLoader on ART.
                if (Modifier.isStatic(field.getModifiers()) || holder.isPrimitive()
                        || holder.getClassLoader() == BOOT) continue;
                Method[] methods;
                try {
                    methods = holder.getDeclaredMethods();
                } catch (LinkageError ignored) {
                    continue; // a signature names a class this build stripped
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

    /** The interface's only abstract method when it takes one value, as a stream consumer does. */
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
