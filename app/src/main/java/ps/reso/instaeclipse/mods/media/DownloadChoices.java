package ps.reso.instaeclipse.mods.media;

import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.R;

public final class DownloadChoices {
    public enum Choice { PHOTO, VIDEO, FRAME, AUDIO, COVER }

    static final int FRAME_MIN_SDK = 30;

    private DownloadChoices() {}

    public static List<Choice> order(boolean hasImage, boolean video, boolean photoWithMusic,
                                     boolean offersAudio, int sdk) {
        List<Choice> choices = new ArrayList<>();
        if (video) {
            if (photoWithMusic && hasImage) choices.add(Choice.PHOTO);
            choices.add(Choice.VIDEO);
            if (sdk >= FRAME_MIN_SDK) choices.add(Choice.FRAME);
        } else if (hasImage) {
            choices.add(Choice.PHOTO);
        }
        if (offersAudio) choices.add(Choice.AUDIO);
        if (video && !photoWithMusic && hasImage) choices.add(Choice.COVER);
        return choices;
    }

    public static int label(Choice choice) {
        return switch (choice) {
            case PHOTO -> R.string.ig_story_download_photo;
            case VIDEO -> R.string.ie_download_video;
            case FRAME -> R.string.ie_save_video_frame;
            case AUDIO -> R.string.ig_download_choice_audio;
            case COVER -> R.string.ie_download_cover;
        };
    }
}
