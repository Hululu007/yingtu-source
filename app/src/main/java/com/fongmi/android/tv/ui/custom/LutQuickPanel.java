package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.yingtu.plus.player.PlayerManager;
import com.yingtu.plus.player.lut.LutPreset;

/**
 * Placeholder for the FongMi LutQuickPanel referenced by VideoActivity.
 * VideoActivity guards every call with hasLutQuick() and falls back to
 * LutPanelDialog, so this stub only needs the four called methods plus the
 * ImportCallback interface; all calls are no-ops (hideIfVisible() returns
 * false so back-press falls through to the control layer). Replace with the
 * real LUT quick panel port when it lands.
 */
public class LutQuickPanel extends FrameLayout {

    public interface ImportCallback {
        void onImportLut();

        void onSelectLutDir();
    }

    public LutQuickPanel(@NonNull Context context) {
        super(context);
    }

    public LutQuickPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public LutQuickPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void refreshList() {
    }

    public void selectImported(LutPreset preset, PlayerManager player, PlayerView exo, Runnable onChanged) {
    }

    public void toggle(PlayerManager player, PlayerView exo, Runnable onChanged, ImportCallback callback) {
    }

    public boolean hideIfVisible() {
        return false;
    }
}
