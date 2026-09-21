package com.videoshell.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
// ⚠️ 注意包名：androidx.media:media 里 MediaSessionCompat / PlaybackStateCompat
// 沿用的是**旧包名** android.support.v4.media.session（不是 androidx.media.session，
// 后者的 session 包里只有 MediaButtonReceiver）。写成 androidx.media.session 会
// 报 "Unresolved reference"，但依赖本身是好的 —— 别去动 build.gradle。
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.videoshell.R

/**
 * 后台播放 + 媒体通知（FN-4）。
 *
 * 职责很窄：**不持有播放器**，只负责
 *  - 一个 [MediaSessionCompat]（提供锁屏播放/暂停控制）；
 *  - 一条前台 [MediaStyle] 通知（在 App 退到后台时还能控制、且系统不会轻易杀掉）。
 *
 * 真正的 ExoPlayer 在 [PlayerActivity] 里。本服务通过广播和它通信：
 *  - 服务 → 播放器：`ACTION_PLAY` / `ACTION_PAUSE` / `ACTION_STOP`（用户点通知/锁屏触发）；
 *  - 播放器 → 服务：`ACTION_STATE`（播放器状态变了，通知的图标要跟着翻）。
 *
 * 这样玩家生命周期仍由 Activity 管，服务只做"展示 + 转发意图"，互不绑架。
 */
class MediaNotificationService : Service() {

    companion object {
        const val CHANNEL_ID = "video_shell_playback"
        const val NOTIF_ID = 1001

        /** 服务 → 播放器 */
        const val ACTION_PLAY = "com.videoshell.play"
        const val ACTION_PAUSE = "com.videoshell.pause"
        const val ACTION_STOP = "com.videoshell.stop_service"

        /** 播放器 → 服务：当前是否正在播 */
        const val ACTION_STATE = "com.videoshell.state"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_TITLE = "title"

        /** 由播放器调用来启动/更新本服务 */
        fun start(ctx: Context, title: String, playing: Boolean) {
            val i = Intent(ctx, MediaNotificationService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PLAYING, playing)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MediaNotificationService::class.java))
        }
    }

    private var session: MediaSessionCompat? = null
    private var playing = false
    private var title = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()

        session = MediaSessionCompat(this, "VideoShell").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // 锁屏/通知上的播放、暂停、停止 ⇒ 转成广播交给 PlayerActivity
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send(ACTION_PLAY)
                override fun onPause() = send(ACTION_PAUSE)
                override fun onStop() = send(ACTION_STOP)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        playing = intent?.getBooleanExtra(EXTRA_PLAYING, playing) ?: playing
        pushState(playing)
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    /** 播放器状态变化：刷新 PlaybackState + 通知图标 */
    private fun pushState(isPlaying: Boolean) {
        playing = isPlaying
            session?.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(
                        if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                        else PlaybackStateCompat.STATE_PAUSED,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        1f
                    )
                    .build()
            )
    }

    override fun onStart(intent: Intent?, startId: Int) {
        super.onStart(intent, startId)
        if (intent?.action == ACTION_STATE) {
            pushState(intent.getBooleanExtra(EXTRA_PLAYING, playing))
            // 通知还在前台，刷新图标
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    private fun send(action: String) {
        sendBroadcast(Intent(action))
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPause = if (playing) ACTION_PAUSE else ACTION_PLAY
        val playPauseIcon =
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifBlank { getString(R.string.app_name) })
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(playPauseIcon, getString(R.string.player_play), pi(playPause))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.player_error_close), pi(ACTION_STOP)
            )
            .setStyle(MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0))
            .build()
        return notif
    }

    private fun pi(action: String): PendingIntent =
        PendingIntent.getBroadcast(this, action.hashCode(), Intent(action), PendingIntent.FLAG_IMMUTABLE)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
