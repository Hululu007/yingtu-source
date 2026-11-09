package com.yingtu.plus.service;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.yingtu.plus.R;
import com.yingtu.plus.player.lyrics.LyricsLine;
import com.yingtu.plus.player.lyrics.LyricsResult;
import com.yingtu.plus.ui.activity.HomeActivity;
import com.yingtu.plus.ui.activity.VideoActivity;
import com.yingtu.plus.util.Prefers;

import java.util.List;

/**
 * 后台播放服务：之前只是个空壳绑定服务，只转上下集回调，没有
 * onStartCommand、没有前台通知、没有耳机键处理，进程一切后台
 * 就可能被系统回收，音频跟着停。
 *
 * 现在补齐（播放核心仍在 VideoActivity / PlayerManager，本类
 * 不搬播放器，只负责保活 + 通知栏控制 + 耳机键 + 开机续播）：
 * 1. 前台服务：播放开始后起前台通知（上一集 / 播放·暂停 /
 *    下一集 / 停止），切后台后进程不被回收，音频继续播；
 * 2. 通知栏按钮与耳机线控走同一个 NavigationCallback 回播放页；
 * 3. 记下“后台播放中 + 在播哪部”，手机重启后由
 *    PlaybackBootReceiver 发一条续播通知，点了才回播放页。
 *
 * 使用条件：设置里“后台播放”开关（BackgroundPlaybackPolicy，
 * 默认关）打开才起前台服务；关着时行为与以前完全一样。
 *
 * 已知边界（不是本轮能补的）：播放器本体仍归 VideoActivity
 * 持有，Activity 被系统销毁（例如划掉小窗后）音频还是会停；
 * 真正把播放器搬进服务是另一轮大重构，本轮只做保活这一层。
 */
public class PlaybackService extends Service {

    public static final String CHANNEL_ID = "playback";
    public static final int NOTIFICATION_ID = 9528;
    public static final int RESUME_NOTIFICATION_ID = 9529;

    public static final String ACTION_START = "com.yingtu.plus.action.BG_START";
    public static final String ACTION_UPDATE = "com.yingtu.plus.action.BG_UPDATE";
    public static final String ACTION_TOGGLE = "com.yingtu.plus.action.BG_TOGGLE";
    public static final String ACTION_NEXT = "com.yingtu.plus.action.BG_NEXT";
    public static final String ACTION_PREV = "com.yingtu.plus.action.BG_PREV";
    public static final String ACTION_STOP = "com.yingtu.plus.action.BG_STOP";

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_SITE_KEY = "site_key";
    public static final String EXTRA_VOD_ID = "vod_id";
    public static final String EXTRA_VOD_NAME = "vod_name";
    public static final String EXTRA_VOD_PIC = "vod_pic";

    private static final String PREF_ACTIVE = "bg_playback_active";
    private static final String PREF_TITLE = "bg_playback_title";
    private static final String PREF_SITE_KEY = "bg_playback_site_key";
    private static final String PREF_VOD_ID = "bg_playback_vod_id";
    private static final String PREF_VOD_NAME = "bg_playback_vod_name";
    private static final String PREF_VOD_PIC = "bg_playback_vod_pic";

    private NavigationCallback navigationCallback;
    private final IBinder binder = new LocalBinder();
    private String title = "";
    private boolean playing;
    private boolean foreground;
    private BroadcastReceiver mediaButtonReceiver;

