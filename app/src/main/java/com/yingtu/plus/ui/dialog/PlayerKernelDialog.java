package com.yingtu.plus.ui.dialog;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.yingtu.plus.setting.PlayerSetting;

// 对照 webhtv 重写
public final class PlayerKernelDialog {

    public interface Listener {
        void onSelected(int player);
    }

    private static final String[] KERNELS = new String[]{"ExoPlayer", "IJKPlayer", "MPV"};

    private PlayerKernelDialog() {
    }

    public static void show(FragmentActivity activity, int selected, Listener listener) {
        int current = PlayerSetting.sanitizePlayer(selected);
        new AlertDialog.Builder(activity)
                .setTitle("播放内核")
                .setSingleChoiceItems(KERNELS, current, (dialog, which) -> {
                    dialog.dismiss();
                    notifySelected(current, which, listener);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public static void show(Fragment fragment, int selected, Listener listener) {
        if (fragment.getActivity() == null) return;
        show(fragment.getActivity(), selected, listener);
    }

    private static void notifySelected(int current, int selected, Listener listener) {
        int target = PlayerSetting.sanitizePlayer(selected);
        if (target != current && listener != null) listener.onSelected(target);
    }
}
