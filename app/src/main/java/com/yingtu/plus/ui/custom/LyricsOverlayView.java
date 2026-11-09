package com.yingtu.plus.ui.custom;

import android.content.Context;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.yingtu.plus.player.lyrics.LyricsLine;
import com.yingtu.plus.player.lyrics.LyricsParser;
import com.yingtu.plus.player.lyrics.LyricsResult;
import com.yingtu.plus.utils.ResUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 对照 webhtv 重写
public class LyricsOverlayView extends FrameLayout {

    public interface SeekListener {
        void onLyricsSeek(long positionMs);
    }

    private static final long POSITION_RESET_THRESHOLD_MS = 3000;

    private final LinearLayout box;
    private final TextView currentView;
    private final TextView nextView;
    private SeekListener seekListener;
    private List<LyricsLine> lines = Collections.emptyList();
    private int index = -1;
    private boolean desktopMode;
    private boolean audioStageMode;
    private boolean suppressed;
    private boolean playing;
    /** 歌词转换显示模式：0=单行（只显当前行） 1=双行交替显示（当前行+下一行） 2=关闭（整块不显） */
    private int displayMode = 1;

    public void setDisplayMode(int mode) {
        displayMode = mode >= 0 && mode <= 2 ? mode : 1;
        applyDisplayMode();
    }

    public int getDisplayMode() {
        return displayMode;
    }

    private void applyDisplayMode() {
        nextView.setVisibility(displayMode == 0 ? GONE : VISIBLE);
        if (displayMode == 2) setVisibility(GONE);
        else if (!lines.isEmpty() && !suppressed) setVisibility(VISIBLE);
    }

    private long basePositionMs;
    private long baseRealtimeMs;
    private float downX;
    private float downY;
    private boolean dragging;

    public LyricsOverlayView(Context context) {
        this(context, null);
    }

    public LyricsOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(18), dp(10), dp(18), dp(10));
        currentView = new TextView(context);
        currentView.setGravity(Gravity.CENTER);
        currentView.setTextColor(Color.WHITE);
        currentView.setTextSize(17);
        currentView.setShadowLayer(3, 1.5f, 1.5f, 0xCC000000);
        nextView = new TextView(context);
        nextView.setGravity(Gravity.CENTER);
        nextView.setTextColor(0xB3FFFFFF);
        nextView.setTextSize(13);
        nextView.setShadowLayer(3, 1.5f, 1.5f, 0xCC000000);
        box.addView(currentView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        box.addView(nextView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setVisibility(GONE);
    }

    public void setDesktopMode(boolean desktopMode) {
        this.desktopMode = desktopMode;
        setClickable(desktopMode);
        applyStyle();
    }

    public void setAudioStageMode(boolean audioStageMode) {
        if (this.audioStageMode == audioStageMode) return;
        this.audioStageMode = audioStageMode;
        setClickable(audioStageMode);
        applyStyle();
    }

    public void setSeekListener(SeekListener seekListener) {
        this.seekListener = seekListener;
    }

    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        if (suppressed) setVisibility(GONE);
        else if (!lines.isEmpty()) setVisibility(VISIBLE);
        else setVisibility(GONE);
    }

    public void setLyrics(LyricsResult result, List<LyricsLine> lines) {
        this.lines = lines == null ? Collections.emptyList() : new ArrayList<>(lines);
        this.index = -1;
        setVisibility(this.lines.isEmpty() || suppressed ? GONE : VISIBLE);
        if (!this.lines.isEmpty()) render(basePositionMs);
    }

    public void refreshStyle() {
        applyStyle();
        index = -1;
        requestLayout();
        invalidate();
    }

    public void update(long positionMs) {
        update(positionMs, false);
    }

    public void update(long positionMs, boolean playing) {
        if (lines.isEmpty()) {
            clear();
            return;
        }
        if (suppressed) {
            setVisibility(GONE);
            return;
        }
        if (displayMode == 2) {
            setVisibility(GONE);
            return;
        }
        long position = Math.max(0, positionMs);
        if (index >= 0 && basePositionMs > 0 && position + POSITION_RESET_THRESHOLD_MS < (playing ? displayPositionMs() : basePositionMs)) {
            index = -1;
        }
        this.playing = playing;
        this.basePositionMs = position;
        this.baseRealtimeMs = SystemClock.elapsedRealtime();
        if (dragging) return;
        int nextIndex = LyricsParser.findLine(lines, position);
        if (nextIndex == index) return;
        index = nextIndex;
        render(position);
        setVisibility(VISIBLE);
    }

    private long displayPositionMs() {
        if (!playing) return basePositionMs;
        return basePositionMs + (SystemClock.elapsedRealtime() - baseRealtimeMs);
    }

    private void render(long positionMs) {
        if (index < 0 || index >= lines.size()) {
            currentView.setText("");
            nextView.setText("");
            return;
        }
        currentView.setText(lines.get(index).getText());
        if (index + 1 < lines.size()) nextView.setText(lines.get(index + 1).getText());
        else nextView.setText("");
    }

    public String debugState() {
        return "lines=" + lines.size() + ",index=" + index + ",base=" + basePositionMs + ",playing=" + playing + ",suppressed=" + suppressed + ",visible=" + getVisibility();
    }

    public void clear() {
        lines = Collections.emptyList();
        index = -1;
        currentView.setText("");
        nextView.setText("");
        setVisibility(GONE);
    }

    private void applyStyle() {
        float scale = desktopMode ? 0.9f : 1.0f;
        currentView.setTextSize(17 * scale);
        nextView.setTextSize(13 * scale);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > ResUtil.dp2px(8) || Math.abs(dy) > ResUtil.dp2px(8)) dragging = true;
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (!dragging && seekListener != null && index >= 0 && index < lines.size()) {
                    seekListener.onLyricsSeek(lines.get(index).getTimeMs());
                }
                dragging = false;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    public boolean isAudioStageTouchPoint(float rawX, float rawY) {
        if (!audioStageMode || getVisibility() != VISIBLE) return false;
        int[] location = new int[2];
        getLocationOnScreen(location);
        return rawX >= location[0] && rawX <= location[0] + getWidth() && rawY >= location[1] && rawY <= location[1] + getHeight();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        refreshStyle();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dragging = false;
    }

    private static int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
