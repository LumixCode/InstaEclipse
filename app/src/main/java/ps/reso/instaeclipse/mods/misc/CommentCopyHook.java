package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.PopupWindow;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.media.CommentMediaDownload;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.text.CopyFeedback;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.utils.users.UserUtils;

public class CommentCopyHook {

    private static final String EFFECT_HANDLER_CLASS =
            "com.instagram.comments.mvvm.view.fragment.CommentViewUiEffectHandler$handleCommentUiEffects$1";
    private static final String CACHE_KEY = "CommentCopyPresenter_v1";

    private static final class MenuCall {
        final Object comment;
        boolean rowsSeen, rowAdded;
        MenuCall(Object comment) { this.comment = comment; }
    }
    private static final ThreadLocal<MenuCall> menuCall = new ThreadLocal<>();
    private static final Map<Class<?>, Constructor<?>> rowConstructors = new ConcurrentHashMap<>();
    private static volatile boolean loggedShapeMismatch;

    public void install(ClassLoader classLoader) {
        try {
            List<Method> menu = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(CACHE_KEY, classLoader) : null;
            if (menu == null || menu.size() < 2) {
                menu = findMenu(classLoader.loadClass(EFFECT_HANDLER_CLASS));
                if (menu.size() > 1) DexKitCache.saveMethods(CACHE_KEY, menu);
            }
            int hooked = 0;
            for (int i = 1; i < menu.size(); i++) {
                XposedBridge.hookMethod(menu.get(i), ADD_ROW_HOOK);
                hooked++;
            }
            if (hooked == 0) {
                if (FeatureFlags.enableCopyComment) FeatureStatusTracker.setBroken("CopyComment", R.string.ig_dialog_misc_copy_comment);
                ModuleLog.error("(InstaEclipse | CopyComment): Could not turn on copy comment: the comment long-press menu was not found in this Instagram version");
                return;
            }
            Method presenter = menu.get(0);
            XposedBridge.hookMethod(presenter, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    menuCall.set(new MenuCall(commentArg(param.args)));
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MenuCall call = menuCall.get();
                    menuCall.remove();
                    if (call != null && call.rowsSeen && !call.rowAdded && FeatureFlags.enableCopyComment
                            && !loggedShapeMismatch) {
                        loggedShapeMismatch = true;
                        ModuleLog.error("(InstaEclipse | CopyComment): The Copy row could not be added: the comment menu changed in this Instagram version");
                    }
                }
            });
            FeatureStatusTracker.setHooked("CopyComment");
            ModuleLog.ok("(InstaEclipse | CopyComment): Ready");
            ModuleLog.detail("(InstaEclipse | CopyComment): menu rows via " + presenter.getDeclaringClass().getName()
                    + "." + presenter.getName() + " + " + menu.get(1).getDeclaringClass().getName() + " (" + hooked + " list methods)");
        } catch (Throwable t) {
            if (FeatureFlags.enableCopyComment) FeatureStatusTracker.setBroken("CopyComment", R.string.ig_dialog_misc_copy_comment);
            ModuleLog.error("(InstaEclipse | CopyComment): Could not turn on copy comment", t);
        }
    }

    private static List<Method> findMenu(Class<?> handler) {
        List<Method> menu = new ArrayList<>();
        Method presenter = null;
        for (Field f : handler.getDeclaredFields()) {
            String type = f.getType().getName();
            if (type.startsWith("java.") || type.startsWith("android.") || type.startsWith("androidx.")) continue;
            for (Method m : f.getType().getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 5
                        && p[3] == List.class && p[4] == float.class) {
                    presenter = m;
                    break;
                }
            }
            if (presenter != null) break;
        }
        if (presenter == null) return menu;
        Class<?> popup = null;
        for (Field f : presenter.getDeclaringClass().getDeclaredFields()) {
            if (PopupWindow.class.isAssignableFrom(f.getType())) popup = f.getType();
        }
        if (popup == null) return menu;
        menu.add(presenter);
        for (Method m : popup.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() != void.class || p.length != 1 || p[0] != List.class
                    || Modifier.isStatic(m.getModifiers())) continue;
            menu.add(m);
        }
        return menu;
    }

    private static Object commentArg(Object[] args) {
        try {
            Class<?> user = Class.forName("com.instagram.user.model.User", false,
                    args[1] != null ? args[1].getClass().getClassLoader() : CommentCopyHook.class.getClassLoader());
            for (Object arg : args) {
                if (arg != null && !(arg instanceof List) && hasFieldOfType(arg.getClass(), user)) return arg;
            }
        } catch (Throwable ignored) {}
        return args.length > 1 ? args[1] : null;
    }

    private static boolean hasFieldOfType(Class<?> clazz, Class<?> target) {
        for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(clazz)) {
            if (target.isAssignableFrom(f.getType())) return true;
        }
        return false;
    }

    private static final XC_MethodHook ADD_ROW_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            MenuCall call = menuCall.get();
            if (call == null || call.rowAdded || !FeatureFlags.enableCopyComment) return;
            if (!(param.args[0] instanceof List<?> rows) || rows.isEmpty()) return;
            call.rowsSeen = true;
            try {
                Constructor<?> row = rowConstructor(rows.get(0).getClass());
                if (row == null) return;
                call.rowAdded = true;
                Object[] comment = CommentMediaDownload.comment(call.comment);
                String text = commentText(call.comment, comment);
                CommentMediaDownload.Found media = comment == null ? null
                        : CommentMediaDownload.find((Class<?>) comment[0], comment[1]);
                if (text == null && media == null) return;
                Context ctx = ((PopupWindow) param.thisObject).getContentView().getContext();
                List<Object> out = new ArrayList<>(rows);
                int at = firstTintedRow(rows);
                if (text != null) {
                    out.add(at++, newRow(row, rows.get(0), ctx, "instagram_copy_pano_outline_24",
                            I18n.t(ctx, R.string.ig_comment_copy_title), () -> copyToClipboard(ctx, text)));
                }
                if (media != null) {
                    String commenter = commenter(call.comment);
                    out.add(at, newRow(row, rows.get(0), ctx, "instagram_download_pano_outline_24",
                            I18n.t(ctx, media.gif ? R.string.ig_comment_download_gif : R.string.ig_comment_download_image),
                            () -> CommentMediaDownload.save(ctx, media, commenter)));
                }
                param.args[0] = out;
            } catch (Throwable t) {
                ModuleLog.warn("(InstaEclipse | CopyComment): Could not add the Copy row to this comment menu", t);
            }
        }
    };

    private static Constructor<?> rowConstructor(Class<?> rowClass) {
        Constructor<?> cached = rowConstructors.get(rowClass);
        if (cached != null) return cached;
        for (Constructor<?> c : rowClass.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length < 11 || p[0] != Drawable.class || p[1] != Drawable.class || !tapAndDismissOnly(p[2])
                    || p[3] != Integer.class || p[4] != String.class || p[5] != String.class) continue;
            boolean flags = true;
            for (int i = 6; i < p.length; i++) flags &= p[i] == boolean.class;
            if (!flags) continue;
            c.setAccessible(true);
            rowConstructors.put(rowClass, c);
            return c;
        }
        return null;
    }

    private static boolean tapAndDismissOnly(Class<?> callback) {
        if (!callback.isInterface()) return false;
        Method[] methods = callback.getMethods();
        if (methods.length != 2) return false;
        boolean tap = false, dismiss = false;
        for (Method m : methods) {
            if (m.getParameterCount() != 0) return false;
            tap |= m.getReturnType() == void.class;
            dismiss |= m.getReturnType() == boolean.class;
        }
        return tap && dismiss;
    }

    private static int firstTintedRow(List<?> rows) {
        for (int i = 0; i < rows.size(); i++) {
            Object row = rows.get(i);
            for (Field f : row.getClass().getDeclaredFields()) {
                if (f.getType() != Integer.class) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(row) != null) return i;
                } catch (Throwable ignored) {}
            }
        }
        return rows.size();
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private static Object newRow(Constructor<?> row, Object template, Context ctx, String icon,
                                 String label, Runnable onTap) throws Exception {
        Class<?> callbackType = row.getParameterTypes()[2];
        Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                new Class<?>[]{callbackType}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "equals": return proxy == args[0];
                            case "hashCode": return System.identityHashCode(proxy);
                            default: return "CopyCommentRow";
                        }
                    }
                    if (method.getReturnType() == boolean.class) return true;
                    if (method.getReturnType() == void.class) onTap.run();
                    return null;
                });
        Drawable drawable = null;
        int id = ResIds.drawable(ctx, icon);
        if (id != 0) drawable = ctx.getDrawable(id);
        Object[] args = new Object[row.getParameterCount()];
        args[1] = drawable;
        args[2] = callback;
        args[4] = label;
        for (int i = 6; i < args.length; i++) args[i] = false;
        Object created = row.newInstance(args);
        copyFlags(template, created);
        return created;
    }

    private static void copyFlags(Object from, Object to) {
        for (Field f : to.getClass().getDeclaredFields()) {
            if (f.getType() != boolean.class || Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                f.setBoolean(to, f.getBoolean(from));
            } catch (Throwable ignored) {}
        }
    }

    private static String commentText(Object holder, Object[] comment) {
        Method accessor = CaptionCopyHook.textAccessor();
        if (comment != null && accessor != null && accessor.getDeclaringClass().isInstance(comment[1])) {
            try {
                Object text = accessor.invoke(comment[1]);
                return text instanceof String s && !s.trim().isEmpty() ? s.trim() : null;
            } catch (Throwable ignored) {}
        }
        String text = holder == null ? null : findLongestTextField(holder);
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static String commenter(Object holder) {
        if (holder == null) return null;
        for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals("com.instagram.user.model.User")) continue;
                try {
                    f.setAccessible(true);
                    Object user = f.get(holder);
                    if (user != null) return UserUtils.callUsernameGetter(user);
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static String findLongestTextField(Object model) {
        String best = null;
        for (Field f : model.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                String val = (String) f.get(model);
                if (val == null || val.isEmpty()) continue;
                if (val.matches("\\d+")) continue;
                if (val.matches("\\d+_\\d+")) continue;
                if (val.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) continue;
                if (val.startsWith("http")) continue;
                if (best == null || val.length() > best.length()) best = val;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private static void copyToClipboard(final Context ctx, final String text) {
        try {
            CopyFeedback.copy(ctx, "comment", text, R.string.ig_toast_comment_copied);
        } catch (Throwable t) {
            ModuleLog.error("(InstaEclipse | CopyComment): Could not copy the comment: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }
}
