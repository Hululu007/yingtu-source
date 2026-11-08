package com.yingtu.plus.ui.custom;

import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;

// 按调用点重建
public class CustomKeyDown {

    public interface Listener {
        void onSpeedUp();
        void onSpeedEnd();
        void onBright(int progress);
        void onVolume(int progress);
        void onFlingUp();
        void onFlingDown();
        void onSeeking(long time);
        void onSeekEnd(long time);
        void onSingleTap();
        void onDoubleTap();
        void onTouchEnd();
    }

    private static final int SEEK_STEP_MS = 10000;
    private static final int FLING_MIN_DY = 120;

    private final Listener listener;
    private final View view;
    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private boolean lock;
    private float scale = 1.0f;
    private boolean seeking;
    private long seekTarget;
    private boolean speedUp;
    private float downX;
    private float downY;
    private int brightProgress = 50;
    private int volumeProgress = 50;
    private boolean verticalScroll;
    private boolean horizontalScroll;

    public static CustomKeyDown create(Listener listener, View view) {
        return new CustomKeyDown(listener, view);
    }

    private CustomKeyDown(Listener listener, View view) {
        this.listener = listener;
        this.view = view;
        this.gestureDetector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (!lock) listener.onSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (!lock) listener.onDoubleTap();
                return true;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                if (lock || speedUp) return;
                speedUp = true;
                listener.onSpeedUp();
            }

            @Override
            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
                if (lock) return false;
                float dy = e2.getY() - (e1 == null ? e2.getY() : e1.getY());
                if (dy < -FLING_MIN_DY && Math.abs(velocityY) > Math.abs(velocityX)) {
                    listener.onFlingUp();
                    return true;
                } else if (dy > FLING_MIN_DY && Math.abs(velocityY) > Math.abs(velocityX)) {
                    listener.onFlingDown();
                    return true;
                }
                return false;
            }
        });
        this.scaleDetector = new ScaleGestureDetector(view.getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                scale = Math.max(0.5f, Math.min(4.0f, scale * detector.getScaleFactor()));
                return true;
            }
        });
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (lock) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                endTouch();
            }
            return true;
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                verticalScroll = false;
                horizontalScroll = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (!verticalScroll && !horizontalScroll) {
                    if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 24) horizontalScroll = true;
                    else if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > 24) verticalScroll = true;
                }
                if (horizontalScroll && !seeking) {
                    seeking = true;
                    seekTarget = getCurrentPosition();
                }
                if (seeking) {
                    long delta = (long) (dx / Math.max(1, view.getWidth()) * SEEK_STEP_MS * 6);
                    seekTarget = Math.max(0, getCurrentPosition() + delta);
                    listener.onSeeking(seekTarget);
                } else if (verticalScroll) {
                    int delta = (int) (-dy / 8);
                    if (downX < view.getWidth() / 2f) {
                        brightProgress = Math.max(0, Math.min(100, brightProgress + delta));
                        listener.onBright(brightProgress);
                    } else {
                        volumeProgress = Math.max(0, Math.min(100, volumeProgress + delta));
                        listener.onVolume(volumeProgress);
                    }
                    downY = event.getY();
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                endTouch();
                break;
            default:
                break;
        }
        return true;
    }

    private void endTouch() {
        if (seeking) {
            seeking = false;
            listener.onSeekEnd(seekTarget);
        }
        if (speedUp) {
            speedUp = false;
            listener.onSpeedEnd();
        }
        listener.onTouchEnd();
    }

    private long getCurrentPosition() {
        try {
            if (view instanceof PlayerView playerView) {
                Player player = playerView.getPlayer();
                if (player != null) return Math.max(0, player.getCurrentPosition());
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public void setLock(boolean lock) {
        this.lock = lock;
        if (lock) {
            seeking = false;
            speedUp = false;
        }
    }

    public boolean isLock() {
        return lock;
    }

    public float getScale() {
        return scale;
    }

    public void resetScale() {
        scale = 1.0f;
    }
}
