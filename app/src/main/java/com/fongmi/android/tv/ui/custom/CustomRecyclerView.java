package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.yingtu.plus.R;

public class CustomRecyclerView extends RecyclerView {

    private int minWidth;
    private int minHeight;
    private int maxWidth;
    private int maxHeight;

    public CustomRecyclerView(@NonNull Context context) {
        super(context);
        init(context, null);
    }

    public CustomRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public CustomRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        if (attrs == null) return;
        TypedArray array = context.obtainStyledAttributes(attrs, R.styleable.CustomRecyclerView);
        minWidth = array.getDimensionPixelSize(R.styleable.CustomRecyclerView_android_minWidth, 0);
        minHeight = array.getDimensionPixelSize(R.styleable.CustomRecyclerView_android_minHeight, 0);
        maxWidth = array.getDimensionPixelSize(R.styleable.CustomRecyclerView_maxWidth, 0);
        maxHeight = array.getDimensionPixelSize(R.styleable.CustomRecyclerView_maxHeight, 0);
        array.recycle();
    }

    public void setMinWidth(int minWidth) {
        if (this.minWidth == minWidth) return;
        this.minWidth = minWidth;
        requestLayout();
    }

    public void setMaxWidth(int maxWidth) {
        if (this.maxWidth == maxWidth) return;
        this.maxWidth = maxWidth;
        requestLayout();
    }

    public void setMinHeight(int minHeight) {
        if (this.minHeight == minHeight) return;
        this.minHeight = minHeight;
        requestLayout();
    }

    public void setMaxHeight(int maxHeight) {
        if (this.maxHeight == maxHeight) return;
        this.maxHeight = maxHeight;
        requestLayout();
    }

    private int limitMeasureSpec(int measureSpec, int maxSize) {
        if (maxSize <= 0) return measureSpec;
        int mode = MeasureSpec.getMode(measureSpec);
        int size = MeasureSpec.getSize(measureSpec);
        if (mode == MeasureSpec.UNSPECIFIED) return MeasureSpec.makeMeasureSpec(maxSize, MeasureSpec.AT_MOST);
        if (mode == MeasureSpec.AT_MOST && size > maxSize) return MeasureSpec.makeMeasureSpec(maxSize, MeasureSpec.AT_MOST);
        return measureSpec;
    }

    private int clampSize(int measuredSize, int minSize, int maxSize) {
        int result = measuredSize;
        if (maxSize > 0 && result > maxSize) result = maxSize;
        if (minSize > 0 && result < minSize) result = minSize;
        return result;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(limitMeasureSpec(widthMeasureSpec, maxWidth), limitMeasureSpec(heightMeasureSpec, maxHeight));
        setMeasuredDimension(clampSize(getMeasuredWidth(), minWidth, maxWidth), clampSize(getMeasuredHeight(), minHeight, maxHeight));
    }
}
