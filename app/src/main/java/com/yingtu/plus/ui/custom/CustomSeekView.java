package com.yingtu.plus.ui.custom;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.ui.DefaultTimeBar;
import androidx.media3.ui.TimeBar;

import java.util.Formatter;
import java.util.Locale;

// 对照 webhtv 重写
public class CustomSeekView extends FrameLayout implements Player.Listener, TimeBar.OnScrubListener {

    private static final int MAX_UPDATE_INTERVAL_MS = 1000;
    private static final int MIN_UPDATE_INTERVAL_MS = 200;
    private static final long SEEK_POSITION_TOLERANCE_MS = 1500;
    private static final long SEEK_POSITION_HOLD_TIMEOUT_MS = 10000;

    private final StringBuilder timeBuilder = new StringBuilder();
    private final Formatter timeFormatter = new Formatter(timeBuilder, Locale.getDefault());
    private final TextView positionView;
    private final TextView durationView;
    private final DefaultTimeBar timeBar;
    private final Runnable runnable;
    private long currentDuration;
    private long currentPosition;
    private long currentBuffered;
    private boolean scrubbing;
    private boolean attached;
    private Player player;
    private Player progressPlayer;
    private long pendingSeekPosition = C.TIME_UNSET;
    private long pendingSeekOrigin = C.TIME_UNSET;
    private long pendingSeekDeadlineMs;

    public CustomSeekView(Context context) {
        this(context, null);
    }

    public CustomSeekView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CustomSeekView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        positionView = new TextView(context);
        positionView.setTextSize(12);
        positionView.setTextColor(0xFFFFFFFF);
        durationView = new TextView(context);
        durationView.setTextSize(12);
        durationView.setTextColor(0xFFFFFFFF);
        timeBar = new DefaultTimeBar(context);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        root.addView(positionView, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        root.addView(timeBar, barParams);
        root.addView(durationView, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        addView(root, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        runnable = this::updateProgress;
        timeBar.addListener(this);
        resetView();
    }

    public void setPlayer(Player player) {
        if (this.player != null) this.player.removeListener(this);
        clearPendingSeek();
        this.player = player;
        if (player != null) player.addListener(this);
        if (attached) updateTimeline();
    }

    public void setProgressPlayer(@Nullable Player progressPlayer) {
        if (this.progressPlayer == progressPlayer) return;
        this.progressPlayer = progressPlayer;
        if (attached) updateTimeline();
    }

    @Nullable
    private Player getProgressPlayer() {
        return progressPlayer != null ? progressPlayer : player;
    }

    private String stringToTime(long time) {
        return Util.getStringForTime(timeBuilder, timeFormatter, time);
    }

    private void updateTimeline() {
        Player progress = getProgressPlayer();
        if (!attached || progress == null) return;
        long duration = progress.getDuration();
        if (duration < 0) duration = 0;
        currentDuration = duration;
        timeBar.setDuration(duration);
        durationView.setText(stringToTime(duration));
        updateProgress();
    }

    private void updateProgress() {
        removeCallbacks(runnable);
        Player progress = getProgressPlayer();
        if (!attached || progress == null) return;
        long reportedPosition = Math.max(0, progress.getCurrentPosition());
        long position = stableSeekPosition(reportedPosition);
        long buffered = Math.max(0, progress.getBufferedPosition());
        long duration = progress.getDuration();
        if (duration < 0) duration = 0;
        currentPosition = position;
        currentBuffered = buffered;
        currentDuration = duration;
        if (!scrubbing) {
            timeBar.setPosition(position);
            timeBar.setBufferedPosition(buffered);
            positionView.setText(stringToTime(position));
            durationView.setText(stringToTime(duration));
        }
        long delay = delayMs(progress, position);
        postDelayed(runnable, delay);
    }

    private long delayMs(Player progress, long position) {
        long duration = progress.getDuration();
        if (duration <= 0 || !progress.isPlaying()) return MAX_UPDATE_INTERVAL_MS;
        long remaining = duration - position;
        return Math.max(MIN_UPDATE_INTERVAL_MS, Math.min(MAX_UPDATE_INTERVAL_MS, remaining));
    }

    private void resetView() {
        currentDuration = 0;
        currentPosition = 0;
        currentBuffered = 0;
        timeBar.setDuration(0);
        timeBar.setPosition(0);
        timeBar.setBufferedPosition(0);
        positionView.setText(stringToTime(0));
        durationView.setText(stringToTime(0));
    }

    public void previewSeekPosition(long positionMs) {
        if (currentDuration <= 0) return;
        long position = Math.max(0, Math.min(positionMs, currentDuration));
        timeBar.setPosition(position);
        positionView.setText(stringToTime(position));
    }

    public void commitSeekPreview(long positionMs) {
        if (player == null || currentDuration <= 0) return;
        long position = Math.max(0, Math.min(positionMs, currentDuration));
        beginPendingSeek(position);
        player.seekTo(position);
        previewSeekPosition(position);
    }

    private void seekToTimeBarPosition(long positionMs) {
        commitSeekPreview(positionMs);
    }

    private void beginPendingSeek(long targetPosition) {
        pendingSeekPosition = targetPosition;
        pendingSeekOrigin = currentPosition;
        pendingSeekDeadlineMs = SystemClock.elapsedRealtime() + SEEK_POSITION_HOLD_TIMEOUT_MS;
    }

    private long stableSeekPosition(long reportedPosition) {
        if (pendingSeekPosition == C.TIME_UNSET) return reportedPosition;
        if (SystemClock.elapsedRealtime() > pendingSeekDeadlineMs) {
            clearPendingSeek();
            return reportedPosition;
        }
        if (Math.abs(reportedPosition - pendingSeekPosition) <= SEEK_POSITION_TOLERANCE_MS) {
            clearPendingSeek();
            return reportedPosition;
        }
        return pendingSeekPosition;
    }

    private void clearPendingSeek() {
        pendingSeekPosition = C.TIME_UNSET;
        pendingSeekOrigin = C.TIME_UNSET;
        pendingSeekDeadlineMs = 0;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateTimeline();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        removeCallbacks(runnable);
    }

    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
        clearPendingSeek();
        if (attached) updateTimeline();
    }

    @Override
    public void onScrubStart(@NonNull TimeBar timeBar, long position) {
        scrubbing = true;
        previewSeekPosition(position);
    }

    @Override
    public void onScrubMove(@NonNull TimeBar timeBar, long position) {
        previewSeekPosition(position);
    }

    @Override
    public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
        scrubbing = false;
        if (!canceled) seekToTimeBarPosition(position);
        else updateProgress();
    }
}
