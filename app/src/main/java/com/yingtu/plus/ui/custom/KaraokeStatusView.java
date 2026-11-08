package com.yingtu.plus.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.yingtu.plus.player.karaoke.KaraokePitchSample;
import com.yingtu.plus.player.karaoke.KaraokeScoreSnapshot;
import com.yingtu.plus.player.karaoke.KaraokeStatus;
import com.yingtu.plus.player.karaoke.KaraokeTrack;

/**
 * Placeholder for the FongMi-style KaraokeStatusView used by VideoActivity.
 * Only the four methods VideoActivity calls are present; all are no-ops
 * that keep the last state. Replace with the real karaoke status view port
 * when it lands.
 */
public class KaraokeStatusView extends FrameLayout {

    private boolean playing;
    private boolean spectrumMode;
    private KaraokeStatus status;
    private KaraokeTrack track;
    private KaraokePitchSample sample;
    private KaraokeScoreSnapshot snapshot;

    public KaraokeStatusView(@NonNull Context context) {
        super(context);
    }

    public KaraokeStatusView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public KaraokeStatusView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public void setState(KaraokeStatus status, KaraokeTrack track, KaraokePitchSample sample, KaraokeScoreSnapshot snapshot) {
        this.status = status;
        this.track = track;
        this.sample = sample;
        this.snapshot = snapshot;
    }

    public void setSpectrumMode(boolean spectrumMode) {
        this.spectrumMode = spectrumMode;
    }

    public void syncPosition(long position, boolean playing) {
        this.playing = playing;
    }
}
