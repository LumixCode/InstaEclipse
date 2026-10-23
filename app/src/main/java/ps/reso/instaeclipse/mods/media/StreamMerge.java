package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;

final class StreamMerge {

    private StreamMerge() {}

    static void mergeVideoAudio(String vp, String ap, String op) throws Exception {
        MediaExtractor vEx = new MediaExtractor(), aEx = new MediaExtractor();
        MediaMuxer mux = new MediaMuxer(op, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            vEx.setDataSource(vp); aEx.setDataSource(ap);
            int vi = selectTrack(vEx, "video/"), ai = selectTrack(aEx, "audio/");
            if (vi < 0 || ai < 0) throw new Exception("Missing video or audio track");
            int vo = mux.addTrack(vEx.getTrackFormat(vi));
            int ao = mux.addTrack(aEx.getTrackFormat(ai));
            mux.start();
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            copyTrack(vEx, mux, vo, buf, info);
            copyTrack(aEx, mux, ao, buf, info);
            mux.stop();
        } finally { vEx.release(); aEx.release(); mux.release(); }
    }

    static int selectTrack(MediaExtractor ex, String mime) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(mime)) { ex.selectTrack(i); return i; }
        }
        return -1;
    }

    @SuppressLint("WrongConstant")
    private static void copyTrack(MediaExtractor ex, MediaMuxer mux, int outTrack,
                                  ByteBuffer buf, MediaCodec.BufferInfo info) {
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.offset = 0; info.size = sz;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(outTrack, buf, info);
            ex.advance();
        }
    }
}
