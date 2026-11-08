package com.yingtu.plus.drama;

import java.util.ArrayList;
import java.util.List;

/** Plain data models for the Fanqie short-drama client. No Android classes. */
public final class DramaModels {

    private DramaModels() {
    }

    /** One video in a feed page (plan/v + cell/change video_data item). */
    public static final class DramaVideo {
        public String vid = "";
        public String seriesId = "";
        public String title = "";
        public String cover = "";
        public String desc = "";
        public int episodeCount;
        public long duration;
        public long playCount;
        public double score;

        public String displayTitle() {
            return title == null ? "" : title;
        }

        public String episodeRemark() {
            return episodeCount > 0 ? episodeCount + "集" : "";
        }
    }

    /** One episode from video_detail (data.video_data.video_list). */
    public static final class DramaEpisode {
        public String vid = "";
        public String seriesId = "";
        public String title = "";
        public String cover = "";
        public int index;
        public long duration;
    }

    /** Series detail: header fields plus the full episode list. */
    public static final class DramaDetail {
        public String seriesId = "";
        public String title = "";
        public String intro = "";
        public String cover = "";
        public int episodeCount;
        public final List<DramaEpisode> episodes = new ArrayList<>();
    }

    /** One resolved playback stream (video_model video_list entry). */
    public static final class DramaStream {
        public String url = "";
        public String definition = "";
        public int width;
        public int height;
        public long bitrate;
        public long size;
        public double duration;
        public String videoId = "";
        public String fallbackApi = "";
    }

    /** One feed page: items plus the cursor for the next page. */
    public static final class FeedPage {
        public final List<DramaVideo> items = new ArrayList<>();
        public String cellId = "";
        public String sessionId = "";
        public String nextOffset = "";
        public boolean hasMore;
    }
}
