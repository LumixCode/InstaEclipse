package ps.reso.instaeclipse.mods.ui;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Disable Discover People in Messages: the Direct inbox, the hidden chats inbox included, never
 * builds its "find friends" section (FIND_PEOPLE: the header row "find_people_header_row" and the
 * contacts / search activation cards, 451 X.07LL with X.07LK) or its suggested accounts
 * (RECOMMENDED_USER_SUGGESTIONS, 451 X.07Jo, filled from dynamic_onboarding/get_direct_empty_state/).
 *
 * Neither comes from the thread rows. Instagram adds FIND_PEOPLE while the inbox shows fewer than
 * five threads (the X.07LL branch of 451 X.07Lo.A04) and the suggested accounts below a server
 * threshold, so the hidden chats inbox, which submits only the hidden threads, always got them.
 * Skipping those sections in Instagram's section emitter ({@link InboxSectionPath}, anchored on
 * the section lookup's "No section generator found for section type " string) means their rows
 * are never built; every other section is untouched. Applies on the inbox's next update.
 */
public class InboxDiscoverPeopleHook {

    private static final String TAG = "(IE|InboxDiscover) ";
    private static final String LOOKUP_ANCHOR = "No section generator found for section type";
    /** Section types already reported as dropped: one log line each per process. */
    private static final Set<String> dropped = ConcurrentHashMap.newKeySet();

    public void install(DexKitBridge bridge, ClassLoader loader) {
        try {
            InboxSectionPath path = null;
            for (MethodData data : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(LOOKUP_ANCHOR)))) {
                if (!data.isMethod()) continue;
                InboxSectionPath found = InboxSectionPath.of(data.getMethodInstance(loader));
                if (found == null) continue;
                if (path != null && !path.emit.equals(found.emit)) {
                    throw new IllegalStateException("inbox section emitter ambiguous");
                }
                path = found;
            }
            if (path == null) throw new IllegalStateException("inbox section emitter not found");
            InboxSectionPath sections = path;
            XposedBridge.hookMethod(sections.emit, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.disableDiscoverPeople) return;
                    try {
                        String type = sections.typeOf(param.args);
                        if (!InboxSectionPath.suggestsPeople(type)) return;
                        param.setResult(false); // what the emitter returns for a section without rows
                        if (dropped.add(type)) ModuleLog.detail(TAG + "dropped inbox section " + type);
                    } catch (Throwable t) {
                        if (dropped.add("error")) ModuleLog.warn(TAG + "Could not check an inbox section; people suggestions may still show: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage());
                    }
                }
            });
            FeatureStatusTracker.setHooked("DisableDiscoverPeople");
            ModuleLog.ok(TAG + "ready");
            ModuleLog.detail(TAG + "hooked " + sections.emit.getDeclaringClass().getName() + "."
                    + sections.emit.getName());
        } catch (Throwable t) {
            ModuleLog.error(TAG + "Will not work in this Instagram version: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
