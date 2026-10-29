package ps.reso.instaeclipse.mods.ui;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache.Found;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class InboxDiscoverPeopleHook {

    private static final String TAG = "(IE|InboxDiscover) ";
    private static final String LOOKUP_ANCHOR = "No section generator found for section type";
    private static final Set<String> dropped = ConcurrentHashMap.newKeySet();

    public void install(ClassLoader loader) {
        try {
            InboxSectionPath path = null;
            for (Found data : DexKitQueryCache.methods(FindMethod.create()
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
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.disableDiscoverPeople && !FeatureFlags.hideDmSuggestions && !FeatureFlags.hideNotesTray) return;
                    try {
                        String type = sections.typeOf(param.args);
                        if (!InboxSectionPath.drops(type, FeatureFlags.hideDmSuggestions, FeatureFlags.disableDiscoverPeople,
                                FeatureFlags.hideNotesTray)) return;
                        param.setResult(false);
                        if (dropped.add(type)) ModuleLog.detail(TAG + "dropped inbox section " + type);
                    } catch (Throwable t) {
                        if (dropped.add("error")) ModuleLog.warn(TAG + "Could not check an inbox section; people suggestions or Notes may still show: "
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
