package ps.reso.instaeclipse.utils.ghost;

import com.instagram.common.session.UserSession;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.*;

public class UnsentIdentityCacheTest {
    public static class SessionOwner {
        private final UserSession session;
        public SessionOwner(UserSession session) { this.session = session; }
        public UserSession getSession() { return session; }
    }
    public static class FieldOwner { public Object session; }

    @Test public void accountReadsGetSessionAndRepeatsWithTheSameResult() {
        SessionOwner owner = new SessionOwner(new UserSession("1"));
        for (int i = 0; i < 3; i++) assertEquals("1", UnsentIdentity.account(owner));
        assertEquals("2", UnsentIdentity.account(new SessionOwner(new UserSession("2"))));
    }

    @Test public void ownerWithoutGetSessionReadsTheCurrentValueOfItsSessionField() {
        FieldOwner owner = new FieldOwner();
        assertNull(UnsentIdentity.account(owner));
        owner.session = new UserSession("1");
        assertEquals("1", UnsentIdentity.account(owner));
        owner.session = new UserSession("2");
        assertEquals("2", UnsentIdentity.account(owner));
        assertEquals("3", UnsentIdentity.account(new UserSession("3")));
    }

    @Test public void methodLookupsAreCachedIncludingMisses() {
        Method first = UnsentIdentity.publicMethod(SessionOwner.class, "getSession");
        assertNotNull(first);
        // getMethod returns a new Method every call: the same instance means it came from the cache.
        assertSame(first, UnsentIdentity.publicMethod(SessionOwner.class, "getSession"));
        assertNull(UnsentIdentity.publicMethod(FieldOwner.class, "getSession"));
        assertNull(UnsentIdentity.publicMethod(FieldOwner.class, "getSession"));
    }
}
