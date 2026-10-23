package ps.reso.instaeclipse.mods.ui;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Fixtures mirror Instagram 451's inbox section classes (446 has the same shape under other names). */
public class InboxSectionPathTest {
    /** X.07JC (446 X.05Eo): the section types, constant names kept. */
    enum Type { THREADS, SEARCH_BAR, TRAY, PENDING_FOLLOW_REQUESTS, SUGGESTIONS, RECOMMENDED_USER_SUGGESTIONS, FIND_PEOPLE }

    /** X.07U5 (446 X.0DBC): the abstract section generator holding its type. */
    abstract static class Section {
        final Type type;
        final Object label = "not the type";

        Section(Type type) { this.type = type; }
    }

    /** X.0ARk: a list section between the base and the final generators. */
    abstract static class ListSection extends Section {
        ListSection(Type type) { super(type); }
    }

    /** X.07LL: FIND_PEOPLE. */
    static final class FindPeople extends ListSection {
        FindPeople() { super(Type.FIND_PEOPLE); }
    }

    /** X.07Lo (446 X.05Hp): type lookup, list resolver, emitter and list emitter. */
    static final class Sections {
        static Section lookup(Type type, Map<Type, Section> all) { return all.get(type); }
        static List<Section> resolve(List<Type> types, Map<Type, Section> all) { return null; }
        static boolean emit(Object out, Section section, Object state, Object capabilities) { return true; }
        static void emitAll(Object out, Object state, Object capabilities, List<Section> sections) {}
        static boolean unrelated(Object out, Object state) { return false; }
        boolean instanceEmit(Section section) { return true; }
    }

    static final class TwoEmitters {
        static Section lookup(Type type, Map<Type, Section> all) { return null; }
        static boolean emit(Object out, Section section) { return true; }
        static boolean emitAgain(Section section) { return true; }
    }

    static final class NoEmitter {
        static Section lookup(Type type, Map<Type, Section> all) { return null; }
        static void emit(Object out, Section section) {}
    }

    abstract static class TwoTypes {
        final Type type = null;
        final Type other = null;
    }

    static final class TwoTypeFields {
        static TwoTypes lookup(Type type) { return null; }
        static boolean emit(TwoTypes section) { return true; }
    }

    static final class NotEnums {
        static Section lookup(String type, Map<String, Section> all) { return null; }
        static boolean emit(Object out, Section section) { return true; }
        Section instanceLookup(Type type) { return null; }
    }

    private static Method method(Class<?> owner, String name) {
        for (Method method : owner.getDeclaredMethods()) if (method.getName().equals(name)) return method;
        throw new AssertionError(name);
    }

    @Test public void emitterIsTheStaticBooleanMethodTakingTheSectionBase() throws Exception {
        InboxSectionPath path = InboxSectionPath.of(method(Sections.class, "lookup"));
        assertNotNull(path);
        assertEquals(method(Sections.class, "emit"), path.emit);
        assertEquals("FIND_PEOPLE", path.typeOf(new Object[]{null, new FindPeople(), null, null}));
    }

    @Test public void typeIsReadFromTheBaseFieldOfEverySubclass() throws Exception {
        InboxSectionPath path = InboxSectionPath.of(method(Sections.class, "lookup"));
        assertNotNull(path);
        Section threads = new Section(Type.THREADS) {};
        Section recommended = new ListSection(Type.RECOMMENDED_USER_SUGGESTIONS) {};
        assertEquals("THREADS", path.typeOf(new Object[]{null, threads, null, null}));
        assertEquals("RECOMMENDED_USER_SUGGESTIONS", path.typeOf(new Object[]{null, recommended, null, null}));
        assertNull(path.typeOf(new Object[]{null, new Section(null) {}, null, null}));
        assertNull(path.typeOf(new Object[]{null, null, null, null}));
    }

    @Test public void noPathWhenTheEmitterIsMissingOrAmbiguous() {
        assertNull(InboxSectionPath.of(method(TwoEmitters.class, "lookup")));
        assertNull(InboxSectionPath.of(method(NoEmitter.class, "lookup")));
    }

    @Test public void noPathWhenTheSectionHoldsTwoTypeFields() {
        assertNull(InboxSectionPath.of(method(TwoTypeFields.class, "lookup")));
    }

    @Test public void noPathUnlessTheLookupIsStaticAndTakesTheTypeEnum() {
        assertNull(InboxSectionPath.of(method(NotEnums.class, "lookup")));
        assertNull(InboxSectionPath.of(method(NotEnums.class, "instanceLookup")));
        assertNull(InboxSectionPath.of(method(Sections.class, "resolve")));
    }

    @Test public void onlyThePeopleSuggestionSectionsAreDropped() {
        assertTrue(InboxSectionPath.suggestsPeople("FIND_PEOPLE"));
        assertTrue(InboxSectionPath.suggestsPeople("RECOMMENDED_USER_SUGGESTIONS"));
        for (String kept : new String[]{"THREADS", "SEARCH_BAR", "TRAY", "PENDING_FOLLOW_REQUESTS",
                "SUGGESTIONS", "HIDDEN_REQUESTS_ROW", "CHANNEL_INVITES", "LOAD_MORE", "find_people", ""}) {
            assertFalse(kept, InboxSectionPath.suggestsPeople(kept));
        }
        assertFalse(InboxSectionPath.suggestsPeople(null));
    }
}
