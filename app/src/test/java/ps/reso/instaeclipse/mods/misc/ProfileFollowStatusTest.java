package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static ps.reso.instaeclipse.mods.misc.ProfileFollowStatus.Status.*;

public class ProfileFollowStatusTest {
    @Test public void missingRelationshipNeverBecomesNegative() {
        assertEquals(HIDDEN, ProfileFollowStatus.from("me", "other", null));
        assertEquals(HIDDEN, ProfileFollowStatus.from("me", "other", Map.of("following", true)));
        Map<String, Object> partial = new HashMap<>();
        partial.put("followed_by", null);
        assertEquals(HIDDEN, ProfileFollowStatus.from("me", "other", partial));
        assertEquals(HIDDEN, ProfileFollowStatus.from("me", "other", Map.of("followed_by", "false")));
    }

    @Test public void selfAndUnknownAccountHideRelationship() {
        assertEquals(HIDDEN, ProfileFollowStatus.from("me", "me", Map.of("followed_by", false)));
        assertEquals(HIDDEN, ProfileFollowStatus.from(null, "other", Map.of("followed_by", true)));
        assertEquals(HIDDEN, ProfileFollowStatus.from("me", "", Map.of("followed_by", true)));
    }

    @Test public void mutualRequiresBothKnownDirections() {
        assertEquals(MUTUAL, ProfileFollowStatus.from("me", "other", Map.of("followed_by", true, "following", true)));
        assertEquals(FOLLOWS_YOU, ProfileFollowStatus.from("me", "other", Map.of("followed_by", true)));
        assertEquals(FOLLOWS_YOU, ProfileFollowStatus.from("me", "other", Map.of("followed_by", true, "following", false)));
    }

    @Test public void knownNegativeRemainsNegativeEvenWhenYouFollowThem() {
        assertEquals(DOES_NOT_FOLLOW, ProfileFollowStatus.from("me", "other", Map.of("followed_by", false, "following", true)));
    }

    @Test public void rebindingAnotherAccountDoesNotReusePreviousRelationship() {
        assertEquals(MUTUAL, ProfileFollowStatus.from("account1", "other", Map.of("followed_by", true, "following", true)));
        assertEquals(HIDDEN, ProfileFollowStatus.from("account2", "other", Map.of()));
        assertEquals(DOES_NOT_FOLLOW, ProfileFollowStatus.from("account2", "other", Map.of("followed_by", false)));
    }

    @Test public void usesProfileHeaderEvenWhenLegacyViewModelHasNoUser() throws Exception {
        Object displayed = new Object();
        Object loggedIn = new Object();
        assertSame(displayed, ProfileFollowStatus.boundUser(List.of(headerUser()),
                new Object[]{1, loggedIn, new Header(displayed), new LegacyViewModel(null)}));
    }

    @Test public void nullOrAmbiguousHeadersNeverSelectAnUnrelatedUser() throws Exception {
        Field user = headerUser();
        assertNull(ProfileFollowStatus.boundUser(List.of(user), new Object[]{new Object(), new Header(null)}));
        assertNull(ProfileFollowStatus.boundUser(List.of(user),
                new Object[]{new Header(new Object()), new Header(new Object())}));
    }

    @Test public void profileRebindAlwaysReadsTheCurrentHeader() throws Exception {
        Field user = headerUser();
        Object first = new Object();
        Object second = new Object();
        assertSame(first, ProfileFollowStatus.boundUser(List.of(user), new Object[]{new Header(first)}));
        assertSame(second, ProfileFollowStatus.boundUser(List.of(user), new Object[]{new Header(second)}));
        assertNull(ProfileFollowStatus.boundUser(List.of(user), new Object[]{new Header(null)}));
    }

