package com.zhangjq0908.iradio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.PlaybackException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers


class WebViewPlaybackService : Service() {

    companion object {
        private const val TAG = "WebViewPlaybackService"
        private const val CHANNEL_ID = "web_radio_channel"
        private const val NOTIFICATION_ID = 1001

        // 动作常量
        const val ACTION_STOP = "com.example.iradio.ACTION_STOP"
        const val ACTION_PLAY_STREAM = "com.example.iradio.PLAY_STREAM"
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.iradio.ACTION_TOGGLE_PLAY_PAUSE"
        const val ACTION_SEEK_TO = "com.example.iradio.ACTION_SEEK_TO"
        const val ACTION_RESET_PLAYER = "com.example.iradio.ACTION_RESET_PLAYER"
		const val ACTION_PLAY_LOCAL = "com.example.iradio.ACTION_PLAY_LOCAL"


        // 额外数据键
        const val EXTRA_TITLE = "title"
        const val EXTRA_URL = "url"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_REFERER = "extra_referer"

        // 来源标识常量
        const val SOURCE_PODCAST = "podcast"
        const val SOURCE_WEBVIEW = "webview"

        // 状态变量
        private var isStreamPlaying = false
        private var currentPlayerState = Player.STATE_IDLE
        private var currentReferer: String? = null
        private var currentSourceUrl: String? = null // 当前播放的源URL

        // 获取当前播放源
        @JvmStatic
        fun getCurrentSource(): String? = currentSourceUrl
        @JvmStatic
        fun setCurrentSource(url: String?) {
            currentSourceUrl = url
        }		

        // 检查播放状态
        @JvmStatic
        fun isPlaying(): Boolean {
            return isStreamPlaying ||
                   currentPlayerState == Player.STATE_READY ||
                   currentPlayerState == Player.STATE_BUFFERING
        }


        // 重置播放器
        @JvmStatic
        fun resetPlayer(context: Context) {
            val intent = Intent(context, WebViewPlaybackService::class.java).apply {
                action = ACTION_RESET_PLAYER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // 停止播放
        @JvmStatic
        fun stop(context: Context) {
            val intent = Intent(context, WebViewPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        // 切换播放/暂停
        @JvmStatic
        fun togglePlayPause(context: Context) {
            val intent = Intent(context, WebViewPlaybackService::class.java).apply {
                action = ACTION_TOGGLE_PLAY_PAUSE
            }
            context.startService(intent)
        }

        // 跳转到指定位置
        @JvmStatic
        fun seekTo(context: Context, position: Long) {
            val intent = Intent(context, WebViewPlaybackService::class.java).apply {
                action = ACTION_SEEK_TO
                putExtra("position", position)
            }
            context.startService(intent)
        }

        // WebView调用的方法
        @JvmStatic
        fun playStreamFromWebView(context: Context, url: String, title: String) {
            playStreamInternal(context, url, title, SOURCE_WEBVIEW, true)
        }

        // Podcast调用的方法
        @JvmStatic
        fun playStreamFromPodcast(context: Context, url: String, title: String) {
            playStreamInternal(context, url, title, SOURCE_PODCAST, false)
        }

        // 保持原有API
        @JvmStatic
        fun playStream(context: Context, url: String, title: String) {
            playStreamInternal(context, url, title, SOURCE_PODCAST, false)
        }

        // 内部播放方法
        private fun playStreamInternal(context: Context, url: String, title: String, source: String, skipAudioFocus: Boolean) {
            currentSourceUrl = url // 记录当前播放源
            
            val intent = Intent(context, WebViewPlaybackService::class.java).apply {
                action = ACTION_PLAY_STREAM
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SOURCE, source)
                putExtra("skip_audio_focus", skipAudioFocus)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        var onPlaybackEnded: (() -> Unit)? = null
    }

    // 实例变量
    private var player: ExoPlayer? = null
    private var currentTitle: String? = null
    private var currentUrl: String? = null
    private lateinit var audioFocusManager: AudioFocusManager
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000) // 每秒更新一次
        }
    }
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var source: String = SOURCE_PODCAST
    private lateinit var localBroadcastManager: LocalBroadcastManager

    override fun onCreate() {
        super.onCreate()
        localBroadcastManager = LocalBroadcastManager.getInstance(this)
        audioFocusManager = AudioFocusManager(this)

    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        
        return when (intent?.action) {
            ACTION_STOP -> {
                stopProgressUpdates()
                resetPlaybackState()
                audioFocusManager.abandonFocus()
                stopPlayback()
                stopSelf()
                sendBroadcast(Intent("STOP_WEB_RADIO"))
                START_NOT_STICKY
            }
            
            ACTION_RESET_PLAYER -> {
                
                resetPlayerState()
                START_STICKY
            }
            
            ACTION_TOGGLE_PLAY_PAUSE -> {
                player?.let { player ->
                    if (player.isPlaying) {
                        // 暂停播放
                        player.pause()
                        isStreamPlaying = false
                        stopProgressUpdates()
                        localBroadcastManager.sendBroadcast(Intent("PLAYBACK_PAUSED"))
                        updateNotification()
                    } else {
                        // 恢复播放
                        audioFocusManager.requestFocus {
                            player.play()
                            isStreamPlaying = true
                            startProgressUpdates()
                            localBroadcastManager.sendBroadcast(Intent("PLAYBACK_STARTED"))
                            updateNotification()
                        }
                    }
                    updatePlayerState(player.playbackState)
                } ?: run {
                    Log.w(TAG, "无法切换播放状态：播放器未初始化")
                }
                START_STICKY
            }
            
            ACTION_SEEK_TO -> {
                val position = intent.getLongExtra("position", 0L)
                player?.let { player ->
                    if (position >= 0 && position <= player.duration) {
                        player.seekTo(position)
                        val seekIntent = Intent("SEEK_COMPLETED").apply {
                            putExtra("seek_position", position)
                        }
                        localBroadcastManager.sendBroadcast(seekIntent)
                        updateProgress()
                    } else {
                        Log.w(TAG, "跳转位置无效: $position, 总时长: ${player.duration}")
                    }
                } ?: run {
                    Log.w(TAG, "无法跳转：播放器未初始化")
                }
                START_STICKY
            }
    
            ACTION_PLAY_STREAM -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "网络电台"
                source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_PODCAST
                val skipAudioFocus = intent.getBooleanExtra("skip_audio_focus", false)

                url?.let { streamUrl ->
                    currentUrl = streamUrl
                    currentTitle = title
                    currentSourceUrl = streamUrl // 确保在服务中记录
                    
                    startForegroundNotification(title)
                    
                    if (!skipAudioFocus) {
                        audioFocusManager.requestFocus {
                            playUrl(streamUrl)
                        }
                    } else {
                        playUrl(streamUrl)
                    }
                }
                START_STICKY
            }
            
            else -> {
                START_STICKY
            }
        }
    }
    
    // 重置播放器状态
    private fun resetPlayerState() {
        
        stopProgressUpdates()
        
        player?.let {
            try {
                it.stop()
                it.clearMediaItems()
                it.playWhenReady = false
                it.seekTo(0)
                
            } catch (e: Exception) {
                Log.e(TAG, "重置播放器状态失败", e)
            }
        }
        
        audioFocusManager.abandonFocus()
        currentPlayerState = Player.STATE_IDLE
        isStreamPlaying = false
        currentSourceUrl = null // 清空当前源
        
        sendPlayerStateBroadcast()
    }
    
    // 重置播放状态
    private fun resetPlaybackState() {
        isStreamPlaying = false
        currentPlayerState = Player.STATE_IDLE
        currentSourceUrl = null // 清空当前源
        sendPlayerStateBroadcast()
    }

    // 开始进度更新
    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressHandler.post(progressRunnable)
    }

