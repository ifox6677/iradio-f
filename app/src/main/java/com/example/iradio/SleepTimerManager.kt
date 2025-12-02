package com.example.iradio

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class SleepTimerManager(
    private val context: Context,
    private val uiContext: CoroutineContext,
    private val onTick: (String?) -> Unit,     // 每秒回调 UI 更新
    private val onFinish: () -> Unit           // 时间结束回调
) {

    private var timerJob: Job? = null
    private var remainingSeconds: Int = 0

    fun start(minutes: Int) {
        cancel()

        remainingSeconds = minutes * 60
        Toast.makeText(context, "定时 $minutes 分钟后停止播放", Toast.LENGTH_SHORT).show()

        timerJob = CoroutineScope(uiContext).launch {
            while (remainingSeconds > 0) {
                val mm = remainingSeconds / 60
                val ss = remainingSeconds % 60
                val timeStr = "%02d:%02d".format(mm, ss)

                onTick(timeStr)
                sendOverlayBroadcast(timeStr)

                delay(1000L)
                remainingSeconds--
            }

            // 倒计时结束
            stopAllPlayback()
            onTick(null)
            onFinish()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        remainingSeconds = 0
        onTick(null)
        sendOverlayBroadcast("")
        Toast.makeText(context, "已取消定时器", Toast.LENGTH_SHORT).show()
    }

    private fun sendOverlayBroadcast(time: String) {
        context.sendBroadcast(Intent("UPDATE_OVERLAY_TIMER").apply {
            putExtra("timeStr", time)
        })
    }

    private fun stopAllPlayback() {
        context.startService(
            Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_STOP
            }
        )
		WebViewPlaybackService.stop(context)
		context.sendBroadcast(Intent("DESTROY_WEBVIEW"))
		context.sendBroadcast(Intent("DESTROY_PODCAST"))

    }
}
