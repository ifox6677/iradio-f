package com.example.iradio

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.DefaultLoadControl
import com.google.android.exoplayer2.DefaultRenderersFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.google.android.exoplayer2.source.MediaSource


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
                    if (l.startsWith("File", ignoreCase = true) && l.contains("=")) {
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

    @Volatile private var player: ExoPlayer? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val retryCounter = AtomicInteger(0)
    private val MAX_RETRY = 5

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val dataSourceFactory by lazy {
        OkHttpDataSource.Factory(okHttpClient).apply {
            setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "Mozilla/5.0",
                    "Icy-Metadata" to "1"
                )
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initPlayer()
    }

    private fun initPlayer() {
        if (player != null) return

        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters().setMaxVideoSize(0, 0).build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(25000, 50000, 1500, 2500)
            .build()

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioOffload(false)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(this).build())
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {

                                val tries = retryCounter.incrementAndGet()
                                if (tries <= MAX_RETRY) {
                                    val delayMs = (1000L * Math.pow(2.0, (tries - 1).toDouble())).toLong()
                                    mainHandler.postDelayed({
                                        try {
                                            if (exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) {
                                                exo.prepare()
                                            }
                                            exo.playWhenReady = true
                                        } catch (_: Exception) {}
                                    }, delayMs)
                                } else {
                                    retryCounter.set(0)
                                }
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                            retryCounter.set(0)
                        }
                    }
                })
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val rawUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

                bgExecutor.submit {
                    val finalUrl = if (rawUrl.endsWith(".pls", ignoreCase = true)) {
                        val list = fetchPlsUrls(rawUrl)
                        list.firstOrNull() ?: rawUrl
                    } else rawUrl

                    mainHandler.post { playStream(finalUrl, title) }
                }
            }
            ACTION_STOP -> stopCompletely()
        }
        return START_STICKY
    }

    private fun playStream(url: String, title: String) {
        sendBroadcast(Intent("STOP_WEB_RADIO"))

        val p = player ?: run { initPlayer(); player!! }

        try {
            p.playWhenReady = false
            p.stop()
            p.clearMediaItems()

            val mediaItem = MediaItem.fromUri(url)
            val mediaSource: MediaSource =
                if (url.contains(".m3u8", true)) {
                    HlsMediaSource.Factory(dataSourceFactory)
                        .setAllowChunklessPreparation(true)
                        .setUseSessionKeys(true)
                        .createMediaSource(mediaItem)
                } else {
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
                }

            p.setMediaSource(mediaSource)
            p.prepare()
            p.playWhenReady = true

            startForeground(NOTIF_ID, createNotification(title))

            // =======================================
            //  唯一修改点：显式广播，完全兼容 Android 12+
            // =======================================
            sendBroadcast(
                Intent("NOW_PLAYING_UPDATE").apply {
                    setPackage(packageName)      // ⭐ 修复主界面无法接收的问题
                    putExtra("stationName", title)
                }
            )

        } catch (_: Exception) {}
    }

    private fun createNotification(title: String): Notification {
        val stopIntent = Intent(this, PlayerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPending = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title.ifEmpty { "iRadio" })
            .setContentText("正在播放…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "iRadio 播放服务", NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun stopCompletely() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { player?.run { playWhenReady = false; stop(); release() } } catch (_: Exception) {}
        player = null

        // =======================================
        // 同样加 setPackage
        // =======================================
        sendBroadcast(
            Intent("NOW_PLAYING_UPDATE").apply {
                setPackage(packageName)
                putExtra("stationName", "未播放")
            }
        )

        stopSelf()
    }

    override fun onDestroy() {
        stopCompletely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
