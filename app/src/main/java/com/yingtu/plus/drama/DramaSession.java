package com.yingtu.plus.drama;

import com.yingtu.plus.drama.DramaModels.DramaVideo;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory handoff between DramaFragment and DramaPlayerActivity: the
 * current feed snapshot plus the tapped position. The player resolves every
 * stream itself at play time (URLs expire), so only ids/covers/titles cross
 * here, never playback URLs. If the process died and the snapshot is empty,
 * the player re-fetches the first feed page on its own.
 */
public final class DramaSession {

    private static final List<DramaVideo> FEED = new ArrayList<>();
    private static int position;
    private static String cellId = "";
    private static String sessionId = "";
    private static String nextOffset = "";
    private static boolean hasMore = true;

    private DramaSession() {
    }

    public static synchronized void setFeed(List<DramaVideo> feed, int startPosition) {
        FEED.clear();
        if (feed != null) FEED.addAll(feed);
        position = Math.max(0, Math.min(startPosition, Math.max(0, FEED.size() - 1)));
    }

    /** Feed cursor for the player to append more series while swiping. */
    public static synchronized void setCursor(String cell, String session, String offset,
                                              boolean more) {
        cellId = cell == null ? "" : cell;
        sessionId = session == null ? "" : session;
        nextOffset = offset == null ? "" : offset;
        hasMore = more;
    }

    public static synchronized String getCellId() {
        return cellId;
    }

    public static synchronized String getSessionId() {
        return sessionId;
    }

    public static synchronized String getNextOffset() {
        return nextOffset;
    }

    public static synchronized boolean hasMore() {
        return hasMore;
    }

    public static synchronized List<DramaVideo> getFeed() {
        return new ArrayList<>(FEED);
    }

    public static synchronized int getPosition() {
        return position;
    }

    public static synchronized void setPosition(int p) {
        position = p;
    }
}