    // 停止进度更新
    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    // 更新进度广播
    private fun updateProgress() {
        player?.let { player ->
            val currentPosition = player.currentPosition
            val totalDuration = player.duration
            val isPlaying = player.isPlaying
            
            if (totalDuration > 0) {
                val intent = Intent("PLAYBACK_PROGRESS").apply {
                    putExtra("current_position", currentPosition)
                    putExtra("duration", totalDuration)
                    putExtra("is_playing", isPlaying)
                }
                localBroadcastManager.sendBroadcast(intent)
                
                if (currentPosition >= totalDuration && totalDuration > 0) {
                    localBroadcastManager.sendBroadcast(Intent("PLAYBACK_STOPPED"))
                    onPlaybackEnded?.invoke()
                    resetPlayerState()
                }
            }
        }
    }

    // 发送播放状态广播
    private fun sendPlayerStateBroadcast() {
        val intent = Intent("PLAYER_STATE_CHANGED").apply {
            putExtra("state", currentPlayerState)
            putExtra("isPlaying", isStreamPlaying)
            putExtra("stateName", getStateName(currentPlayerState))
        }
        localBroadcastManager.sendBroadcast(intent)
    }
    
    // 获取状态名称
    private fun getStateName(state: Int): String {
        return when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
    }
    
