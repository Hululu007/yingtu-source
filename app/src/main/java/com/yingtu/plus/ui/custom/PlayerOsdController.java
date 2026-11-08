package com.yingtu.plus.ui.custom;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.yingtu.plus.App;
import com.yingtu.plus.R;
import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.setting.PlayerSetting;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// 对照 webhtv 重写
public class PlayerOsdController {

    public interface Source {
        PlayerManager getPlayer();

        String getTitle();
    }

    private final View root;
    private final TextView topLeft;
    private final TextView topRight;
    private final TextView bottomLeft;
    private final TextView bottomRight;
    private final TextView diagnostics;
    private final TextView diagnosticsExtra;
    private final View diagnosticsPanel;
    private final View miniProgress;
    private final Source source;
    private final float miniSp;
    private final Runnable update = this::update;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final DecimalFormat speedFormat = new DecimalFormat("#.0");
    private boolean controlsVisible;
    private boolean diagnosticsVisible;
    private boolean started;

    public PlayerOsdController(View root, TextView topLeft, TextView topRight, TextView bottomLeft, TextView bottomRight, TextView diagnostics, View miniProgress, Source source, float miniSp) {
        this.root = root;
        this.topLeft = topLeft;
        this.topRight = topRight;
        this.bottomLeft = bottomLeft;
        this.bottomRight = bottomRight;
        this.diagnostics = diagnostics;
        this.diagnosticsExtra = root.findViewById(R.id.osdDiagnosticsExtra);
        this.diagnosticsPanel = root.findViewById(R.id.osdDiagnosticsPanel);
        this.miniProgress = miniProgress;
        this.source = source;
        this.miniSp = miniSp;
        if (diagnosticsExtra != null) diagnosticsExtra.setVisibility(View.GONE);
        if (miniProgress != null) {
            miniProgress.setPivotX(0);
            miniProgress.setVisibility(View.GONE);
        }
    }

    public void start() {
        started = true;
        App.post(update, 0);
    }

    public void stop() {
        started = false;
        App.removeCallbacks(update);
    }

    public void release() {
        stop();
    }

    public void setControlsVisible(boolean controlsVisible) {
        if (this.controlsVisible == controlsVisible) return;
        this.controlsVisible = controlsVisible;
        if (started) render();
    }

    public boolean isDiagnosticsVisible() {
        return diagnosticsVisible;
    }

    public void setDiagnosticsVisible(boolean visible) {
        boolean next = visible && PlayerSetting.isOsdDiagnostics();
        if (diagnosticsVisible == next) return;
        diagnosticsVisible = next;
        if (started) render();
    }

    public void toggleDiagnostics() {
        if (!PlayerSetting.isOsdDiagnostics()) return;
        diagnosticsVisible = !diagnosticsVisible;
        if (started) render();
    }

    private void update() {
        if (render()) App.post(update, 1000);
    }

    private boolean render() {
        if (!started || root == null) return false;
        PlayerManager player = source == null ? null : source.getPlayer();
        boolean showOsd = controlsVisible && player != null && !player.isEmpty();
        root.setVisibility(showOsd ? View.VISIBLE : View.GONE);
        if (!showOsd) {
            if (miniProgress != null) miniProgress.setVisibility(View.GONE);
            return started;
        }
        String title = source.getTitle();
        String size = player.getVideoWidth() > 0 && player.getVideoHeight() > 0 ? player.getVideoWidth() + " x " + player.getVideoHeight() : "";
        setText(topLeft, join("\n", title, size));
        setText(topRight, timeFormat.format(new Date()));
        setText(bottomLeft, formatTime(player.getPosition()) + " / " + formatTime(player.getDuration()));
        setText(bottomRight, player.getSpeedText() + "  " + player.getBufferedPercentage() + "%");
        if (miniProgress != null) {
            long duration = player.getDuration();
            float progress = duration > 0 ? (float) player.getPosition() / duration : 0f;
            miniProgress.setScaleX(Math.max(0f, Math.min(1f, progress)));
            miniProgress.setVisibility(View.VISIBLE);
        }
        renderDiagnostics(player);
        return true;
    }

    private void renderDiagnostics(PlayerManager player) {
        boolean show = diagnosticsVisible && PlayerSetting.isOsdDiagnostics();
        if (diagnosticsPanel != null) diagnosticsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        StringBuilder builder = new StringBuilder();
        builder.append("播放  ").append(player.getPlayerText()).append(" / ").append(player.getDecodeText()).append("\n");
        builder.append("视频  ").append(player.getVideoWidth()).append("x").append(player.getVideoHeight());
        if (player.getVideoFormat() != null) builder.append(" / ").append(player.getVideoFormat().sampleMimeType);
        builder.append("\n");
        builder.append("速度  ").append(player.getSpeedText()).append(" / 缓冲 ").append(player.getBufferedPercentage()).append("%\n");
        builder.append("状态  掉帧 ").append(player.getDroppedFrames()).append(" / 重缓冲 ").append(player.getRebufferCount()).append(" 次\n");
        String render = player.getRenderDiagnostics();
        if (!TextUtils.isEmpty(render)) builder.append(render).append("\n");
        String runtime = player.getRuntimeDiagnostics();
        if (!TextUtils.isEmpty(runtime)) builder.append(runtime);
        setText(diagnostics, builder.toString().trim());
    }

    private void setText(TextView view, String text) {
        if (view == null) return;
        view.setText(text);
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private static String join(String separator, String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            if (builder.length() > 0) builder.append(separator);
            builder.append(value);
        }
        return builder.toString();
    }

    private static String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }
}
