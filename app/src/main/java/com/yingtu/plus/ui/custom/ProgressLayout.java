package com.yingtu.plus.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Placeholder for the FongMi-style ProgressLayout used by VideoActivity.
 * Only the four methods VideoActivity calls are implemented: showContent()
 * shows the content child, showProgress()/showEmpty() hide it, isEmpty()
 * reports the empty state. No spinner overlay yet; replace with the real
 * ProgressLayout port when it lands.
 */
public class ProgressLayout extends FrameLayout {

    private boolean empty;

    public ProgressLayout(@NonNull Context context) {
        super(context);
    }

    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ProgressLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void showContent() {
        empty = false;
        setContentVisible(true);
    }

    public void showProgress() {
        empty = false;
        setContentVisible(false);
    }

    public void showEmpty() {
        empty = true;
        setContentVisible(false);
    }

    public boolean isEmpty() {
        return empty;
    }

    private void setContentVisible(boolean visible) {
        View content = getChildCount() > 0 ? getChildAt(0) : null;
        if (content != null) content.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }
}
