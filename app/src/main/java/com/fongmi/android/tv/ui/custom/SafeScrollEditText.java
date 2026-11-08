package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.google.android.material.textfield.TextInputEditText;

public class SafeScrollEditText extends TextInputEditText {

    private static final int DRAG_NONE = 0;
    private static final int DRAG_VERTICAL = 1;
    private static final int DRAG_HORIZONTAL = 2;

    private final Paint railPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final int barThickness;
    private final int grabWidth;
    private final int minThumbLength;
    private final int edgeGap;
    private int dragging = DRAG_NONE;

    public SafeScrollEditText(Context context) {
        this(context, null);
    }

    public SafeScrollEditText(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.editTextStyle);
    }

    public SafeScrollEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        barThickness = toPx(6);
        grabWidth = toPx(24);
        minThumbLength = toPx(34);
        edgeGap = toPx(3);
        railPaint.setColor(Color.parseColor("#DADCE0"));
        thumbPaint.setColor(Color.parseColor("#185ABC"));
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawBar(canvas, true);
        drawBar(canvas, false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dragging = pickDragTarget(event.getX(), event.getY());
            if (dragging == DRAG_NONE) return super.onTouchEvent(event);
            setParentInterceptAllowed(false);
            scrollByDrag(event.getX(), event.getY());
            return true;
        }
        if (dragging == DRAG_NONE) return super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_MOVE) {
            scrollByDrag(event.getX(), event.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = DRAG_NONE;
            setParentInterceptAllowed(true);
            return true;
        }
        return true;
    }

    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(limit(x, 0, maxScrollX()), limit(y, 0, maxScrollY()));
    }

    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        invalidate();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        invalidate();
    }

    private void drawBar(Canvas canvas, boolean vertical) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        int maxScroll = vertical ? maxScrollY() : maxScrollX();
        int extent = vertical ? getHeight() : getWidth();
        int range = maxScroll + Math.max(1, extent);
        float railStart;
        float railEnd;
        float cross;
        float thumbStart;
        float thumbEnd;
        if (vertical) {
            railStart = getScrollY() + edgeGap;
            railEnd = getScrollY() + getHeight() - edgeGap - barThickness - edgeGap;
            cross = getScrollX() + getWidth() - edgeGap - barThickness;
        } else {
            railStart = getScrollX() + edgeGap;
            railEnd = getScrollX() + getWidth() - edgeGap - barThickness - edgeGap;
            cross = getScrollY() + getHeight() - edgeGap - barThickness;
        }
        float railLength = railEnd - railStart;
        if (railLength <= 0) return;
        float thumbLength = maxScroll <= 0 ? railLength : Math.max(minThumbLength, railLength * extent / range);
        float scroll = vertical ? getScrollY() : getScrollX();
        float thumbPos = railStart + (Math.max(railStart, railEnd - thumbLength) - railStart) * scroll / Math.max(1, maxScroll);
        thumbStart = thumbPos;
        thumbEnd = thumbPos + thumbLength;
        if (vertical) {
            fillRoundRect(canvas, cross, railStart, cross + barThickness, railEnd, railPaint);
            fillRoundRect(canvas, cross, thumbStart, cross + barThickness, thumbEnd, thumbPaint);
        } else {
            fillRoundRect(canvas, railStart, cross, railEnd, cross + barThickness, railPaint);
            fillRoundRect(canvas, thumbStart, cross, thumbEnd, cross + barThickness, thumbPaint);
        }
    }

    private int pickDragTarget(float x, float y) {
        if (x >= getWidth() - grabWidth) return DRAG_VERTICAL;
        if (y >= getHeight() - grabWidth) return DRAG_HORIZONTAL;
        return DRAG_NONE;
    }

    private void scrollByDrag(float x, float y) {
        if (dragging == DRAG_VERTICAL) {
            int maxScroll = maxScrollY();
            if (maxScroll <= 0) return;
            float start = edgeGap;
            float end = getHeight() - edgeGap - barThickness - edgeGap;
            scrollTo(getScrollX(), Math.round(maxScroll * ratio(y, start, end)));
        } else if (dragging == DRAG_HORIZONTAL) {
            int maxScroll = maxScrollX();
            if (maxScroll <= 0) return;
            float start = edgeGap;
            float end = getWidth() - edgeGap - barThickness - edgeGap;
            scrollTo(Math.round(maxScroll * ratio(x, start, end)), getScrollY());
        }
    }

    private float ratio(float value, float start, float end) {
        float fraction = (value - start) / Math.max(1f, end - start);
        return Math.max(0f, Math.min(1f, fraction));
    }

    private int limit(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int maxScrollX() {
        return Math.max(0, contentWidth() - getWidth());
    }

    private int maxScrollY() {
        return Math.max(0, contentHeight() - getHeight());
    }

    private int contentWidth() {
        int widest = 0;
        Layout layout = getLayout();
        if (layout != null) {
            for (int i = 0; i < layout.getLineCount(); i++) {
                widest = Math.max(widest, (int) Math.ceil(layout.getLineWidth(i)));
            }
        } else if (getText() != null) {
            widest = (int) Math.ceil(getPaint().measureText(getText().toString()));
        }
        return Math.max(getWidth(), widest + getTotalPaddingLeft() + getTotalPaddingRight());
    }

    private int contentHeight() {
        Layout layout = getLayout();
        int textHeight = layout == null ? getLineHeight() : layout.getHeight();
        return Math.max(getHeight(), textHeight + getTotalPaddingTop() + getTotalPaddingBottom());
    }

    private void setParentInterceptAllowed(boolean allowed) {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!allowed);
            parent = parent.getParent();
        }
    }

    private void fillRoundRect(Canvas canvas, float left, float top, float right, float bottom, Paint paint) {
        barRect.set(left, top, right, bottom);
        canvas.drawRoundRect(barRect, barThickness, barThickness, paint);
    }

    private int toPx(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
