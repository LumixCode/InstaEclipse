package ps.reso.instaeclipse.mods.misc;

import org.junit.Test;
import static org.junit.Assert.*;

public class ExternalLinkPolicyTest {
    @Test public void plainWebLinkKeepsItsQueryAndFragment() {
        assertEquals("https://example.com/path?u=keep%2Bthis&x=2#section",
                ExternalLinkPolicy.browserUrl("https://example.com/path?u=keep%2Bthis&x=2#section"));
    }

    @Test public void unwrapsOnlyInstagramLinkRedirect() {
        assertEquals("https://example.com/watch?q=a+b#clip", ExternalLinkPolicy.browserUrl(
                "https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2Fwatch%3Fq%3Da%2Bb%23clip&e=tracking"));
        assertEquals("https://l.instagram.com.evil.test/?u=value", ExternalLinkPolicy.browserUrl(
                "https://l.instagram.com.evil.test/?u=value"));
    }

    @Test public void unwrapsFacebookLinkRedirect() {
        assertEquals("https://example.com/", ExternalLinkPolicy.browserUrl(
                "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.com%2F&h=x"));
        assertNull(ExternalLinkPolicy.browserUrl(
                "https://l.facebook.com/l.php?u=https%3A%2F%2Fa.test&u=https%3A%2F%2Fb.test"));
    }

    @Test public void unescapedAdMacrosStillOpenExternally() {
        assertEquals("https://example.com/?utm_content=a%7Cb",
                ExternalLinkPolicy.browserUrl("https://example.com/?utm_content=a|b"));
        assertEquals("https://example.com/%7Bid%7D", ExternalLinkPolicy.browserUrl(
                "https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2F%7Bid%7D"));
        assertEquals("https://example.com/a%20b", ExternalLinkPolicy.browserUrl(
                "https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2Fa%20b"));
        assertNull(ExternalLinkPolicy.browserUrl("https://exa mple.com/"));
    }

    @Test public void internalNavigationStaysInternal() {
        for (String value : new String[]{"instagram://media?id=1", "https://instagram.com/p/abc/",
                "https://www.Instagram.com./accounts/login/", "https://i.instagram.com/api/v1/",
                "https://l.instagram.com/?u=https%3A%2F%2Finstagram.com%2Freel%2Fabc",
                "https://www.facebook.com/x", "https://fb.com/x", "https://ig.me/m/a",
                "https://instagr.am/p/abc/", "https://threads.net/@a", "https://www.threads.com/@a",
                "https://l.instagram.com/?u=https%3A%2F%2Fm.facebook.com%2Fx"}) {
            assertNull(value, ExternalLinkPolicy.browserUrl(value));
        }
    }

    @Test public void malformedAndNonWebTargetsFallBackToHost() {
        for (String value : new String[]{null, "", "hello", "/profile/", "javascript:alert(1)",
                "file:///sdcard/a", "intent://example", "mailto:a@example.com", "https://user:pass@example.com/",
                "https://l.instagram.com/?e=only", "https://l.instagram.com/?u=%ZZ",
                "https://l.instagram.com/?u=https%3A%2F%2Fa.test&u=https%3A%2F%2Fb.test",
                "https://l.instagram.com/?u=javascript%3Aalert(1)"}) {
            assertNull(value, ExternalLinkPolicy.browserUrl(value));
        }
    }
}
