package com.example.iradio

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// ---------- 顶层单例 ---------- //
private var playerInstance: ExoPlayer? = null
private var currentUrl: String? = null

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "PLAY"
        const val ACTION_STOP = "STOP"
        const val EXTRA_STREAM_URL = "url"
        const val EXTRA_TITLE = "title"
        private const val CHANNEL_ID = "iradio_play"
        private const val NOTIF_ID = 1234

        @JvmStatic
        fun fetchPlsUrls(plsUrl: String): List<String> {
            return try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url(plsUrl).get().build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: return emptyList()
                val urls = mutableListOf<String>()
                body.lines().forEach { line ->
                    val l = line.trim()
                    if (l.startsWith("File", true) && l.contains("=")) {
                        val u = l.substringAfter("=").trim()
                        if (u.isNotEmpty()) urls.add(u)
                    }
                }
                urls
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val dataSourceFactory by lazy {
        OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(
                mapOf("User-Agent" to "Mozilla/5.0", "Icy-Metadata" to "1")
            )
    }

    private fun getPlayer(): ExoPlayer = playerInstance ?: ExoPlayer.Builder(this).apply {
        setTrackSelector(
            DefaultTrackSelector(this@PlayerService).apply {
                parameters = buildUponParameters().setMaxVideoSize(0, 0).build()
            }
        )
        setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(25000, 50000, 1500, 2500)
                .build()
        )
        setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
    }.build().also { playerInstance = it }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 启动前台服务，保证通知显示（初始空标题）
        startForeground(NOTIF_ID, createNotification("加载中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val rawUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

                if (rawUrl == currentUrl) return START_STICKY
                stopPlayerInternal() // 停掉旧流
                currentUrl = rawUrl

                // 异步解析 pls 或播放流
                bgExecutor.submit {
                    val finalUrl = if (rawUrl.endsWith(".pls", true))
                        fetchPlsUrls(rawUrl).firstOrNull() ?: rawUrl
                    else rawUrl
                    mainHandler.post { playStream(finalUrl, title) }
                }
            }
            ACTION_STOP -> stopPlayer()
        }
        return START_STICKY
    }

    private fun playStream(url: String, title: String) {
        val p = getPlayer()
        p.stop()
        p.clearMediaItems()
        p.playWhenReady = false

        val mediaItem = MediaItem.fromUri(url)
        val source = if (url.contains(".m3u8", true))
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        else
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)

        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true

        currentUrl = url

        // 更新通知栏显示电台名
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, createNotification(title))

        // 广播给 MainActivity 更新播放状态
        sendBroadcast(Intent("NOW_PLAYING_UPDATE").apply {
            putExtra("stationName", title)
            setPackage(packageName)
        })
    }

    private fun stopPlayerInternal() {
        playerInstance?.let {
            it.stop()
            it.clearMediaItems()
            it.playWhenReady = false
        }
        currentUrl = null
    }

    private fun stopPlayer() {
        stopPlayerInternal()

        // 停止前台服务并移除通知
        stopForeground(STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)

        // 广播给 MainActivity 显示未播放
        sendBroadcast(Intent("NOW_PLAYING_UPDATE").apply {
            putExtra("stationName", "未播放")
            setPackage(packageName)
        })

        stopSelf()
    }

    override fun onDestroy() {
        playerInstance?.release()
        playerInstance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 通知栏 ---------- //
    private fun createNotification(title: String): Notification {
        val stopPi = PendingIntent.getService(
            this, System.currentTimeMillis().toInt(),
            Intent(this, PlayerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "iRadio" })
            .setContentText("正在播放…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "iRadio 播放服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
