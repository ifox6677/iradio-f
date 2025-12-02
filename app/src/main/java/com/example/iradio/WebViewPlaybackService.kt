package com.example.iradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.PlaybackException

class WebViewPlaybackService : Service() {

    companion object {
        private const val TAG = "WebViewPlaybackService"
        private const val CHANNEL_ID = "web_radio_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.example.iradio.ACTION_STOP"
        const val ACTION_PLAY_STREAM = "com.example.iradio.PLAY_STREAM"

        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"

        // 外部调用播放
        fun playStream(context: android.content.Context, url: String, title: String) {
            val intent = Intent(context, WebViewPlaybackService::class.java).apply {
                action = ACTION_PLAY_STREAM
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // 外部调用停止
        fun stop(context: android.content.Context) {
            context.startService(
                Intent(context, WebViewPlaybackService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        // 播放完成回调（PodcastActivity 用）
        var onPlaybackEnded: (() -> Unit)? = null
    }

    private var player: ExoPlayer? = null
    private var currentTitle: String? = null
    private var currentUrl: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {

            // ← 修正 STOP，让 ExoPlayer 真正停止
            ACTION_STOP -> {
                stopPlayback()
                stopSelf()
                sendBroadcast(Intent("STOP_WEB_RADIO"))
                return START_NOT_STICKY
            }

            ACTION_PLAY_STREAM -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "网络电台"

                if (url != null) {
                    currentUrl = url
                    currentTitle = title

                    startForegroundNotification(title)
                    playUrl(url)
                }
            }
        }
        return START_STICKY
    }

    private fun playUrl(url: String) {
        if (player == null) {
            player = ExoPlayer.Builder(this).build()
            player!!.addListener(object : Player.Listener {

                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.e(TAG, "ExoPlayer state: $playbackState")

                    if (playbackState == Player.STATE_READY) {
                        sendBroadcast(Intent("STOP_WEB_GRAB"))
                    } else if (playbackState == Player.STATE_ENDED) {
                        Log.e(TAG, "🎯 播放完成")
                        onPlaybackEnded?.invoke()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer ERROR", error)
                }
            })
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setDefaultRequestProperties(mapOf("User-Agent" to "Mozilla/5.0"))
            setAllowCrossProtocolRedirects(true)
        }

        val mediaSource = if (url.contains(".m3u8")) {
            Log.e(TAG, "🎯 [EXOPLAY_URL] type=HLS $url")
            HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            Log.e(TAG, "🎯 [EXOPLAY_URL] type=Progressive $url")
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }

        player!!.setMediaSource(mediaSource)
        player!!.prepare()
        player!!.play()
    }

    private fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}

        player = null
    }

    private fun startForegroundNotification(title: String) {
        createNotificationChannel()

        val openIntent = Intent(this, WebViewActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WebViewPlaybackService::class.java).apply {
           action = ACTION_STOP
        }
         
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) /*
        val stopIntent = Intent("DESTROY_WEBVIEW") // 自定义广播
        val stopPending = PendingIntent.getBroadcast(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )*/		

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("ExoPlayer")
            .setSmallIcon(R.drawable.ic_play_arrow)
            .setContentIntent(openPending)
            .setSilent(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPending)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "网页电台播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