    /** 播放开始：记下在播哪部，起前台服务保活。 */
    public static void startBackground(Context context, String title, String siteKey, String vodId, String vodName, String vodPic) {
        if (context == null) return;
        Prefers.put(PREF_ACTIVE, 1);
        Prefers.put(PREF_TITLE, title == null ? "" : title);
        Prefers.put(PREF_SITE_KEY, siteKey == null ? "" : siteKey);
        Prefers.put(PREF_VOD_ID, vodId == null ? "" : vodId);
        Prefers.put(PREF_VOD_NAME, vodName == null ? "" : vodName);
        Prefers.put(PREF_VOD_PIC, vodPic == null ? "" : vodPic);
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_START);
        intent.putExtra(EXTRA_TITLE, title == null ? "" : title);
        intent.putExtra(EXTRA_PLAYING, true);
        startServiceCompat(context, intent);
    }

    /** 播放 / 暂停变化：只刷新通知栏按钮状态，不重起服务。 */
    public static void updatePlaying(Context context, String title, boolean isPlaying) {
        if (context == null) return;
        Intent intent = new Intent(context, PlaybackService.class).setAction(ACTION_UPDATE);
        intent.putExtra(EXTRA_TITLE, title == null ? "" : title);
        intent.putExtra(EXTRA_PLAYING, isPlaying);
        startServiceCompat(context, intent);
    }

    /** 播放页退出：停前台服务，清掉“后台播放中”标记。 */
    public static void stopBackground(Context context) {
        if (context == null) return;
        Prefers.put(PREF_ACTIVE, 0);
        try {
            context.stopService(new Intent(context, PlaybackService.class));
        } catch (Exception ignored) {
        }
    }

    /** 开机续播有没有可续的内容。 */
    public static boolean hasResume() {
        if (Prefers.getInt(PREF_ACTIVE, 0) != 1) return false;
        return !TextUtils.isEmpty(Prefers.getString(PREF_VOD_ID, "")) && !TextUtils.isEmpty(Prefers.getString(PREF_SITE_KEY, ""));
    }

    public static String getResumeTitle() {
        String name = Prefers.getString(PREF_VOD_NAME, "");
        if (!TextUtils.isEmpty(name)) return name;
        return Prefers.getString(PREF_TITLE, "");
    }

    /** 续播意图：回到 VideoActivity，用存下的站源与片名重开播放页。 */
    @Nullable
    public static Intent buildResumeIntent(Context context) {
        if (context == null || !hasResume()) return null;
        Intent intent = new Intent(context, VideoActivity.class);
        intent.putExtra("key", Prefers.getString(PREF_SITE_KEY, ""));
        intent.putExtra("id", Prefers.getString(PREF_VOD_ID, ""));
        intent.putExtra("name", Prefers.getString(PREF_VOD_NAME, ""));
        intent.putExtra("pic", Prefers.getString(PREF_VOD_PIC, ""));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }

    private static void startServiceCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        registerMediaButtonReceiver();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();
        switch (action) {
            case ACTION_START:
                title = intent.getStringExtra(EXTRA_TITLE);
                if (title == null) title = "";
                playing = true;
                startForegroundNotification();
                break;
            case ACTION_UPDATE:
                title = intent.getStringExtra(EXTRA_TITLE);
                if (title == null) title = this.title;
                playing = intent.getBooleanExtra(EXTRA_PLAYING, playing);
                // 更新也可能先于 START 到达（例如服务刚被系统回收过）：
                // 只要收到更新就保证前台化，否则 startForegroundService
                // 之后没调 startForeground 会触发前台服务超时。
                if (foreground) refreshNotification();
                else startForegroundNotification();
                break;
            case ACTION_TOGGLE:
                playing = !playing;
                if (navigationCallback != null) navigationCallback.onTogglePlay();
                if (foreground) refreshNotification();
                break;
            case ACTION_NEXT:
                if (navigationCallback != null) navigationCallback.onNext();
                break;
            case ACTION_PREV:
                if (navigationCallback != null) navigationCallback.onPrev();
                break;
            case ACTION_STOP:
                if (navigationCallback != null) navigationCallback.onStop();
                stopForegroundService();
                break;
            default:
                break;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterMediaButtonReceiver();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        foreground = false;
        super.onDestroy();
    }

    private void createChannel() {
        try {
            NotificationManagerCompat manager = NotificationManagerCompat.from(this);
            manager.createNotificationChannel(new NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(R.string.bg_channel_name)).build());
        } catch (Exception ignored) {
        }
    }

    private void startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
            foreground = true;
        } catch (Exception e) {
            foreground = false;
        }
    }

    private void refreshNotification() {
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    private void stopForegroundService() {
        Prefers.put(PREF_ACTIVE, 0);
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        foreground = false;
        stopSelf();
    }

    private android.app.Notification buildNotification() {
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, home,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(TextUtils.isEmpty(title) ? getString(R.string.app_name) : title)
                .setContentText(getString(R.string.bg_playing))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, getString(R.string.bg_prev), serviceAction(ACTION_PREV, 1))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        getString(playing ? R.string.bg_pause : R.string.bg_play), serviceAction(ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, getString(R.string.bg_next), serviceAction(ACTION_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.bg_stop), serviceAction(ACTION_STOP, 4));
        return builder.build();
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void registerMediaButtonReceiver() {
        if (mediaButtonReceiver != null) return;
        mediaButtonReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !AudioManager.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
                KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return;
                if (navigationCallback == null) return;
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        playing = !playing;
                        navigationCallback.onTogglePlay();
                        if (foreground) refreshNotification();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PLAY:
                        if (!playing) {
                            playing = true;
                            navigationCallback.onTogglePlay();
                            if (foreground) refreshNotification();
                        }
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PAUSE:
                        if (playing) {
                            playing = false;
                            navigationCallback.onTogglePlay();
                            if (foreground) refreshNotification();
                        }
                        break;
                    case KeyEvent.KEYCODE_MEDIA_NEXT:
                        navigationCallback.onNext();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                        navigationCallback.onPrev();
                        break;
                    case KeyEvent.KEYCODE_MEDIA_STOP:
                        navigationCallback.onStop();
                        stopForegroundService();
                        break;
                    default:
                        break;
                }
            }
        };
        try {
            registerReceiver(mediaButtonReceiver, new IntentFilter(AudioManager.ACTION_MEDIA_BUTTON));
        } catch (Exception e) {
            mediaButtonReceiver = null;
        }
    }

    private void unregisterMediaButtonReceiver() {
        if (mediaButtonReceiver == null) return;
        try {
            unregisterReceiver(mediaButtonReceiver);
        } catch (Exception ignored) {
        }
        mediaButtonReceiver = null;
    }

    /** 外部播放器回放完：走上下集回调（VideoActivity 传给 PlayerHelper 的 onNext）。 */
    public void dispatchNext() {
        if (navigationCallback != null) navigationCallback.onNext();
    }

    public void setNavigationCallback(NavigationCallback callback) {
        this.navigationCallback = callback;
    }

    public NavigationCallback getNavigationCallback() {
        return navigationCallback;
    }

    /** 桌面歌词：当前音频是否为歌词内容。 */
    public void setDesktopLyricsAudioContent(boolean audioContent) {
        // 桌面歌词 UI 由调用方维护，服务侧只保留接口。
    }

    /** 桌面歌词：推送当前行快照。 */
    public void setDesktopLyricsSnapshot(LyricsResult result, List<LyricsLine> lines) {
        // 桌面歌词 UI 由调用方维护，服务侧只保留接口。
    }

    public interface NavigationCallback {

        default void onPrev() {}

        default void onNext() {}

        default void onStop() {}

        default void onReplay() {}

        default void onAudio() {}

        /** 通知栏 / 耳机键的播放·暂停切换。 */
        default void onTogglePlay() {}
    }

    public class LocalBinder extends Binder {

        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }
}
