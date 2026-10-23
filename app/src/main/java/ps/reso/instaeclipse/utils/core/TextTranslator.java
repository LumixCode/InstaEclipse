package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hands a piece of text to the user's translation app (#233) — no network or API keys of our own.
 *
 * Tries, in order: the system translate intent (handled by Google Translate and others), a
 * PROCESS_TEXT chooser (DeepL, Translate, …), then a plain share sheet. Each step is a straight
 * startActivity in try/catch rather than a resolveActivity check, because Instagram's own
 * package-visibility (<queries>) would hide most handlers from a lookup.
 */
public final class TextTranslator {

    private TextTranslator() {}

    public static void translate(Context ctx, String text) {
        if (ctx == null || text == null || text.trim().isEmpty()) return;
        boolean newTask = !(ctx instanceof Activity);

        Intent translate = new Intent("android.intent.action.TRANSLATE")
                .putExtra(Intent.EXTRA_TEXT, text);
        if (start(ctx, translate, newTask)) return;

        String title = I18n.t(ctx, R.string.ig_translate_text);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        Intent process = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        Intent chooser = Intent.createChooser(process, title);
        if (Build.VERSION.SDK_INT >= 29) chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, new Intent[] {send});
        if (start(ctx, chooser, newTask)) return;

        start(ctx, Intent.createChooser(send, title), newTask);
    }

    private static boolean start(Context ctx, Intent intent, boolean newTask) {
        try {
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            ModuleLog.error("(IE|Translate) Could not open the translator: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }
}
