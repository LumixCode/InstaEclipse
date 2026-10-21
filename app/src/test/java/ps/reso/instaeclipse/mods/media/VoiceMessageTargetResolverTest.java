package ps.reso.instaeclipse.mods.media;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class VoiceMessageTargetResolverTest {
    static final class Voice {}
    static final class Message {
        Voice audio;
        Message repliedTo;
    }
    static final class SaveSelection { Message selected; }
    static final class AmbiguousSelection { Message first; Message second; }
    static final class Thread { SaveSelection previousSelection; }

    @Test public void resolvesSelectedMessageAndSaveWrapper() {
        Message message = new Message();
        message.audio = new Voice();
        SaveSelection wrapper = new SaveSelection();
        wrapper.selected = message;
        assertSame(message.audio, find(message));
        assertSame(message.audio, find(wrapper));
    }

    @Test public void doesNotDownloadQuotedVoiceWhenSelectedMessageIsText() {
        Message quotedVoice = new Message();
        quotedVoice.audio = new Voice();
        Message text = new Message();
        text.repliedTo = quotedVoice;
        SaveSelection wrapper = new SaveSelection();
        wrapper.selected = text;
        assertNull(find(text));
        assertNull(find(wrapper));
    }

    @Test public void doesNotSearchAnUnrelatedThreadSelection() {
        Message voice = new Message();
        voice.audio = new Voice();
        SaveSelection wrapper = new SaveSelection();
        wrapper.selected = voice;
        Thread thread = new Thread();
        thread.previousSelection = wrapper;
        assertNull(find(thread));
        assertNull(find(null));
    }

    @Test public void neverFallsThroughFromCurrentVoiceToQuotedVoice() {
        Message current = new Message();
        current.audio = new Voice();
        current.repliedTo = new Message();
        current.repliedTo.audio = new Voice();
        assertSame(current.audio, find(current));
    }

    @Test public void rejectsAChangedWrapperWithConflictingMessages() {
        AmbiguousSelection selection = new AmbiguousSelection();
        selection.first = new Message();
        selection.first.audio = new Voice();
        selection.second = new Message();
        selection.second.audio = new Voice();
        assertNull(find(selection));
        selection.second = selection.first;
        assertSame(selection.first.audio, find(selection));
    }

    private static Object find(Object value) {
        return VoiceMessageTargetResolver.find(value, Message.class, Voice.class);
    }
}
