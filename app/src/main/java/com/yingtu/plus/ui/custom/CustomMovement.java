package com.yingtu.plus.ui.custom;

import android.graphics.RectF;
import android.text.Layout;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.Spannable;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import com.yingtu.plus.utils.KeyUtil;
import com.yingtu.plus.utils.ResUtil;

// 对照 webhtv 重写
public class CustomMovement extends ScrollingMovementMethod {

    private static final int CLICK = 1;
    private static final int UP = 2;
    private static final int DOWN = 3;
    private static final Object FROM_BELOW = new NoCopySpan.Concrete();
    private static CustomMovement sInstance;

    public static CustomMovement getInstance() {
        if (sInstance == null) sInstance = new CustomMovement();
        return sInstance;
    }

    public static void bind(TextView view) {
        CharSequence text = view.getText();
        boolean hasLinks = text instanceof Spannable && ((Spannable) text).getSpans(0, text.length(), ClickableSpan.class).length > 0;
        view.setMovementMethod(hasLinks ? CustomMovement.getInstance() : null);
        if (hasLinks) view.setHighlightColor(ResUtil.getColor(android.R.color.white) & 0x4DFFFFFF);
    }

    @Override
    public boolean canSelectArbitrarily() {
        return true;
    }

    @Override
    protected boolean handleMovementKey(TextView widget, Spannable buffer, int keyCode, int movementMetaState, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (KeyEvent.metaStateHasNoModifiers(movementMetaState)) {
                    if (KeyUtil.isActionDown(event) && event.getRepeatCount() == 0 && action(CLICK, widget, buffer)) {
                        return true;
                    }
                }
                break;
        }
        return super.handleMovementKey(widget, buffer, keyCode, movementMetaState, event);
    }

    private boolean findFocus(TextView widget, int direction) {
        View view = widget.focusSearch(direction);
        if (view == null) return false;
        view.requestFocus();
        return true;
    }

    @Override
    protected boolean up(TextView widget, Spannable buffer) {
        if (action(UP, widget, buffer)) return true;
        if (findFocus(widget, View.FOCUS_UP)) return true;
        return super.up(widget, buffer);
    }

    @Override
    protected boolean down(TextView widget, Spannable buffer) {
        if (action(DOWN, widget, buffer)) return true;
        if (findFocus(widget, View.FOCUS_DOWN)) return true;
        return super.down(widget, buffer);
    }

    @Override
    protected boolean left(TextView widget, Spannable buffer) {
        if (findFocus(widget, View.FOCUS_LEFT)) return true;
        return super.left(widget, buffer);
    }

    @Override
    protected boolean right(TextView widget, Spannable buffer) {
        if (findFocus(widget, View.FOCUS_RIGHT)) return true;
        return super.right(widget, buffer);
    }

    private boolean action(int what, TextView widget, Spannable buffer) {
        Layout layout = widget.getLayout();
        if (layout == null) return false;
        int selectionStart = Selection.getSelectionStart(buffer);
        int selectionEnd = Selection.getSelectionEnd(buffer);
        if (selectionStart < 0 || selectionEnd < 0) return false;
        int line = layout.getLineForOffset(selectionStart);
        ClickableSpan[] links = buffer.getSpans(selectionStart, selectionEnd, ClickableSpan.class);
        if (links.length == 0) return false;
        switch (what) {
            case CLICK:
                links[0].onClick(widget);
                return true;
            case UP: {
                int target = layout.getLineStart(Math.max(0, line - 1));
                ClickableSpan[] above = buffer.getSpans(target, target, ClickableSpan.class);
                if (above.length > 0) {
                    Selection.setSelection(buffer, buffer.getSpanStart(above[0]), buffer.getSpanEnd(above[0]));
                    return true;
                }
                return false;
            }
            case DOWN: {
                int target = layout.getLineStart(Math.min(layout.getLineCount() - 1, line + 1));
                ClickableSpan[] below = buffer.getSpans(target, target, ClickableSpan.class);
                if (below.length > 0) {
                    Selection.setSelection(buffer, buffer.getSpanStart(below[0]), buffer.getSpanEnd(below[0]));
                    return true;
                }
                return false;
            }
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
        int action = event.getAction();
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(widget, buffer, event);
        Layout layout = widget.getLayout();
        if (layout == null) return super.onTouchEvent(widget, buffer, event);
        int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
        int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
        int line = layout.getLineForVertical(y);
        RectF bounds = new RectF(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineLeft(line) + layout.getLineWidth(line), layout.getLineBottom(line));
        if (!bounds.contains(x, y)) return super.onTouchEvent(widget, buffer, event);
        int off = layout.getOffsetForHorizontal(line, x);
        ClickableSpan[] links = buffer.getSpans(off, off, ClickableSpan.class);
        if (links.length == 0) {
            Selection.removeSelection(buffer);
            return super.onTouchEvent(widget, buffer, event);
        }
        if (action == MotionEvent.ACTION_UP) links[0].onClick(widget);
        return true;
    }

    @Override
    public void initialize(TextView widget, Spannable text) {
        Selection.removeSelection(text);
        text.removeSpan(FROM_BELOW);
    }

    @Override
    public void onTakeFocus(TextView view, Spannable text, int dir) {
        Selection.removeSelection(text);
        if (dir == View.FOCUS_BACKWARD) {
            text.setSpan(FROM_BELOW, 0, 0, Spannable.SPAN_POINT_POINT);
        } else {
            text.removeSpan(FROM_BELOW);
        }
    }
}
