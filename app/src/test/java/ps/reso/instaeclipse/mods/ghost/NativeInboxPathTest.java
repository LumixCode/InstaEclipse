package ps.reso.instaeclipse.mods.ghost;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Fixtures mirror Instagram 451's inbox classes (446 has the same shape under other names). */
public class NativeInboxPathTest {
    /** X.0Hli (446 X.0qDz): the stream consumer interface. */
    interface Stream { void accept(Object value); }

    /** kotlin Function3: an interface field that is not a one-value consumer. */
    interface Sections { Object invoke(Object a, Object b, Object c); }

    /** X.09TE (446 X.0AJc): one lambda class for every stream variant and other owners. */
    static final class Variant implements Stream {
        final int $t;
        final Object owner;

        Variant(Object owner, int variant) {
            this.owner = owner;
            this.$t = variant;
        }

        @Override public void accept(Object value) {}
    }

    /** X.07Ln (446 X.05Ho). */
    static final class Generator {
        final Stream threads = new Variant(this, 3);
        final Stream rows = new Variant(this, 2);
        final Sections sections = (a, b, c) -> a;
        final Object session = new Object();
    }

    /** X.07Gu: another owner of the same lambda class that also receives a List. */
    static final class OtherOwner {
        final Stream list = new Variant(this, 9);
    }

    /** X.07DI (446 X.04g6): only its no-arg instance getter returns the generator. */
    static final class Controller {
        private final Generator generator = new Generator();

        public Generator generator() { return generator; }
        public Generator generator(int ignored) { return null; }
        public static Generator create() { return new Generator(); }
    }

    static class BaseFragment {
        Controller controller = new Controller();
    }

    /** X.04Gm (446 X.04Za). */
    static final class InboxFragment extends BaseFragment {
        final String module = "direct_inbox";
        int count;
    }

    static final class TwoControllers {
        Controller first = new Controller();
        Controller second = new Controller();
    }

    /** A lambda with two places a generator could live: no owner can be chosen. */
    static final class AmbiguousVariant implements Stream {
        final Object first = null;
        final Object second = null;

        @Override public void accept(Object value) {}
    }

    /** 446's lambda constructor takes the generator; an owner typed as the generator also counts. */
    static final class TypedVariant implements Stream {
        final TypedGenerator owner;

        TypedVariant(TypedGenerator owner) { this.owner = owner; }

        @Override public void accept(Object value) {}
    }

    static final class TypedGenerator {
        final Stream rows = new TypedVariant(this);
    }

    private static Method accept(Class<?> type) throws NoSuchMethodException {
        return type.getDeclaredMethod("accept", Object.class);
    }

    @Test public void callbacksAreTheOneValueLambdaTheGeneratorConstructs() throws Exception {
        List<Method> found = NativeInboxPath.callbacks(Generator.class, Arrays.asList(
                Object.class, Variant.class, StringBuilder.class, OtherOwner.class, Variant.class));
        assertEquals(Collections.singletonList(accept(Variant.class)), found);
    }

    @Test public void noCallbackWhenTheGeneratorBuildsNoConsumerLambda() {
        assertTrue(NativeInboxPath.callbacks(Generator.class, Arrays.asList(Object.class, String.class)).isEmpty());
    }

    @Test public void rowListIsOnlyAListThroughTheGeneratorsOwnConsumer() throws Exception {
        NativeInboxPath path = NativeInboxPath.of(Generator.class, accept(Variant.class));
        assertNotNull(path);
        Generator generator = new Generator();
        List<Object> rows = Collections.singletonList("row");
        assertSame(generator, path.rowGenerator(generator.rows, rows));
        assertSame(generator, path.rowGenerator(generator.threads, Collections.emptyList()));
        assertNull(path.rowGenerator(generator.rows, "not a list"));
        assertNull(path.rowGenerator(generator.rows, null));
        assertNull(path.rowGenerator(new OtherOwner().list, rows));
        assertNull(path.rowGenerator(new Variant(generator, 2), rows)); // not held by the generator
        assertNull(path.rowGenerator(new Variant(null, 2), rows));
    }

    @Test public void consumersAreEveryStreamFieldButNotOtherInterfaces() throws Exception {
        NativeInboxPath path = NativeInboxPath.of(Generator.class, accept(Variant.class));
        Generator generator = new Generator();
        List<Object> consumers = path.consumersOf(generator);
        assertEquals(2, consumers.size());
        assertTrue(consumers.contains(generator.rows));
        assertTrue(consumers.contains(generator.threads));
        assertFalse(consumers.contains(generator.sections));
    }

    @Test public void ownerFieldMustBeUnambiguous() throws Exception {
        assertNull(NativeInboxPath.of(Generator.class, accept(AmbiguousVariant.class)));
    }

    @Test public void generatorTypedOwnerAsOn446IsAccepted() throws Exception {
        NativeInboxPath path = NativeInboxPath.of(TypedGenerator.class, accept(TypedVariant.class));
        assertNotNull(path);
        TypedGenerator generator = new TypedGenerator();
        assertSame(generator, path.rowGenerator(generator.rows, Collections.emptyList()));
    }

    @Test public void lambdaOfAnotherGeneratorIsNotAPath() throws Exception {
        // TypedVariant cannot hold a Generator, and Generator holds no TypedVariant.
        assertNull(NativeInboxPath.of(Generator.class, accept(TypedVariant.class)));
    }

    @Test public void getterWalksFragmentFieldsIncludingSuperclasses() throws Exception {
        NativeInboxPath.Getter getter = NativeInboxPath.getter(InboxFragment.class, Generator.class);
        assertNotNull(getter);
        InboxFragment fragment = new InboxFragment();
        assertSame(fragment.controller.generator(), getter.generatorOf(fragment));
        fragment.controller = null;
        assertNull(getter.generatorOf(fragment));
    }

    @Test public void getterIsNullWhenAmbiguousOrAbsent() {
        assertNull(NativeInboxPath.getter(TwoControllers.class, Generator.class));
        assertNull(NativeInboxPath.getter(OtherOwner.class, Generator.class));
    }
}
