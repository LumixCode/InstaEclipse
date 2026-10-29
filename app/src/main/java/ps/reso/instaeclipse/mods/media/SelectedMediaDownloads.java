package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.app.Dialog;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.io.File;

import ps.reso.instaeclipse.utils.ipc.CompanionBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadMessages;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public final class SelectedMediaDownloads {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    static final class Captured {
        final SelectedMediaSnapshot media;
        final DownloadRequest video;
        final DownloadRequest originalVideo;
        final boolean original;
        final String type;
        final DownloadRequest.Options options;
        final String photoUrl;
        final String photoFilename;
        final String audioFilename;
        final boolean audioAllowed;
        final List<Captured> children;

        Captured(SelectedMediaSnapshot media, String type, DownloadRequest.Options options, boolean original) {
            this(media, type, options, original, FeatureFlags.offerAudioDownload);
        }

        private Captured(SelectedMediaSnapshot media, String type, DownloadRequest.Options options,
                         boolean original, boolean offerAudioDownload) {
            this.media = media;
            this.type = type;
            this.options = options;
            this.original = original;
            audioAllowed = "story".equals(type) || offerAudioDownload;
            String base = FeedVideoDownloadHook.buildFilename(DownloadFiles.segment(media.owner, 64), type,
                    DownloadFiles.segment(media.id, 112), false);
            base = base.substring(0, base.lastIndexOf('.'));
            audioFilename = base + "_audio.m4a";
            video = media.video ? media.request(filename(base, true), options) : null;
            originalVideo = media.video && media.originalVideoUrl != null
                    ? new DownloadRequest(media.id, media.owner, filename(base, true), null,
                    List.of(new DownloadRequest.Rendition(media.originalVideoUrl, 0, 0, 0, "", 0)),
                    new DownloadRequest.Options(0, false, false, true), DownloadRequest.AudioExpectation.UNKNOWN) : null;
            photoUrl = original ? media.originalImageUrl : media.imageUrl;
            photoFilename = filename(base, false);
            ArrayList<Captured> items = new ArrayList<>();
            for (SelectedMediaSnapshot child : media.children)
                items.add(new Captured(child, type, options, original, audioAllowed));
            children = Collections.unmodifiableList(items);
        }

        private Captured(Captured previous, boolean original) {
            media = previous.media;
            type = previous.type;
            options = previous.options;
            this.original = original;
            video = previous.video;
            originalVideo = previous.originalVideo;
            photoFilename = previous.photoFilename;
            audioFilename = previous.audioFilename;
            audioAllowed = previous.audioAllowed;
            photoUrl = original ? media.originalImageUrl : media.imageUrl;
            ArrayList<Captured> items = new ArrayList<>();
            for (Captured child : previous.children) items.add(child.withOriginal(original));
            children = Collections.unmodifiableList(items);
        }

        Captured withOriginal(boolean value) { return new Captured(this, value); }
        DownloadRequest selectedVideo() { return original ? originalVideo : video; }
        boolean offersAudio() { return audioAllowed && media.separateAudio != null; }
        DownloadChoices.Choice batchKind() {
            return video != null && !media.photoWithMusic ? DownloadChoices.Choice.VIDEO : DownloadChoices.Choice.PHOTO;
        }
    }

    static Captured capture(Context ctx, Object media, String type) throws Exception {
        boolean offerAudioDownload = FeatureFlags.offerAudioDownload;
        return new Captured(SelectedMediaAdapter.capture(ctx, media, type), type,
                new DownloadRequest.Options(FeatureFlags.downloaderQualityMaxShortSide,
                        FeatureFlags.downloaderCompatibility, FeatureFlags.downloaderAllowFallback),
                FeatureFlags.downloaderUseOriginal, offerAudioDownload);
    }

    static void captureAndShowPost(Context ctx, Object media, String type) {
        captureAndShowPost(ctx, media, type, null);
    }

    static void captureAndShowPost(Context ctx, Object media, String type,
            java.util.function.IntUnaryOperator currentSlide) {
        try {
            Captured captured = capture(ctx, media, type);
            int n = captured.children.size();
            showPost(ctx, captured, n > 1 && currentSlide != null ? currentSlide.applyAsInt(n) : -1);
        } catch (Throwable error) { unavailable(ctx, error); }
    }

    static void showPost(Context ctx, Captured captured) {
        showPost(ctx, captured, -1);
    }

    static void showPost(Context ctx, Captured captured, Runnable onClosed) {
        showPost(ctx, captured, -1, onClosed);
    }

    static void showPost(Context ctx, Captured captured, int current) {
        showPost(ctx, captured, current, null);
    }

    static void showPost(Context ctx, Captured captured, int current, Runnable onClosed) {
        Runnable closed = onClosed == null ? NOTHING : onClosed;
        if (captured.children.isEmpty()) { showItemChoices(ctx, captured, closed); return; }
        if (!canShow(ctx)) { closed.run(); return; }
        int n = captured.children.size();
        boolean known = current >= 0 && current < n;
        ArrayList<CharSequence> labels = new ArrayList<>();
        if (known) labels.add(I18n.t(ctx, R.string.ig_dl_carousel_current, current + 1, n));
        labels.add(I18n.t(ctx, R.string.ig_dl_carousel_all, n));
        labels.add(I18n.t(ctx, R.string.ie_download_select_item));
        int offset = known ? 1 : 0;
        AtomicBoolean handedOver = new AtomicBoolean();
        IeDialog dialog = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_dl_title));
        dialog.choices(labels.toArray(new CharSequence[0]), -1, which -> {
            if (known && which == 0) { handedOver.set(true); showItemChoices(ctx, captured.children.get(current), closed); }
            else if (which == offset) startAll(ctx, captured);
            else { handedOver.set(true); showItemList(ctx, captured, closed); }
        });
        dialog.onDismiss(() -> { if (!handedOver.get()) closed.run(); });
        dialog.show();
    }

    private static void showItemList(Context ctx, Captured captured, Runnable closed) {
        if (!canShow(ctx)) { closed.run(); return; }
        ArrayList<CharSequence> labels = new ArrayList<>();
        for (int i = 0; i < captured.children.size(); i++)
            labels.add(I18n.t(ctx, R.string.ie_download_item, i + 1));
        AtomicBoolean handedOver = new AtomicBoolean();
        IeDialog dialog = new IeDialog(ctx).title(I18n.t(ctx, R.string.ie_download_select_item));
        dialog.choices(labels.toArray(new CharSequence[0]), -1, which -> {
            handedOver.set(true);
            showItemChoices(ctx, captured.children.get(which), closed);
        });
        dialog.onDismiss(() -> { if (!handedOver.get()) closed.run(); });
        dialog.show();
    }

    static void startAll(Context ctx, Captured captured) {
        toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_all_n_items, captured.children.size()));
        BatchTally batch = new BatchTally(captured.children.size());
        for (Captured child : captured.children)
            start(ctx, child, child.batchKind() == DownloadChoices.Choice.VIDEO, batch);
    }

    private static void showItemChoices(Context ctx, Captured captured, Runnable closed) {
        List<DownloadChoices.Choice> choices = DownloadChoices.order(captured.media.imageUrl != null,
                captured.video != null, captured.media.photoWithMusic, captured.offersAudio(), Build.VERSION.SDK_INT);
        if (choices.size() == 1) { startChoice(ctx, captured, choices.get(0)); closed.run(); return; }
        if (choices.isEmpty()) { unavailable(ctx, null); closed.run(); return; }
        if (!canShow(ctx)) { closed.run(); return; }
        chooser(ctx, choices, choice -> startChoice(ctx, captured, choice)).onDismiss(closed).show();
    }

    public static IeDialog chooser(Context ctx, List<DownloadChoices.Choice> choices,
                                   Consumer<DownloadChoices.Choice> pick) {
        CharSequence[] labels = new CharSequence[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = I18n.t(ctx, DownloadChoices.label(choices.get(i)));
        IeDialog dialog = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_dl_title));
        dialog.choices(labels, -1, which -> pick.accept(choices.get(which)));
        return dialog;
    }

    static void startChoice(Context ctx, Captured captured, DownloadChoices.Choice choice) {
        switch (choice) {
            case PHOTO, COVER -> start(ctx, captured, false);
            case VIDEO -> start(ctx, captured, true);
            case FRAME -> startFrame(ctx, frameRequest(captured));
            case AUDIO -> {
                OriginalMediaSelection.Audio audio = captured.media.separateAudio;
                if (captured.offersAudio()) FeedVideoDownloadHook.downloadAudio(ctx, audio.url,
                        captured.audioFilename,
                        captured.media.owner, audio.extract, audio.startMs, audio.durationMs);
            }
        }
    }

    static DownloadRequest frameRequest(Captured captured) {
        DownloadRequest v = captured.video;
        if (v == null || v.options.originalSelection) return v;
        return new DownloadRequest(v.mediaId, v.owner, v.filename, v.manifest, v.direct,
                new DownloadRequest.Options(v.options.maxShortSide, true, true), v.audio);
    }

    static void startFrame(Context ctx, DownloadRequest request) {
        if (request == null) { unavailable(ctx, null); return; }
        try {
            CompanionBridge.saveFrame(ctx, request, error -> {
                if (error != null) companionFailed(ctx, error);
            });
        } catch (Throwable error) {
            failed(ctx, R.string.ie_download_reason_generic);
        }
    }

    static void start(Context ctx, Captured captured, boolean video) {
        start(ctx, captured, video, null);
    }

    static void start(Context ctx, Captured captured, boolean video, BatchTally batch) {
        start(ctx, captured, video, batch, false);
    }

    static boolean companionUnreachable(String error) {
        return CompanionBridge.isUnreachable(error);
    }

    static void savedLocally(Context ctx, String mime) {
        if (!DownloadPublisher.reachesChosenFolder(FeatureFlags.downloaderCustomPath, mime))
            toast(ctx, I18n.t(ctx, R.string.ig_dl_companion_fallback));
    }

    private static void start(Context ctx, Captured captured, boolean video, BatchTally batch, boolean local) {
        if (captured.original && (video ? captured.media.originalPlan == null : captured.photoUrl == null)) {
            toast(ctx, I18n.t(ctx, R.string.ie_downloader_original_unavailable));
            reportFailed(ctx, batch);
            return;
        }
        if (video && captured.original) { startOriginal(ctx, captured, batch); return; }
        if (video) { startVideo(ctx, captured.selectedVideo(), batch, local); return; }
        if (captured.photoUrl == null) { unavailable(ctx, null); reportFailed(ctx, batch); return; }
        try {
            if (!local && FeedVideoDownloadHook.hasCompanionDestination()) {
                CompanionBridge.downloadLegacy(ctx, captured.photoUrl, null, captured.photoFilename,
                        "image/jpeg", captured.media.owner, error -> {
                            if (companionUnreachable(error)) { savedLocally(ctx, "image/jpeg"); start(ctx, captured, false, batch, true); }
                            else if (error != null) { companionFailed(ctx, error); reportFailed(ctx, batch); }
                            else if (batch == null) toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_photo));
                            else reportDelegated(ctx, batch);
                        });
                return;
            }
        } catch (Throwable error) { failed(ctx, R.string.ie_download_reason_generic); reportFailed(ctx, batch); return; }
        DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();
        Context app = ctx.getApplicationContext();
        if (batch == null) toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_photo));
        WORKER.submit(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("ie_photo_", ".bin", app.getCacheDir());
                String mime = DownloadEngine.fetch(app, captured.photoUrl, temp, "image/jpeg", cancel, NO_PROGRESS);
                MediaTypeDetector.Result detected = MediaTypeDetector.verified(mime, captured.photoFilename);
                DownloadPublisher.save(app, temp, detected.filename, detected.mimeType, captured.media.owner, cancel);
                if (batch == null) toast(ctx, I18n.t(ctx, R.string.ig_toast_file_saved, detected.filename));
                else reportSaved(ctx, batch);
            } catch (Throwable error) {
                failed(ctx, cancel.isCancelled() ? R.string.ie_download_cancelled : DownloadMessages.reason(error));
                reportFailed(ctx, batch);
            } finally { if (temp != null) temp.delete(); }
        });
    }

    private static void startOriginal(Context ctx, Captured captured, BatchTally batch) {
        OriginalMediaSelection.Plan plan = captured.media.originalPlan;
        String filename = captured.originalVideo.filename;
        WORKER.submit(() -> {
            try {
                String base = plan.fallbackUrl == null ? plan.url : plan.fallbackUrl;
                String[] upgrade = plan.fallbackUrl == null ? null : new String[]{plan.url, plan.audioUrl};
                boolean delegated = FeedVideoDownloadHook.downloadCapturedOriginal(ctx, base,
                        filename, true, captured.media.owner, upgrade);
                if (delegated) reportDelegated(ctx, batch);
                else if (batch == null) toast(ctx, I18n.t(ctx, R.string.ig_toast_file_saved, filename));
                else reportSaved(ctx, batch);
            } catch (Throwable failure) { failed(ctx, DownloadMessages.reason(failure)); reportFailed(ctx, batch); }
        });
    }

    static void startVideo(Context ctx, DownloadRequest request, BatchTally batch, boolean local) {
        if (request == null) { unavailable(ctx, null); reportFailed(ctx, batch); return; }
        try {
            if (!local && FeedVideoDownloadHook.hasCompanionDestination()) {
                CompanionBridge.download(ctx, request, error -> {
                    if (companionUnreachable(error)) { savedLocally(ctx, "video/mp4"); startVideo(ctx, request, batch, true); }
                    else if (error != null) { companionFailed(ctx, error); reportFailed(ctx, batch); }
                    else if (batch == null) toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_video));
                    else reportDelegated(ctx, batch);
                });
                return;
            }
        } catch (Throwable error) { failed(ctx, R.string.ie_download_reason_generic); reportFailed(ctx, batch); return; }

        DownloadEngine.Cancellation cancel = new DownloadEngine.Cancellation();
        Context app = ctx.getApplicationContext();
        if (batch == null) toast(ctx, I18n.t(ctx, R.string.ig_toast_downloading_video));
        WORKER.submit(() -> {
            try (DownloadEngine.Result result = DownloadEngine.execute(app, request, cancel, NO_PROGRESS)) {
                DownloadPublisher.save(app, result.file, request.filename, result.mime, request.owner, cancel);
                if (batch == null || result.lowerFallback)
                    toast(ctx, I18n.t(ctx, DownloadMessages.saved(result.lowerFallback), request.filename,
                            DownloadMessages.quality(result.metadata)));
                reportSaved(ctx, batch);
                ModuleLog.line("(IE|Download) Saved " + request.filename);
                ModuleLog.detail("(IE|Download) published " + result.summary);
            } catch (DownloadEngine.Failure failure) {
                if (cancel.isCancelled()) { failed(ctx, R.string.ie_download_cancelled); reportFailed(ctx, batch); }
                else if (failure.canOfferFallback && !request.options.allowFallback && canShow(ctx)) {
                    if (offerFallbackAndWait(ctx, DownloadMessages.reason(failure), cancel))
                        startVideo(ctx, allowFallback(request, failure), batch, local);
                    else reportFailed(ctx, batch);
                } else { failed(ctx, DownloadMessages.reason(failure)); reportFailed(ctx, batch); }
            } catch (Throwable error) {
                failed(ctx, cancel.isCancelled() ? R.string.ie_download_cancelled : DownloadMessages.reason(error));
                reportFailed(ctx, batch);
            }
        });
    }

    private static void reportSaved(Context ctx, BatchTally batch) {
        if (batch != null && batch.saved()) batchDone(ctx, batch);
    }

    static void reportFailed(Context ctx, BatchTally batch) {
        if (batch != null && batch.failed()) batchDone(ctx, batch);
    }

    private static void reportDelegated(Context ctx, BatchTally batch) {
        if (batch != null && batch.delegated()) batchDone(ctx, batch);
    }

    static BatchTally batch(int total, int done, int failed) {
        BatchTally batch = new BatchTally(total);
        SUMMARIES.put(batch, new int[]{done, failed});
        return batch;
    }

    private static void batchDone(Context ctx, BatchTally batch) {
        int total = batch.total();
        int saved = batch.savedCount();
        int[] summary = SUMMARIES.remove(batch);
        try {
            if (summary != null) toast(ctx, saved == 0 ? I18n.t(ctx, summary[1]) : I18n.t(ctx, summary[0], saved, total));
            else toast(ctx, I18n.moduleResources(ctx).getQuantityString(R.plurals.ie_download_batch_done, total, saved, total));
        } catch (Throwable error) {
            ModuleLog.warn("(IE|Download) The batch summary could not be shown: " + error.getClass().getSimpleName());
        }
    }

    private static boolean offerFallbackAndWait(Context ctx, int reason,
            DownloadEngine.Cancellation cancellation) {
        CountDownLatch dismissed = new CountDownLatch(1);
        AtomicBoolean retry = new AtomicBoolean(false);
        AtomicReference<Dialog> prompt = new AtomicReference<>();
        FeedVideoDownloadHook.mainHandler.post(() -> {
            if (!canShow(ctx) || cancellation.isCancelled()) { dismissed.countDown(); return; }
            try {
                prompt.set(fallbackPrompt(ctx, reason, () -> { retry.set(true); dismissed.countDown(); },
                        dismissed::countDown).show());
            } catch (Throwable ignored) { dismissed.countDown(); }
        });
        try {
            while (!dismissed.await(250, TimeUnit.MILLISECONDS)) {
                if (!canShow(ctx) || cancellation.isCancelled()) return false;
            }
            return retry.get() && canShow(ctx) && !cancellation.isCancelled();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancellation.cancel();
            return false;
        } finally {
            FeedVideoDownloadHook.mainHandler.post(() -> {
                try { Dialog dialog = prompt.get(); if (dialog != null) dialog.dismiss(); }
                catch (Throwable ignored) { }
            });
        }
    }

    public static IeDialog fallbackPrompt(Context ctx, int reason, Runnable retry, Runnable dismissed) {
        IeDialog dialog = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_dl_title))
                .message(I18n.t(ctx, reason) + "\n\n" + I18n.t(ctx, R.string.ie_download_fallback_prompt));
        dialog.primary(I18n.t(ctx, R.string.ie_download_retry_lower), retry);
        dialog.secondary(I18n.t(ctx, R.string.ig_dialog_cancel), null);
        return dialog.onDismiss(dismissed);
    }

    private static final DownloadEngine.Progress NO_PROGRESS = (phase, done, total) -> { };
    private static final Runnable NOTHING = () -> { };
    private static final Map<BatchTally, int[]> SUMMARIES = Collections.synchronizedMap(new WeakHashMap<>());

    static DownloadRequest allowFallback(DownloadRequest original, DownloadEngine.Failure failure) {
        return DownloadEngine.fallback(original, failure);
    }

    static void unavailable(Context ctx, Throwable error) {
        ModuleLog.error("(IE|MediaAdapter) Could not download: this media's details could not be read"
                + (error == null ? "" : " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")"));
        toast(ctx, I18n.t(ctx, R.string.ie_download_unavailable));
    }

    private static String filename(String base, boolean video) {
        return base + (video ? ".mp4" : ".jpg");
    }

    private static boolean canShow(Context context) {
        Context c = context;
        for (int i = 0; i < 8; i++) {
            if (c instanceof Activity a) return !a.isFinishing() && !a.isDestroyed();
            if (!(c instanceof ContextWrapper wrapper) || wrapper.getBaseContext() == c) break;
            c = wrapper.getBaseContext();
        }
        return false;
    }

    private static void failed(Context ctx, int reason) {
        toast(ctx, DownloadMessages.failure(ctx, reason));
    }
    private static void companionFailed(Context ctx, String error) {
        toast(ctx, DownloadMessages.companionFailure(ctx, error));
    }
    private static void toast(Context ctx, String message) {
        FeedVideoDownloadHook.mainHandler.post(() -> ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx.getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
