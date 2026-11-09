package com.yingtu.plus.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.yingtu.plus.R;

/**
 * 开机 / 应用更新后恢复：后台播放开着时手机重启，播放进程已经没了，
 * 这里不偷偷重播，只发一条“上次后台播放已中断，点击继续播放”通知，
 * 点了才回到播放页续播（用 PlaybackService 存下的片名与站源）。
 *
 * 故意不在这里起前台服务：开机时应用在后台，起前台服务会被系统
 * 拦（Android 12+ 后台起前台服务有限制），只发一条普通通知最稳。
 */
public class PlaybackBootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (!PlaybackService.hasResume()) return;
        Intent resume = PlaybackService.buildResumeIntent(context);
        if (resume == null) return;
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_play)
                    .setContentTitle(context.getString(R.string.bg_resume_title))
                    .setContentText(context.getString(R.string.bg_resume_text, PlaybackService.getResumeTitle()))
                    .setContentIntent(android.app.PendingIntent.getActivity(context, 0, resume,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true);
            NotificationManagerCompat.from(context).notify(PlaybackService.RESUME_NOTIFICATION_ID, builder.build());
        } catch (Exception ignored) {
        }
    }
}
