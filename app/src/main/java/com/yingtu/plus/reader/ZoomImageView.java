package com.yingtu.plus.reader;

import android.content.Context;
import android.graphics.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 可双指缩放、单指拖动的图片视图（漫画翻页里的单图缩放）。
 * 不引入第三方 PhotoView，用 Matrix + ScaleGestureDetector 实现。
 */
public class ZoomImageView extends AppCompatImageView {

    private final Matrix matrix = new Matrix();
    private final float[] values = new float[9];
    private final ScaleGestureDetector scaleDetector;

    private float minScale = 1f;
    private float maxScale = 4f;
    private float lastX;
    private float lastY;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float scale = getScale() * detector.getScaleFactor();
                        scale = Math.max(minScale, Math.min(maxScale, scale));
                        float factor = scale / getScale();
                        matrix.postScale(factor, factor,
                                detector.getFocusX(), detector.getFocusY());
                        fixBounds();
                        setImageMatrix(matrix);
                        return true;
                    }
                });
    }

    private float getScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed && getDrawable() != null) resetMatrix();
    }

    private void resetMatrix() {
        if (getDrawable() == null) return;
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        int drawableWidth = getDrawable().getIntrinsicWidth();
        int drawableHeight = getDrawable().getIntrinsicHeight();
        if (viewWidth == 0 || viewHeight == 0 || drawableWidth <= 0 || drawableHeight <= 0) return;
        float scale = Math.min((float) viewWidth / drawableWidth,
                (float) viewHeight / drawableHeight);
        minScale = scale;
        matrix.setScale(scale, scale);
        matrix.postTranslate((viewWidth - drawableWidth * scale) / 2f,
                (viewHeight - drawableHeight * scale) / 2f);
        setImageMatrix(matrix);
    }

    private void fixBounds() {
        matrix.getValues(values);
        float transX = values[Matrix.MTRANS_X];
        float transY = values[Matrix.MTRANS_Y];
        float scale = values[Matrix.MSCALE_X];
        if (getDrawable() == null) return;
        float width = getDrawable().getIntrinsicWidth() * scale;
        float height = getDrawable().getIntrinsicHeight() * scale;
        float dx = 0;
        float dy = 0;
        if (width <= getWidth()) {
            dx = (getWidth() - width) / 2f - transX;
        } else if (transX > 0) {
            dx = -transX;
        } else if (transX + width < getWidth()) {
            dx = getWidth() - (transX + width);
        }
        if (height <= getHeight()) {
            dy = (getHeight() - height) / 2f - transY;
        } else if (transY > 0) {
            dy = -transY;
        } else if (transY + height < getHeight()) {
            dy = getHeight() - (transY + height);
        }
        matrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    matrix.postTranslate(dx, dy);
                    fixBounds();
                    setImageMatrix(matrix);
                    lastX = event.getX();
                    lastY = event.getY();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 缩回 1 倍以下时复位，避免图片缩没
                if (getScale() < minScale) resetMatrix();
                break;
            default:
                break;
        }
        // 未放大时不消费横滑，让 ViewPager2 翻页
        if (getScale() <= minScale + 0.01f) {
            return false;
        }
        return true;
    }

    /** 双击复位用：外部调用 */
    public void resetZoom() {
        resetMatrix();
    }
}
