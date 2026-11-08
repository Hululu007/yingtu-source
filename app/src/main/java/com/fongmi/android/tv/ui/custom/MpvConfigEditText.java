package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.Gravity;

public class MpvConfigEditText extends SafeScrollEditText {

    private final Paint gutterBackground = new Paint();
    private final Paint gutterDivider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gutterNumber = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int gutterSize;
    private final int gutterGap;

    public MpvConfigEditText(Context context) {
        this(context, null);
    }

    public MpvConfigEditText(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.editTextStyle);
    }

    public MpvConfigEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        gutterSize = toPx(46);
        gutterGap = toPx(12);
        setupEditor();
    }

    private void setupEditor() {
        gutterBackground.setColor(Color.parseColor("#F8F9FA"));
        gutterDivider.setColor(Color.parseColor("#E1E5EA"));
        gutterDivider.setStrokeWidth(toPx(1));
        gutterNumber.setColor(Color.parseColor("#9AA0A6"));
        gutterNumber.setTextAlign(Paint.Align.RIGHT);
        gutterNumber.setTextSize(toSp(11));
        gutterNumber.setTypeface(Typeface.MONOSPACE);
        setTypeface(Typeface.MONOSPACE);
        setGravity(Gravity.TOP | Gravity.START);
        setHorizontallyScrolling(true);
        setPadding(gutterSize + gutterGap, toPx(12), toPx(18), toPx(18));
        setLineSpacing(toPx(2), 1f);
        setSelectAllOnFocus(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawLineNumbers(canvas);
        super.onDraw(canvas);
    }

    private void drawLineNumbers(Canvas canvas) {
        int left = getScrollX();
        int top = getScrollY();
        canvas.drawRect(left, top, left + gutterSize, top + getHeight(), gutterBackground);
        canvas.drawLine(left + gutterSize, top, left + gutterSize, top + getHeight(), gutterDivider);
        Layout layout = getLayout();
        if (layout == null || layout.getLineCount() == 0) return;
        int firstLine = Math.max(0, layout.getLineForVertical(Math.max(0, getScrollY() - getTotalPaddingTop())));
        int lastLine = Math.min(layout.getLineCount() - 1, layout.getLineForVertical(getScrollY() + getHeight()));
        for (int line = firstLine; line <= lastLine; line++) {
            float baseline = getTotalPaddingTop() + layout.getLineBaseline(line);
            canvas.drawText(String.valueOf(line + 1), left + gutterSize - toPx(9), baseline, gutterNumber);
        }
    }

    private int toPx(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private float toSp(int value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
