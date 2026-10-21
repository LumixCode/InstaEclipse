package ps.reso.instaeclipse.utils.ghost;

import org.junit.Test;
import static org.junit.Assert.*;

public class UnsentIdentityTest {
    public interface Item { int getType(); }
    public static class Message {}
    public static class Data implements Item {
        public Data quoted;
        public Message current;
        @Override public int getType() { return 1; }
    }
    public static class Holder { public Item bound; public Data staleListenerData; }

    @Test public void selectsCurrentBoundDataInsteadOfOldListenerOrQuotedItem() {
        Data current = new Data();
        current.current = new Message();
        current.quoted = new Data();
        current.quoted.current = new Message();
        Holder holder = new Holder();
        holder.bound = current;
        holder.staleListenerData = current.quoted;
        assertSame(current, UnsentIdentity.boundData(holder, Message.class));
        assertSame(current.current, UnsentIdentity.directValue(current, Message.class));
    }

    @Test public void recycledHolderUsesNewMessageAndEmptyBindingClearsIt() {
        Holder holder = new Holder();
        Data old = new Data(); old.current = new Message();
        Data fresh = new Data(); fresh.current = new Message();
        holder.bound = old;
        assertSame(old, UnsentIdentity.boundData(holder, Message.class));
        holder.bound = fresh;
        assertSame(fresh, UnsentIdentity.boundData(holder, Message.class));
        holder.bound = null;
        holder.staleListenerData = old;
        assertNull(UnsentIdentity.boundData(holder, Message.class));
    }

    @Test public void quoteOnlyDoesNotIdentifyParentAsThatMessage() {
        Data parent = new Data(); parent.quoted = new Data(); parent.quoted.current = new Message();
        Holder holder = new Holder(); holder.bound = parent;
        assertNull(UnsentIdentity.boundData(holder, Message.class));
    }

    @Test public void ambiguousMessageFieldsFailClosed() {
        class Ambiguous { public Message one = new Message(); public Message two = new Message(); }
        assertNull(UnsentIdentity.directValue(new Ambiguous(), Message.class));
    }

    @Test public void baseTypedOwnerFieldUsesRuntimeTypeWithoutSearchingNestedOwners() {
        class Owner { public Object current = new Message(); public Data nested = new Data(); }
        Owner owner = new Owner();
        owner.nested.current = new Message();
        assertSame(owner.current, UnsentIdentity.directValue(owner, Message.class));
        owner.current = null;
        assertNull(UnsentIdentity.directValue(owner, Message.class));
    }

    @Test public void multipleFieldsOfSameOwnerAreAllowedOnlyWhenTheyReferenceSameObject() {
        class Owner { public Object one; public Object two; }
        Owner owner = new Owner();
        owner.one = owner.two = new Message();
        assertSame(owner.one, UnsentIdentity.directValue(owner, Message.class));
        owner.two = new Message();
        assertNull(UnsentIdentity.directValue(owner, Message.class));
    }

    public static class TextMessage {
        public String body;
        public TextMessage quoted;
    }

    @Test public void readsExactBodyIncludingShortNumericEmojiAndUppercaseText() throws Exception {
        TextMessage message = new TextMessage();
        message.quoted = new TextMessage();
        message.quoted.body = "A much longer quoted message must not replace the current body";
        for (String text : new String[]{"hi", "OK", "123", "x", "🙂"}) {
            message.body = text;
            assertEquals(text, UnsentIdentity.messageText(message, TextMessage.class.getField("body")));
        }
    }

    @Test public void missingTextFieldDoesNotGuessFromNestedContent() throws Exception {
        TextMessage message = new TextMessage();
        message.quoted = new TextMessage();
        message.quoted.body = "Quoted content";
        assertNull(UnsentIdentity.messageText(message, null));
        assertNull(UnsentIdentity.messageText(message, TextMessage.class.getField("quoted")));
        assertNull(UnsentIdentity.messageText(new Message(), TextMessage.class.getField("body")));
    }

    @Test public void dualThreadIdsUseCanonicalIdRatherThanRejectingTheKey() {
        assertEquals("canonical", UnsentIdentity.threadId(
                "DirectThreadKey{mThreadId='canonical', mThreadV2Id='different-v2'}"));
        assertEquals("canonical", UnsentIdentity.threadId(
                "DirectThreadKey{mThreadId='canonical', mThreadV2Id='null'}"));
    }

    @Test public void missingCanonicalThreadDoesNotFallBackToAnotherNamespace() {
        assertNull(UnsentIdentity.threadId("DirectThreadKey{mThreadId='null', mThreadV2Id='123'}"));
        assertNull(UnsentIdentity.threadId("DirectThreadKey{mThreadV2Id='123'}"));
        assertNull(UnsentIdentity.threadId("DirectThreadKey{mThreadId='', mThreadV2Id='123'}"));
        assertNull(UnsentIdentity.threadId("UnsupportedKey{mThreadId='123'}"));
        assertNull(UnsentIdentity.threadId(null));
    }
}
