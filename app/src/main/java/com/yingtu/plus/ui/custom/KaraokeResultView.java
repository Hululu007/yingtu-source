package com.yingtu.plus.ui.custom;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.yingtu.plus.player.karaoke.KaraokeResult;
import com.yingtu.plus.utils.ResUtil;

// 对照 webhtv 重写
public class KaraokeResultView extends LinearLayout {

    private static final int TEXT_PRIMARY = 0xFFF8FAFC;
    private static final int TEXT_SECONDARY = 0xFFCBD5E1;
    private static final int TEXT_MUTED = 0xFF94A3B8;

    private TextView action;
    private boolean leanbackLandscapeExpanded;

    public KaraokeResultView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(20), dp(18), dp(20), dp(18));
        setMinimumWidth(dialogWidth());
        setBackground(panelBackground());
    }

    public KaraokeResultView setResult(KaraokeResult result) {
        removeAllViews();
        action = null;
        if (result == null) return this;
        addHeader(result);
        addHero(result);
        addMetrics(result);
        addStats(result);
        addFootnote(result);
        addAction();
        return this;
    }

    public KaraokeResultView setLeanbackLandscapeExpanded(boolean expanded) {
        leanbackLandscapeExpanded = expanded;
        setMinimumWidth(dialogWidth());
        return this;
    }

    public KaraokeResultView setAction(Runnable runnable) {
        if (action != null) action.setOnClickListener(v -> {
            if (runnable != null) runnable.run();
        });
        return this;
    }

    public void requestActionFocus() {
        if (action != null) action.post(() -> action.requestFocus());
    }

    public int getPreferredDialogWidth() {
        return dialogWidth();
    }

    private int dialogWidth() {
        int width = Math.min(ResUtil.getScreenWidth(), ResUtil.getScreenHeight());
        int target = leanbackLandscapeExpanded ? (int) (width * 0.86f) : (int) (width * 0.82f);
        return Math.max(dp(280), Math.min(dp(420), target));
    }

    private void addHeader(KaraokeResult result) {
        TextView title = text("演唱完成", 15, TEXT_SECONDARY, true);
        title.setGravity(Gravity.CENTER);
        addView(title);
        if (!TextUtils.isEmpty(result.getTrackLabel())) {
            TextView track = text(result.getTrackLabel(), 13, TEXT_MUTED, false);
            track.setGravity(Gravity.CENTER);
            track.setSingleLine(true);
            track.setEllipsize(TextUtils.TruncateAt.END);
            addView(track);
        }
    }

    private void addHero(KaraokeResult result) {
        TextView score = text(String.valueOf(result.getScorePercent()), 52, TEXT_PRIMARY, true);
        score.setGravity(Gravity.CENTER);
        addView(score, layoutParams(0, 8));
        TextView grade = text("评分 " + result.getGrade(), 15, TEXT_SECONDARY, true);
        grade.setGravity(Gravity.CENTER);
        addView(grade);
    }

    private void addMetrics(KaraokeResult result) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.addView(metric("命中", result.getHitPercent() + "%"));
        row.addView(metric("发声", result.getVoicedPercent() + "%"));
        if (result.isPitchScoring()) {
            row.addView(metric("完美", result.getPerfectPercent() + "%"));
            row.addView(metric("颤音", result.getVibratoPercent() + "%"));
        }
        row.addView(metric("加分", result.getBonusPercent() + "%"));
        addView(row, layoutParams(12, 4));
    }

    private LinearLayout metric(String label, String value) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setPadding(dp(10), 0, dp(10), 0);
        column.addView(text(value, 16, TEXT_PRIMARY, true));
        column.addView(text(label, 12, TEXT_MUTED, false));
        return column;
    }

    private void addStats(KaraokeResult result) {
        String stats = "演唱 " + result.getTotalSeconds() + " 秒 · 最高连唱 " + result.getBestComboSeconds() + " 秒 · 已评分 " + result.getScoredLineCount() + " 句";
        if (result.getScoredLineCount() > 0) {
            stats += "\n句均 " + result.getAverageLineScorePercent() + "% · 最佳单句 " + result.getBestLineScorePercent() + "%";
        }
        TextView view = text(stats, 13, TEXT_SECONDARY, false);
        view.setGravity(Gravity.CENTER);
        addView(view, layoutParams(8, 4));
    }

    private void addFootnote(KaraokeResult result) {
        if (!result.isScoring()) return;
        TextView view = text(result.isPitchScoring() ? "已按音高评分" : "已按发声评分", 12, TEXT_MUTED, false);
        view.setGravity(Gravity.CENTER);
        addView(view, layoutParams(4, 0));
    }

    private void addAction() {
        action = text("继续", 15, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(16), dp(10), dp(16), dp(10));
        action.setClickable(true);
        action.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF4CAF50);
        background.setCornerRadius(dp(8));
        action.setBackground(background);
        addView(action, layoutParams(14, 0));
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xFF1E2433);
        drawable.setCornerRadius(dp(16));
        drawable.setStroke(dp(1), 0x26FFFFFF);
        return drawable;
    }

    private LayoutParams layoutParams(int topMarginDp, int bottomMarginDp) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        params.bottomMargin = dp(bottomMarginDp);
        return params;
    }

    private static int dp(int value) {
        return ResUtil.dp2px(value);
    }
}
