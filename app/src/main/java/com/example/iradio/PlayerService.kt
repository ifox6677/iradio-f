package com.zhangjq0908.iradio

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
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
import java.net.URL

class PlayerService : Service() {

    companion object {
        const val ACTION_PLAY = "PLAY"
        const val ACTION_STOP = "STOP"
        const val EXTRA_STREAM_URL = "url"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "iradio_play"
        private const val NOTIF_ID = 1234
        private const val TAG = "PlayerService"
	    private var playerInstance: ExoPlayer? = null
        private var currentUrl: String? = null
	    // ===== 播放守护（Watchdog） =====
        private val watchdogHandler = Handler(Looper.getMainLooper())
        private val WATCHDOG_INTERVAL = 10_000L // 10 秒一次（可调）
        @Volatile
        private var userStopped = false
		

        @Volatile
        var lastPlayingTitle: String? = null

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
                body.lines()
                    .map { it.trim() }
                    .filter { it.startsWith("File", true) && it.contains("=") }
                    .mapNotNull { it.substringAfter("=").trim().ifEmpty { null } }
            } catch (e: Exception) {
                emptyList()
            }
        }
        fun fetchPlsUrlsSafe(
            plsUrl: String,
            maxEntries: Int = 5,
            timeoutMs: Long = 8000
        ): List<String> {
    
            val urls = mutableListOf<String>()
    
            val conn = URL(plsUrl).openConnection().apply {
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
            }
    
            conn.getInputStream().bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (urls.size >= maxEntries) break
    
                    val l = line.trim()
                    if (l.startsWith("File", ignoreCase = true)) {
                        val idx = l.indexOf("=")
                        if (idx > 0) {
                            val u = l.substring(idx + 1).trim()
                            if (u.startsWith("http")) {
                                urls.add(u)
                            }
                        }
                    }
                }
            }
    
            return urls
        }
    }		
		

    private lateinit var audioFocusManager: AudioFocusManager
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val bgExecutor = Executors.newSingleThreadExecutor()

    // 播放重试
    private var retryCount = 0
    private val MAX_RETRY = 5

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20000, TimeUnit.SECONDS) // ★ 关键：永不主动超时
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .pingInterval(30, TimeUnit.SECONDS) // ★ 防 NAT / 路由器断链
            .build()
    }

    private val dataSourceFactory by lazy {
        OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(
                mapOf("User-Agent" to "Mozilla/5.0", "Icy-Metadata" to "1")
            )
    }

    // 当前播放标题
    private var currentTitle: String? = null

    private fun getPlayer(): ExoPlayer =
        playerInstance ?: ExoPlayer.Builder(this).apply {

            setTrackSelector(
                DefaultTrackSelector(this@PlayerService).apply {
                    parameters = buildUponParameters()
                        .setMaxVideoSize(0, 0)
                        .build()
                }
            )

            setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(25_000, 50_000, 1_500, 2_500)
                    .build()
            )

            setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        }.build().also { player ->
            playerInstance = player
            audioFocusManager.setPlayer(player)

            // 统一监听
            player.addListener(object : Player.Listener {

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "Player error: ${error.errorCode}", error)
                    if (isRecoverable(error) && retryCount < MAX_RETRY) {
                        retryCount++
                        retryPlay()
                    } else {
                        Log.e(TAG, "Unrecoverable error, stop")
                        stopPlayer()
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        retryCount = 0
                        // ✅ 仅在缓冲完成可播放时广播
                        currentTitle?.let { title ->
                            lastPlayingTitle = title
                            sendBroadcast(Intent("NOW_PLAYING_UPDATE").apply {
                                putExtra("stationName", title)
                                setPackage(packageName)
                            })
                        }
                    }
                }
            })
        }

    override fun onCreate() {
        super.onCreate()
        audioFocusManager = AudioFocusManager(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification("加载中…"))
		
		watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
			    userStopped = false
                val rawUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""

                if (rawUrl == currentUrl) return START_STICKY

                stopPlayerInternal()
                currentUrl = rawUrl
                currentTitle = title
                lastPlayingTitle = title

                bgExecutor.submit {
                    val finalUrl =
                        if (rawUrl.endsWith(".pls", true))
                            fetchPlsUrls(rawUrl).firstOrNull() ?: rawUrl
                        else rawUrl

                    mainHandler.post {
                        playStream(finalUrl, title)
                    }
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

        WebViewPlaybackService.stop(this)

        val mediaItem = MediaItem.fromUri(url)
        val source =
            if (url.contains(".m3u8", true))
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem)
            else
                DefaultMediaSourceFactory(dataSourceFactory)
                    .createMediaSource(mediaItem)

        p.setMediaSource(source)
        p.prepare()

        audioFocusManager.requestFocus()//{
        p.playWhenReady = true
        currentUrl = url

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, createNotification(title))
        //}
    }

    private fun retryPlay() {
        Log.w(TAG, "Retry play $retryCount/$MAX_RETRY")
        mainHandler.postDelayed({
            currentUrl?.let { playStream(it, currentTitle ?: "") }
        }, 3_000)
    }
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val p = playerInstance
    
            if (!userStopped && p != null && currentUrl != null) {
    
                val badState =
                    !p.isPlaying &&
                    p.playWhenReady &&
                    p.playbackState != Player.STATE_BUFFERING
    
                if (badState) {
                    Log.w(
                        TAG,
                        "⚠️ Watchdog detected stalled player, force reconnect"
                    )
                    retryPlay()
                }
            }
    
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL)
        }
    }
	

    private fun isRecoverable(e: PlaybackException): Boolean {
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> true
            else -> false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(title: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, PlayerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPi = PendingIntent.getActivity(
            this, 0,
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
                CHANNEL_ID,
                "iRadio 播放服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }

            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
    private fun stopPlayerInternal() {
        audioFocusManager.abandonFocus()
        playerInstance?.apply {
            stop()
            clearMediaItems()
            playWhenReady = false
        }
        currentUrl = null
    }

    private fun stopPlayer() {
        userStopped = true   // ✅ 用户主动停止	    
        stopPlayerInternal()
        lastPlayingTitle = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)

        stopSelf()
    }

    override fun onDestroy() {
	    watchdogHandler.removeCallbacks(watchdogRunnable)
        audioFocusManager.abandonFocus()
        playerInstance?.release()
        playerInstance = null
        super.onDestroy()
    }
	
}
