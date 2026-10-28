package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.log.Logging;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadMessages;
import ps.reso.instaeclipse.utils.download.DownloadRequest;
import ps.reso.instaeclipse.utils.download.TempFiles;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import androidx.documentfile.provider.DocumentFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that runs in the companion-app process and saves downloaded media
 * to a SAF (Storage Access Framework) folder.
 *
 * <p>The companion app holds the persistable SAF permission for folders picked via
 * FeaturesFragment.  The Xposed module (running in Instagram's process) cannot use
 * that permission directly, so it forwards the CDN URL(s) to this service as plain
 * string extras.  The service downloads the media itself — no file-descriptor passing
 * across different UIDs is required.
 *
 * <p>For video+audio-separate streams the service also handles the merge step locally.
 * Download progress is reported via a live-updating foreground notification.
 */
public class DownloadSaveService extends Service {

    static final String EXTRA_REQUEST = "selectedDownloadRequest";
    private static final String ACTION_CANCEL = BuildConfig.APPLICATION_ID + ".CANCEL_DOWNLOAD";
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final Map<Integer, DownloadEngine.Cancellation> cancellations = new ConcurrentHashMap<>();
    private final Map<Integer, String> jobTokens = new ConcurrentHashMap<>();
    private volatile int activeJobId;
    private int latestStartId;

    static final String CHANNEL_ID  = "ie_dl_save";
    static final String DONE_GROUP = "ie_dl_done";
    static final int    NOTIF_ID    = 0x49455344; // "IESD" — ongoing progress
    private static final int    DONE_NOTIF_BASE = 0x49455345; // per-startId completion notifs
    static final int DONE_SUMMARY_ID = 0x49455346;

    /** Throttle: minimum ms between notification updates. */
    private static final long NOTIF_INTERVAL_MS = 250;

    private NotificationManager nm;
    private long lastNotifMs  = 0;
    private int  lastNotifPct = -1;
    private volatile String progressText;
    private volatile int progressValue, progressMax;
    private volatile boolean progressIndeterminate;
    private final SaveSummary summary = new SaveSummary();
    private final SaveSummary.Texts summaryTexts = new SaveSummary.Texts() {
        @Override public String saved(int count) {
            return getResources().getQuantityString(R.plurals.ie_download_files_saved, count, count);
        }

        @Override public String savedAndFailed(int saved, int failed) {
            return getString(R.string.ie_download_saved_and_failed, saved(saved),
                    getResources().getQuantityString(R.plurals.ie_download_files_failed, failed, failed));
        }

        @Override public String cancelled() {
            return getString(R.string.ie_download_cancelled);
        }
    };

    private static final class SavedMedia {
        final Uri uri;
        final String filename;
        final String mimeType;

        SavedMedia(Uri uri, String filename, String mimeType) {
            this.uri = uri;
            this.filename = filename;
            this.mimeType = mimeType;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        ensureChannel();
        jobs.submit(() -> TempFiles.sweepOnce(getCacheDir()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            int jobId = intent.getIntExtra("jobId", -1);
            DownloadEngine.Cancellation token = cancellations.get(jobId);
            String expectedToken = jobTokens.get(jobId);
            if (token != null && expectedToken != null
                    && expectedToken.equals(intent.getStringExtra("jobToken"))) token.cancel();
            if (cancellations.isEmpty()) stopSelfResult(latestStartId);
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, activeJobId != 0 && progressText != null
                ? buildProgressNotification(progressText, progressValue, progressMax, progressIndeterminate)
                : buildProgressNotification(getString(R.string.ie_download_starting), 0, 0, true));

        if (intent == null) { if (cancellations.isEmpty()) stopSelfResult(latestStartId); return START_NOT_STICKY; }

        if (intent.getStringExtra("chatExportSubdir") != null) {
            handleChatExport(intent, startId);
            return START_NOT_STICKY;
        }
        DownloadRequest request = null;
        try {
            if (intent.hasExtra(EXTRA_REQUEST)) request = DownloadRequest.fromJson(intent.getStringExtra(EXTRA_REQUEST));
        } catch (RuntimeException invalid) {
            showToast(getString(R.string.ig_toast_download_failed, getString(R.string.ie_download_reason_invalid)));
            if (cancellations.isEmpty()) stopSelfResult(latestStartId);
            return START_NOT_STICKY;
        }

        String url       = intent.getStringExtra("url");
        String audioUrl  = intent.getStringExtra("audioUrl"); // null → single-stream
        String filename  = intent.getStringExtra("filename");
        String mimeType  = intent.getStringExtra("mimeType");
        String username  = intent.getStringExtra("username");
        final String fallbackUrl = intent.getStringExtra("fallbackUrl");
        final boolean extractAudio = intent.getBooleanExtra("extractAudio", false);
        final long trimStartMs = intent.getLongExtra("trimStartMs", 0);
        final long trimDurationMs = intent.getLongExtra("trimDurationMs", -1);

        if (request == null && (url == null || filename == null)) {
            if (cancellations.isEmpty()) stopSelfResult(latestStartId);
            return START_NOT_STICKY;
        }

        SharedPreferences cache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(this);
        String saveUri          = cache.getString("downloaderCustomUri", "");
        boolean usernameFolder  = cache.getBoolean("downloaderUsernameFolder", false);

        if (saveUri.isEmpty()) {
            showToast(getString(R.string.ig_toast_no_download_folder));
            if (cancellations.isEmpty()) stopSelfResult(latestStartId);
            return START_NOT_STICKY;
        }

        final int    sid    = startId;
        final String fUrl   = url, fAudio = audioUrl, fFile = filename;
        final String fMime  = mimeType, fUser = username, fSave = saveUri;
        final boolean fUF   = usernameFolder;
        final DownloadRequest captured = request;
        final DownloadEngine.Cancellation cancellation = new DownloadEngine.Cancellation();
        final String jobToken = UUID.randomUUID().toString();
        final int notificationId = jobToken.hashCode();
        cancellations.put(sid, cancellation);
        jobTokens.put(sid, jobToken);
        refreshWaiting();

        jobs.submit(() -> {
            activeJobId = sid;
            try {
                DownloadFiles.check(cancellation);
                if (captured != null) {
                    try (DownloadEngine.Result result = DownloadEngine.execute(this, captured, cancellation,
                            (phase, done, total) -> maybeUpdateProgress(phaseText(phase),
                                    total > 0 ? (int) Math.min(100, done * 100 / total) : 0, 100, total <= 0))) {
                        pushProgress(getString(R.string.ie_download_saving), 99, 100, false);
                        Uri saved = writeViaSaf(result.file, captured.filename, result.mime,
                                fSave, captured.owner, fUF, cancellation);
                        String verified = getString(DownloadMessages.saved(result.lowerFallback), captured.filename,
                                DownloadMessages.quality(result.metadata));
                        postDoneNotification(notificationId, verified, result.mime, saved);
                        saved(verified);
                        ModuleLog.line("(IE|Download) Saved " + captured.filename
                                + (result.lowerFallback ? " (lower quality, as you allowed)" : ""));
                        ModuleLog.detail("(IE|Download) SAF published " + result.summary);
                    }
                    return;
                }
                SavedMedia saved;
                if (extractAudio) {
                    saved = downloadExtractAudioAndSave(fUrl, fFile, fSave, fUser, fUF, trimStartMs, trimDurationMs);
                } else if (fallbackUrl != null) {
                    try {
                        saved = fAudio != null ? downloadMergeAndSave(fUrl, fAudio, fFile, fMime, fSave, fUser, fUF)
                                : downloadAndSave(fUrl, fFile, fMime, fSave, fUser, fUF);
                    } catch (Exception failedUpgrade) {
                        DownloadFiles.check(cancellation);
                        ModuleLog.warn("(IE|DL) Could not download the original quality; saving the version Instagram was playing instead");
                        ModuleLog.detail("(IE|DL) Original DASH failed: " + failedUpgrade.getClass().getSimpleName()
                                + ": " + failedUpgrade.getMessage());
                        saved = downloadAndSave(fallbackUrl, fFile, fMime, fSave, fUser, fUF);
                    }
                } else {
                    saved = fAudio != null ? downloadMergeAndSave(fUrl, fAudio, fFile, fMime, fSave, fUser, fUF)
                            : downloadAndSave(fUrl, fFile, fMime, fSave, fUser, fUF);
                }
                String text = getString(R.string.ig_toast_file_saved, saved.filename);
                postDoneNotification(notificationId, text, saved.mimeType, saved.uri);
                saved(text);
                ModuleLog.line("(IE|Download) Saved " + saved.filename);
                ModuleLog.detail("(IE|Download) SAF published type=" + saved.mimeType);
            } catch (Throwable e) {
                int reasonId = cancellation.isCancelled() ? R.string.ie_download_cancelled : DownloadMessages.reason(e);
                boolean cancelled = reasonId == R.string.ie_download_cancelled;
                String reason = getString(reasonId);
                String logReason = e instanceof DownloadEngine.Failure failure ? failure.getMessage() : "Could not save download";
                DownloadRequest retry = captured != null && !captured.options.allowFallback
                        && e instanceof DownloadEngine.Failure failure && failure.canOfferFallback
                        && !cancelled ? SelectedMediaDownloads.allowFallback(captured, failure) : null;
                postFailureNotification(notificationId, reason, retry);
                if (cancelled) cancelled();
                else failed(getString(R.string.ig_toast_download_failed, reason));
                String diagnostic = e instanceof DownloadEngine.Failure failure
                        && !failure.diagnostic.isEmpty() ? " " + failure.diagnostic : "";
                if (cancelled) ModuleLog.line("(IE|Download) Download cancelled");
                else ModuleLog.error("(IE|Download) Could not save the download: " + logReason
                        + (retry != null ? " (a lower-quality version can be saved instead)" : ""));
                ModuleLog.detail("(IE|Download) " + (cancelled ? "cancelled" : "failed")
                        + " fallbackAvailable=" + (retry != null) + diagnostic);
            } finally {
                jobDone(sid);
            }
        });

        return START_NOT_STICKY;
    }

    private void jobDone(int sid) {
        Logging.flush();
        cancellations.remove(sid);
        jobTokens.remove(sid);
        activeJobId = 0;
        progressText = null;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!cancellations.isEmpty()) return;
            String text = summary.drain(summaryTexts);
            if (text != null) showToast(text);
            stopSelfResult(latestStartId);
        });
    }

    private void saved(String text) {
        summary.saved(text);
        postGroupSummary();
    }

    private void failed(String text) {
        summary.failed(text);
        postGroupSummary();
    }

    private void cancelled() {
        summary.cancelled();
        postGroupSummary();
    }

    private String phaseText(String phase) {
        return getString(switch (String.valueOf(phase)) {
            case DownloadEngine.PHASE_DOWNLOAD_AUDIO -> R.string.ig_toast_downloading_audio;
            case DownloadEngine.PHASE_VERIFY -> R.string.ie_download_verifying;
            case DownloadEngine.PHASE_JOIN -> R.string.ig_toast_merging_video_audio;
            default -> R.string.ig_toast_downloading;
        });
    }

    // ── Download helpers ──────────────────────────────────────────────────────

    private SavedMedia downloadAndSave(String url, String filename, String mimeType,
                                       String saveUri, String username, boolean usernameFolder)
            throws Exception {
        File tmp = File.createTempFile("ie_dl_", ".bin", getCacheDir());
        try {
            String downloading = getString(R.string.ig_toast_downloading);
            pushProgress(downloading, 0, 100, false);
            String responseType = downloadToFile(url, tmp, mimeType, (done, total) -> {
                if (total > 0) {
                    int pct = (int) (done * 95 / total);
                    maybeUpdateProgress(downloading, pct, 100);
                } else {
                    maybeUpdateProgress(downloading, 0, 0, true);
                }
            });
            MediaTypeDetector.Result detected = MediaTypeDetector.verified(responseType, filename);
            ModuleLog.detail("(IE|DL|Type) detected=" + detected.kind);
            pushProgress(getString(R.string.ie_download_saving), 97, 100, false);
            Uri uri = writeViaSaf(tmp, detected.filename, detected.mimeType,
                    saveUri, username, usernameFolder);
            return new SavedMedia(uri, detected.filename, detected.mimeType);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private SavedMedia downloadMergeAndSave(String videoUrl, String audioUrl, String filename,
                                            String mimeType, String saveUri,
                                            String username, boolean usernameFolder)
            throws Exception {
        File cacheDir = getCacheDir();
        long ts = System.currentTimeMillis();
        File tv  = new File(cacheDir, "ie_sv_" + ts + ".mp4");
        File ta  = new File(cacheDir, "ie_sa_" + ts + ".mp4");
        File out = new File(cacheDir, "ie_sm_" + ts + ".mp4");
        try {
            // Video download: 0–60 %
            String video = getString(R.string.ig_toast_downloading_video);
            pushProgress(video, 0, 100, false);
            downloadToFile(videoUrl, tv, "video/mp4", (done, total) -> {
                if (total > 0) {
                    int pct = (int) (done * 60 / total);
                    maybeUpdateProgress(video, pct, 100);
                }
            });

            // Audio download: 60–80 %
            String audio = getString(R.string.ig_toast_downloading_audio);
            pushProgress(audio, 60, 100, false);
            downloadToFile(audioUrl, ta, "audio/mp4", (done, total) -> {
                if (total > 0) {
                    int pct = 60 + (int) (done * 20 / total);
                    maybeUpdateProgress(audio, pct, 100);
                }
            });

            // Merge: 80–95 % (indeterminate — MediaMuxer gives no callbacks)
            pushProgress(getString(R.string.ig_toast_merging_video_audio), 80, 100, true);
            StreamMerge.mergeVideoAudio(tv.getAbsolutePath(), ta.getAbsolutePath(), out.getAbsolutePath());

            pushProgress(getString(R.string.ie_download_saving), 97, 100, false);
            String corrected = MediaTypeDetector.withCorrectExtension(
                    filename, MediaTypeDetector.Kind.VIDEO);
            Uri uri = writeViaSaf(out, corrected, "video/mp4",
                    saveUri, username, usernameFolder);
            return new SavedMedia(uri, corrected, "video/mp4");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tv.delete();
            //noinspection ResultOfMethodCallIgnored
            ta.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    /** Downloads a video and saves only its audio track as .m4a (AAC copied as-is, no re-encode). */
    private SavedMedia downloadExtractAudioAndSave(String videoUrl, String filename, String saveUri,
                                                   String username, boolean usernameFolder,
                                                   long trimStartMs, long trimDurationMs)
            throws Exception {
        File cacheDir = getCacheDir();
        long ts = System.currentTimeMillis();
        File tv  = new File(cacheDir, "ie_xv_" + ts + ".mp4");
        File out = new File(cacheDir, "ie_xa_" + ts + ".m4a");
        try {
            String audio = getString(R.string.ig_toast_downloading_audio);
            pushProgress(audio, 0, 100, false);
            DownloadEngine.fetchAudioSource(this, videoUrl, tv, cancellations.get(activeJobId), (phase, done, total) -> {
                if (total > 0) maybeUpdateProgress(audio, (int) (done * 85 / total), 100);
            });
            pushProgress(getString(R.string.ie_download_extracting_audio), 90, 100, true);
            extractAudioTrack(tv.getAbsolutePath(), out.getAbsolutePath(),
                    trimStartMs * 1000, trimDurationMs > 0 ? trimDurationMs * 1000 : -1);
            String name = filename.replaceAll("\\.(mp4|m4a)$", "") + ".m4a";
            Uri uri = writeViaSaf(out, name, "audio/mp4", saveUri, username, usernameFolder);
            return new SavedMedia(uri, name, "audio/mp4");
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tv.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    /**
     * Same, keeping only {@code durationUs} of audio from {@code startUs} (re-timed to start at 0);
     * a duration of -1 keeps the rest of the track. Samples are copied, never re-encoded.
     */
    static void extractAudioTrack(String videoPath, String outPath, long startUs, long durationUs)
            throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(videoPath);
            int ai = StreamMerge.selectTrack(ex, "audio/");
            if (ai < 0) throw new Exception("This video has no audio track");
            mux = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int ao = mux.addTrack(ex.getTrackFormat(ai));
            mux.start();
            copyTrackRange(ex, mux, ao, startUs, durationUs);
            mux.stop();
        } finally {
            ex.release();
            if (mux != null) mux.release();
        }
    }

    @SuppressLint("WrongConstant")
    private static void copyTrackRange(MediaExtractor ex, MediaMuxer mux, int outTrack,
                                       long startUs, long durationUs) {
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long end = durationUs > 0 ? startUs + durationUs : Long.MAX_VALUE;
        ex.seekTo(Math.max(0, startUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            long t = ex.getSampleTime();
            if (t > end) break;
            if (t >= startUs) {
                info.offset = 0;
                info.size = sz;
                info.presentationTimeUs = t - startUs;
                info.flags = ex.getSampleFlags();
                mux.writeSampleData(outTrack, buf, info);
            }
            ex.advance();
        }
    }

    // ── SAF write ────────────────────────────────────────────────────────────

    private Uri writeViaSaf(File src, String filename, String mimeType,
                             String saveUri, String username, boolean usernameFolder)
            throws Exception {
        return writeViaSaf(src, filename, mimeType, saveUri, username, usernameFolder,
                cancellations.get(activeJobId));
    }

    private Uri writeViaSaf(File src, String filename, String mimeType,
            String saveUri, String username, boolean usernameFolder,
            DownloadEngine.Cancellation cancellation) throws Exception {
        return DownloadPublisher.saveToSaf(this, src, filename, mimeType, saveUri,
                username, usernameFolder, cancellation);
    }

    // ── Chat export (HTML + media batch into a nested subfolder) ───────────────

    private void handleChatExport(Intent intent, int startId) {
        final String subdir       = DownloadRequestValidator.sanitizeSubdir(intent.getStringExtra("chatExportSubdir"));
        final String htmlFilename = DownloadRequestValidator.sanitizeFileName(intent.getStringExtra("htmlFilename"), null);
        final String htmlB64      = intent.getStringExtra("htmlB64");
        final boolean csv = "text/csv".equals(intent.getStringExtra("exportMime"));
        final String mainMime = csv ? "text/csv" : "text/html";
        final String[] urls  = intent.getStringArrayExtra("mediaUrls");
        final String[] names = intent.getStringArrayExtra("mediaNames");
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                names[i] = DownloadRequestValidator.sanitizeFileName(names[i], "media_" + (i + 1));
            }
        }
        final String[] mimes = intent.getStringArrayExtra("mediaMimes");
        final int messageCount = intent.getIntExtra("messageCount", 0);

        SharedPreferences cache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(this);
        String saveUri = cache.getString("downloaderCustomUri", "");
        if (saveUri.isEmpty()) saveUri = intent.getStringExtra("saveUri");
        final String fSave = saveUri;
        if (fSave == null || fSave.isEmpty()) {
            showToast(getString(R.string.ig_toast_no_download_folder));
            if (cancellations.isEmpty()) stopSelfResult(latestStartId);
            return;
        }

        final DownloadEngine.Cancellation cancellation = new DownloadEngine.Cancellation();
        cancellations.put(startId, cancellation);
        String jobToken = UUID.randomUUID().toString();
        jobTokens.put(startId, jobToken);
        refreshWaiting();
        final int notificationId = jobToken.hashCode();
        jobs.submit(() -> {
            activeJobId = startId;
            int saved = 0;
            int total = urls != null ? urls.length : 0;
            List<File> kept = new ArrayList<>();
            try {
                ExportDir dir = resolveNestedDir(fSave, subdir);
                String saving = getString(R.string.ie_export_saving);
                pushProgress(saving, 0, 100, true);
                HtmlInliner html = !csv && htmlB64 != null && htmlFilename != null
                        ? new HtmlInliner(new String(android.util.Base64.decode(htmlB64, android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8))
                        : null;
                for (int i = 0; i < total; i++) {
                    DownloadFiles.check(cancellation);
                    maybeUpdateProgress(getString(R.string.ie_export_saving_media, i + 1, total),
                            (int) ((i + 1) * 100L / Math.max(1, total)), 100);
                    File tmp = File.createTempFile("ie_cm_", ".bin", getCacheDir());
                    boolean keep = false;
                    try {
                        downloadToFile(urls[i], tmp, mimes[i], null);
                        long len = tmp.length();
                        if (len > 0) {
                            writeFileToDir(dir, tmp, names[i], mimes[i]);
                            saved++;
                            keep = html != null && html.offer(names[i], mimes[i], len, () -> new FileInputStream(tmp));
                            if (keep) kept.add(tmp);
                        }
                    } catch (Throwable ignored) {
                    } finally { //noinspection ResultOfMethodCallIgnored
                        if (!keep) tmp.delete(); }
                }
                Uri htmlUri = null;
                if (csv) {
                    if (htmlB64 == null || htmlFilename == null) throw new IllegalStateException("the export has no file");
                    byte[] body = android.util.Base64.decode(htmlB64, android.util.Base64.DEFAULT);
                    pushProgress(saving, 99, 100, true);
                    htmlUri = writeToDir(dir, htmlFilename, mainMime, os -> os.write(body));
                } else if (html != null) {
                    pushProgress(saving, 99, 100, true);
                    htmlUri = writeToDir(dir, htmlFilename, "text/html", html::write);
                }
                final int fSaved = saved;
                DownloadFiles.check(cancellation);
                if (csv) ModuleLog.line("(IE|Export|Service) Export saved: " + subdir + "/" + htmlFilename);
                else if (fSaved < total) ModuleLog.warn("(IE|Export|Service) Chat export saved, but " + (total - fSaved)
                        + " of " + total + " media files could not be downloaded");
                else ModuleLog.line("(IE|Export|Service) Chat export saved with all " + total + " media files");
                String text = csv ? getString(R.string.ig_toast_file_saved, htmlFilename)
                        : getString(R.string.ig_export_media_saved, fSaved, subdir);
                postDoneNotification(notificationId, text, mainMime, htmlUri, true);
                saved(text);
            } catch (Throwable e) {
                if (cancellation.isCancelled()) {
                    ModuleLog.line("(IE|Export|Service) Chat export cancelled");
                    postDoneNotification(notificationId, getString(R.string.ie_download_cancelled), null, null, true);
                    cancelled();
                } else {
                    ModuleLog.error("(IE|ChatExport|Service) Could not save the chat export: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                    String text = getString(R.string.ie_export_failed);
                    postDoneNotification(notificationId, text, null, null);
                    failed(text);
                }
            } finally {
                kept.forEach(File::delete);
                jobDone(startId);
            }
        });
    }

    private static final class ExportDir {
        final DocumentFile dir;
        final boolean fresh;

        ExportDir(DocumentFile dir, boolean fresh) {
            this.dir = dir;
            this.fresh = fresh;
        }
    }

    private interface Body {
        void write(OutputStream out) throws Exception;
    }

    /** Resolves (creating each segment) a nested subfolder like "chats/chat_x" under the SAF tree. */
    private ExportDir resolveNestedDir(String saveUri, String subPath) throws Exception {
        DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(saveUri));
        if (dir == null || !dir.canWrite()) {
            throw new Exception("SAF folder not writable — was the permission revoked?");
        }
        boolean fresh = false;
        if (subPath != null) {
            for (String seg : subPath.split("/")) {
                if (seg.isEmpty()) continue;
                DocumentFile sub = fresh ? null : dir.findFile(seg);
                if (sub == null || !sub.isDirectory()) {
                    sub = dir.createDirectory(seg);
                    fresh = true;
                }
                if (sub == null) throw new Exception("Cannot create folder: " + seg);
                dir = sub;
            }
        }
        return new ExportDir(dir, fresh);
    }

    /** Writes src into an already-resolved SAF directory, replacing any existing file of that name. */
    private Uri writeFileToDir(ExportDir dir, File src, String filename, String mimeType)
            throws Exception {
        return writeToDir(dir, filename, mimeType, os -> DownloadFiles.copy(src, os, cancellations.get(activeJobId)));
    }

    private Uri writeToDir(ExportDir dir, String filename, String mimeType, Body body) throws Exception {
        if (!dir.fresh) {
            DocumentFile existing = dir.dir.findFile(filename);
            if (existing != null) //noinspection ResultOfMethodCallIgnored
                existing.delete();
        }
        DocumentFile file = dir.dir.createFile(mimeType != null ? mimeType : "application/octet-stream", filename);
        if (file == null) throw new Exception("SAF createFile returned null: " + filename);
        boolean complete = false;
        try {
            try (OutputStream os = getContentResolver().openOutputStream(file.getUri())) {
                if (os == null) throw new Exception("SAF openOutputStream returned null");
                body.write(os);
            }
            DownloadFiles.check(cancellations.get(activeJobId));
            complete = true;
            return file.getUri();
        } finally {
            if (!complete) file.delete();
        }
    }

    // ── Network / merge utilities ─────────────────────────────────────────────

    @FunctionalInterface
    interface ProgressCallback {
        /** @param bytesRead bytes downloaded so far; @param totalBytes -1 if unknown */
        void onProgress(long bytesRead, long totalBytes);
    }

    private String downloadToFile(String url, File dest, String mime, ProgressCallback cb)
            throws Exception {
        return DownloadEngine.fetch(this, url, dest, mime, cancellations.get(activeJobId),
                (phase, done, total) -> { if (cb != null) cb.onProgress(done, total); });
    }
    // ── Notification helpers ──────────────────────────────────────────────────

    /**
     * Unconditionally updates the foreground notification (use for phase transitions).
     */
    private void pushProgress(String text, int progress, int max, boolean indeterminate) {
        lastNotifPct = progress;
        lastNotifMs  = System.currentTimeMillis();
        showProgress(text, progress, max, indeterminate);
    }

    private void showProgress(String text, int progress, int max, boolean indeterminate) {
        progressText = text;
        progressValue = progress;
        progressMax = max;
        progressIndeterminate = indeterminate;
        nm.notify(NOTIF_ID, buildProgressNotification(text, progress, max, indeterminate));
    }

    private void refreshWaiting() {
        String text = progressText;
        if (activeJobId != 0 && text != null)
            nm.notify(NOTIF_ID, buildProgressNotification(text, progressValue, progressMax, progressIndeterminate));
    }

    /**
     * Throttled update — called on every chunk read. Skips the notify call if
     * less than {@link #NOTIF_INTERVAL_MS} have passed AND progress changed < 2 %.
     */
    private void maybeUpdateProgress(String text, int pct, int max) {
        maybeUpdateProgress(text, pct, max, false);
    }

    private void maybeUpdateProgress(String text, int pct, int max, boolean indeterminate) {
        long now = System.currentTimeMillis();
        if (Math.abs(pct - lastNotifPct) < 2 && now - lastNotifMs < NOTIF_INTERVAL_MS) return;
        lastNotifPct = pct;
        lastNotifMs  = now;
        showProgress(text, pct, max, indeterminate);
    }

    private Notification buildProgressNotification(String text, int progress, int max,
                                                    boolean indeterminate) {
        int jobId = activeJobId;
        PendingIntent action = null;
        if (jobId != 0) {
            String token = jobTokens.get(jobId);
            Intent cancel = new Intent(this, DownloadSaveService.class).setAction(ACTION_CANCEL)
                    .setData(Uri.parse("instaeclipse-download://cancel/" + token))
                    .putExtra("jobId", jobId).putExtra("jobToken", token);
            action = PendingIntent.getService(this, jobId, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        int waiting = Math.max(0, cancellations.size() - (jobId != 0 ? 1 : 0));
        return progressNotification(this, text, progress, max, indeterminate, waiting, action);
    }

    static Notification progressNotification(Context ctx, String text, int progress, int max,
            boolean indeterminate, int waiting, PendingIntent cancel) {
        String shown = waiting > 0
                ? ctx.getResources().getQuantityString(R.plurals.ie_download_waiting, waiting, text, waiting) : text;
        Notification.Builder builder = new Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.module_display_name))
                .setContentText(shown)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(max, progress, indeterminate)
                .setOngoing(true);
        if (cancel != null)
            builder.addAction(new Notification.Action.Builder(null, ctx.getString(android.R.string.cancel), cancel).build());
        return builder.build();
    }

    static Notification doneNotification(Context ctx, String text, boolean success, PendingIntent open) {
        Notification.Builder builder = new Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.module_display_name))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(success ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setGroup(DONE_GROUP)
                .setAutoCancel(true);
        if (open != null) builder.setContentIntent(open);
        return builder.build();
    }

    static Notification groupSummary(Context ctx, String text) {
        return new Notification.Builder(ctx, CHANNEL_ID)
                .setContentTitle(ctx.getString(R.string.module_display_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setGroup(DONE_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build();
    }

    private void postGroupSummary() {
        String text = summary.text(summaryTexts);
        if (text != null) nm.notify(DONE_SUMMARY_ID, groupSummary(this, text));
    }

    private void postFailureNotification(int startId, String reason, DownloadRequest retry) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
        builder.setContentTitle(getString(R.string.module_display_name)).setContentText(reason)
                .setSmallIcon(android.R.drawable.stat_notify_error).setAutoCancel(true)
                .setGroup(DONE_GROUP)
                .setStyle(new Notification.BigTextStyle().bigText(reason + (retry == null ? ""
                        : "\n" + getString(R.string.ie_download_fallback_prompt))));
        if (retry != null) {
            Intent intent = new Intent(this, DownloadSaveService.class)
                    .setData(Uri.parse("instaeclipse-download://retry/" + UUID.randomUUID()))
                    .putExtra(EXTRA_REQUEST, retry.toJson());
            PendingIntent action = PendingIntent.getForegroundService(this, DONE_NOTIF_BASE + startId,
                    intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(R.string.ie_download_retry_lower), action).build());
        }
        nm.notify(startId, builder.build());
    }

    /**
     * Posts a non-ongoing completion/error notification that persists after the service stops.
     * Uses {@code DONE_NOTIF_BASE + startId} so concurrent downloads don't collide.
     */
    /**
     * Posts a persistent completion notification.
     * If {@code fileUri} is non-null, tapping the notification opens the saved file
     * in the device's default viewer (gallery, video player, etc.).
     */
    private void postDoneNotification(int startId, String text, String mimeType, Uri fileUri) {
        postDoneNotification(startId, text, mimeType, fileUri, fileUri != null);
    }

    private void postDoneNotification(int startId, String text, String mimeType, Uri fileUri, boolean success) {
        // Build a tap-to-open PendingIntent when we have a valid file URI
        android.app.PendingIntent contentIntent = null;
        if (fileUri != null && mimeType != null) {
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, mimeType);
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_ONE_SHOT;
            contentIntent = android.app.PendingIntent.getActivity(
                    this, DONE_NOTIF_BASE + startId, viewIntent, piFlags);
        }

        nm.notify(startId, doneNotification(this, text, success, contentIntent));
    }

    private void showToast(String msg) {
        // Use application context — service context is invalid after stopSelf() fires
        Context appCtx = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() ->
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show());
    }

    private void ensureChannel() {
        ensureChannel(this, nm);
    }

    static void ensureChannel(Context ctx, NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.module_download_channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    @Override public void onDestroy() {
        for (DownloadEngine.Cancellation cancellation : cancellations.values()) cancellation.cancel();
        jobs.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
