package com.zhangjq0908.iradio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
//import android.util.Log
import com.google.android.exoplayer2.ExoPlayer

/**
 * AudioFocusManager
 * - 管理 ExoPlayer 的音频焦点
 * - 支持延迟焦点自动恢复播放
 * - 支持临时失去焦点静音或降低音量（duck）
 * - 支持永久失去焦点停止播放
 * - 支持绑定 Player 并自动同步 UI 状态
 */
class AudioFocusManager(
    private val context: Context
) {

    private var audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var player: ExoPlayer? = null

    private var originalVolume: Float = 1f
    private var focusLostTime: Long = 0L
    private val MAX_LOST_DURATION = 10 * 60 * 1000L // 10 分钟

    private var isFocusGranted = false
    private var isDucking = false
    private var pendingVolume: Float = 1f

    private var pendingPlayAction: (() -> Unit)? = null // 延迟播放
    private var hasCalledPendingPlay = false
	// ===== 播放守护（Watchdog） =====



    /**
     * 绑定 ExoPlayer
     */
    fun setPlayer(player: ExoPlayer) {
        this.player = player
        this.originalVolume = player.volume
        this.pendingVolume = player.volume

        // 如果当前处于 duck 状态，降低音量，否则恢复原音量
        if (isDucking) {
            player.volume = originalVolume * 0.2f
        } else {
            player.volume = pendingVolume
        }

        //Log.d("AudioFocusManager", "Player bound, volume: ${player.volume}")
    }

    fun setOriginalVolume(vol: Float) {
        originalVolume = vol
        pendingVolume = vol
        if (!isDucking) {
            player?.volume = vol
        }
    }

    /**
     * 请求音频焦点
     * @param immediateAction 焦点获取成功后立即执行的播放动作
     */
    fun requestFocus(immediateAction: (() -> Unit)? = null) {
        //Log.d("AudioFocusManager", "Requesting audio focus, already granted: $isFocusGranted")

        if (isFocusGranted) {
            immediateAction?.invoke()
            return
        }

        abandonFocus() // 释放旧焦点

        hasCalledPendingPlay = false
        pendingPlayAction = immediateAction

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(true) // 支持延迟焦点
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { focusChange ->
                //Log.d("AudioFocusManager", "Audio focus changed: $focusChange")
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // 短暂失去焦点 → 暂停播放
                        player?.pause()
                        isDucking = false
                        focusLostTime = System.currentTimeMillis()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // 可以降低音量而不是完全静音
                        isDucking = true
                        focusLostTime = System.currentTimeMillis()
                        player?.volume = originalVolume * 0.2f
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        val now = System.currentTimeMillis()
                        isDucking = false
                        isFocusGranted = true

                        if (now - focusLostTime <= MAX_LOST_DURATION) {
                            // 恢复音量
                            player?.volume = pendingVolume
                            // 如果之前有延迟播放动作，则执行
                            if (!hasCalledPendingPlay) {
                                pendingPlayAction?.invoke()
                                hasCalledPendingPlay = true
                            }
                        } else {
                            // 超过 10 分钟 → 停止播放
                            player?.stop()
                            player?.clearMediaItems()
                            sendBroadcastUpdate("未播放")
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // 永久失去焦点 → 停止播放
                        isFocusGranted = false
                        isDucking = false
                        player?.stop()
                        player?.clearMediaItems()
                        sendBroadcastUpdate("未播放")
                    }
                }
            }
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            isFocusGranted = true
            isDucking = false
            //Log.d("AudioFocusManager", "Audio focus granted immediately")
            immediateAction?.invoke()
            hasCalledPendingPlay = true
        } else {
            //Log.d("AudioFocusManager", "Audio focus request pending, result: $result")
        }
    }

    /**
     * 释放音频焦点
     */
    fun abandonFocus() {
        isFocusGranted = false
        isDucking = false
        focusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            focusRequest = null
        }
        player?.volume = pendingVolume
    }

    fun isFocusHeld(): Boolean = isFocusGranted
    fun isDucking(): Boolean = isDucking

    /**
     * 广播给 MainActivity 更新播放状态
     */
    private fun sendBroadcastUpdate(stationName: String) {
        context.sendBroadcast(Intent("NOW_PLAYING_UPDATE").apply {
            putExtra("stationName", stationName)
            setPackage(context.packageName)
        })
    }
}