    // 更新播放状态
    private fun updatePlayerState(state: Int) {
        val oldState = currentPlayerState
        currentPlayerState = state
        
        isStreamPlaying = when (state) {
            Player.STATE_READY -> player?.isPlaying == true
            Player.STATE_BUFFERING -> true
            else -> false
        }
        

        if (state == Player.STATE_ENDED) {
            resetPlayerState()
        }
        
        sendPlayerStateBroadcast()
    }

    // 播放URL
    private fun playUrl(url: String) {
        //Log.d(TAG, "开始播放URL: ${url.take(50)}...")
        PlayerService.stop(this)
        stopProgressUpdates()
        
        if (player == null) {
            player = ExoPlayer.Builder(this).build().apply {
                audioFocusManager.setPlayer(this)
                
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updatePlayerState(playbackState)

                        when (playbackState) {
                            Player.STATE_READY -> {
                                if (isPlaying) startProgressUpdates()
                                localBroadcastManager.sendBroadcast(Intent("PLAYBACK_STARTED"))
                                updateNotification()
                            }
                            Player.STATE_ENDED -> {
                                stopProgressUpdates()
                                localBroadcastManager.sendBroadcast(Intent("PLAYBACK_STOPPED"))
                                onPlaybackEnded?.invoke()
                                updateNotification()
                            }
                            Player.STATE_BUFFERING -> {
                                if (isPlaying) startProgressUpdates()
                            }
                            Player.STATE_IDLE -> {
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "播放器错误: ${error.message}")
                        localBroadcastManager.sendBroadcast(Intent("PLAYBACK_ERROR").apply {
                            putExtra("error", error.message ?: "未知错误")
                        })
                        resetPlayerState()
                    }
                    
                    override fun onVolumeChanged(volume: Float) {
                        audioFocusManager.setOriginalVolume(volume)
                        super.onVolumeChanged(volume)
                    }
                })
            }
        } else {
            audioFocusManager.setPlayer(player!!)
        }
        
        player?.apply {
            try {
                stop()
                clearMediaItems()
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                play()
                startProgressUpdates()
                
                currentUrl = url
                isStreamPlaying = true
                
                localBroadcastManager.sendBroadcast(Intent("PLAYBACK_STARTED"))
                updateNotification()
                
            } catch (e: Exception) {
                Log.e(TAG, "播放失败", e)
                localBroadcastManager.sendBroadcast(Intent("PLAYBACK_ERROR").apply {
                    putExtra("error", e.message ?: "播放失败")
                })
                resetPlayerState()
            }
        }
    }

    // 格式化时间
    private fun formatTime(milliseconds: Long): String {
        if (milliseconds <= 0) return "00:00"
        
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
    

    private fun stopPlayback() {
        
        stopProgressUpdates()
        
        try {
            player?.stop()
            player?.release()
            player = null
        } catch (e: Exception) {
            Log.e(TAG, "停止播放时出错", e)
        }

        resetPlaybackState()
        currentSourceUrl = null // 清空当前源
    }

    // 启动前台通知
    private fun startForegroundNotification(title: String) {
        createNotificationChannel()

        val openIntent = Intent(this, WebViewActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
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
        )

        val playPauseIntent = Intent(this, WebViewPlaybackService::class.java).apply {
            action = ACTION_TOGGLE_PLAY_PAUSE
        }
        val playPausePending = PendingIntent.getService(
            this, 2, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle ?: title)
            .setContentText(if (isStreamPlaying) "正在播放" else "已暂停")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .setSilent(true)
            .setOngoing(true)
            .addAction(
                if (isStreamPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isStreamPlaying) "暂停" else "播放",
                playPausePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止", 
                stopPending
            )
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // 更新通知
    private fun updateNotification() {
        currentTitle?.let {
            startForegroundNotification(it)
        }
    }

    // 创建通知通道
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
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        
        stopProgressUpdates()
        resetPlaybackState()
        audioFocusManager.abandonFocus()
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        super.onDestroy()
    }
}