    @Test public void schemaPreservesUnknownAndExplicitFalse() throws Exception {
        assertEquals(HIDDEN, schemaStatus("me", schema("other", null, null)));
        assertEquals(DOES_NOT_FOLLOW, schemaStatus("me", schema("other", false, true)));
        assertEquals(FOLLOWS_YOU, schemaStatus("me", schema("other", true, null)));
        assertEquals(MUTUAL, schemaStatus("me", schema("other", true, true)));
    }

    @Test public void multipleProjectionsMayCompleteButNeverContradictOneProfile() throws Exception {
        assertEquals(MUTUAL, schemaStatus("me", schema("other", true, null), schema("other", null, true)));
        assertEquals(HIDDEN, schemaStatus("me", schema("other", true, true), schema("other", false, true)));
        assertEquals(HIDDEN, schemaStatus("me", schema("first", true, true), schema("second", true, true)));
    }

    @Test public void schemaCannotLeakAcrossSelfAccountOrRebind() throws Exception {
        assertEquals(HIDDEN, schemaStatus("me", schema("me", true, true)));
        assertEquals(HIDDEN, schemaStatus(null, schema("other", true, true)));
        assertEquals(MUTUAL, schemaStatus("account1", schema("other", true, true)));
        assertEquals(HIDDEN, schemaStatus("account2", schema("other", null, null)));
        assertEquals(HIDDEN, schemaStatus("me", schema(null, true, true)));
    }

    @Test public void schemaSelectionIsBoundToTheCurrentHeaderArgument() throws Exception {
        Field projection = SchemaHeader.class.getDeclaredField("projection");
        Field data = SchemaWrapper.class.getDeclaredField("data");
        projection.setAccessible(true);
        data.setAccessible(true);
        Schema first = schema("first", true, true);
        Schema second = schema("second", false, false);
        assertEquals(List.of(first), ProfileFollowStatus.boundSchemas(List.of(projection), data,
                new Object[]{new Header(second), new SchemaHeader(new SchemaWrapper(first))}));
        assertEquals(List.of(second), ProfileFollowStatus.boundSchemas(List.of(projection), data,
                new Object[]{new SchemaHeader(new SchemaWrapper(second))}));
        assertEquals(List.of(), ProfileFollowStatus.boundSchemas(List.of(projection), data,
                new Object[]{new SchemaHeader(null)}));
    }

    private static ProfileFollowStatus.Status schemaStatus(String viewer, Schema... schemas) throws Exception {
        return ProfileFollowStatus.fromSchemas(viewer, List.of((Object[]) schemas), Schema.class.getMethod("string", int.class),
                Schema.class.getMethod("child", int.class), Schema.class.getMethod("bool", int.class));
    }

    private static Schema schema(String id, Boolean followedBy, Boolean following) {
        Schema user = new Schema();
        user.values.put("id".hashCode(), id);
        if (followedBy != null || following != null) {
            Schema relationship = new Schema();
            relationship.values.put("followed_by".hashCode(), followedBy);
            relationship.values.put("following".hashCode(), following);
            user.values.put("friendship_status".hashCode(), relationship);
        }
        return user;
    }

    public static final class Schema {
        final Map<Integer, Object> values = new HashMap<>();
        public String string(int key) { return (String) values.get(key); }
        public Schema child(int key) { return (Schema) values.get(key); }
        public Boolean bool(int key) { return (Boolean) values.get(key); }
    }

    private static final class SchemaWrapper {
        final Schema data;
        SchemaWrapper(Schema data) { this.data = data; }
    }

    private static final class SchemaHeader {
        final SchemaWrapper projection;
        SchemaHeader(SchemaWrapper projection) { this.projection = projection; }
    }

    private static Field headerUser() throws Exception {
        Field field = Header.class.getDeclaredField("user");
        field.setAccessible(true);
        return field;
    }

    private static final class Header {
        final Object user;
        Header(Object user) { this.user = user; }
    }

    private static final class LegacyViewModel {
        final Object user;
        LegacyViewModel(Object user) { this.user = user; }
    }
}
