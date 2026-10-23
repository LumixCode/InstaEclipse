package ps.reso.instaeclipse.mods.devops;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

final class DevGatePick {

    static final String GATE_PROTO = "(Lcom/instagram/common/session/UserSession;)Z";

    private DevGatePick() {}

    static boolean hasGateShape(String descriptor) {
        return descriptor != null && descriptor.endsWith(GATE_PROTO);
    }

    static String choose(List<String> invokes, Predicate<String> isStatic) {
        Set<String> gates = new LinkedHashSet<>();
        for (String descriptor : invokes) {
            if (hasGateShape(descriptor) && isStatic.test(descriptor)) gates.add(descriptor);
        }
        return gates.size() == 1 ? gates.iterator().next() : null;
    }
}
