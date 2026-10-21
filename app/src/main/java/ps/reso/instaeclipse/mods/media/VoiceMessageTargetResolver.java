package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Reads only the selected message's voice payload, never a quoted/replied-to message. */
final class VoiceMessageTargetResolver {
    private VoiceMessageTargetResolver() {}

    static Object find(Object value, Class<?> messageType, Class<?> voiceType) {
        if (value == null) return null;
        if (messageType.isInstance(value)) return readTypedField(value, voiceType);
        // Instagram's save action wraps the selected message in a MessageInfo object.
        Object message = readTypedField(value, messageType);
        return message == null ? null : readTypedField(message, voiceType);
    }

    static Object readTypedField(Object owner, Class<?> type) {
        Object result = null;
        for (Class<?> cls = owner.getClass(); cls != null && cls != Object.class;
             cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (value == null) continue;
                    if (result != null && result != value) return null;
                    result = value;
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        return result;
    }
}
