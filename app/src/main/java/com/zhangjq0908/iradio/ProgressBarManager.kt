package com.zhangjq0908.iradio

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class ProgressBarManager(
    private val context: Context,
    private val colorPrimary: Int,
    private val colorSecondary: Int,
    private val colorAccent: Int,
    private val colorSurface: Int,
    private val colorOnPrimary: Int,
    private val colorOnSecondary: Int,
    private val colorOnSurface: Int,
    private val colorPlaying: Int,
    private val colorDivider: Int,
    private val colorProgressBackground: Int,
    private val colorProgress: Int
) {
    
    private var currentProgress: Long = 0L
    private var totalDuration: Long = 0L
    var seekBar: SeekBar? = null
    var currentTimeText: TextView? = null
    var totalTimeText: TextView? = null
    private var isSeeking = false
    private var seekToPosition: Long = 0L
    
    // 监听器回调
    var onSeekListener: ((Long) -> Unit)? = null
    var onSeekStartListener: (() -> Unit)? = null
    var onSeekEndListener: (() -> Unit)? = null
    
    // 扩展函数：dp转px
    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()
    
    fun setupProgressBar(seekBar: SeekBar, currentTimeText: TextView, totalTimeText: TextView) {
        this.seekBar = seekBar
        this.currentTimeText = currentTimeText
        this.totalTimeText = totalTimeText
        
        // 配置SeekBar
        seekBar.apply {
            progress = 0
            max = 1000 // 使用1000作为最大值，提高精度
            progressDrawable = createSeekBarProgressDrawable()
            thumb = createSeekBarThumb()
            
            // 设置拖动监听
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && totalDuration > 0) {
                        // 计算对应的播放位置
                        val position = (progress.toLong() * totalDuration / 1000L)
                        seekToPosition = position
                        
                        // 更新当前时间显示
                        updateCurrentTimeDisplay(position)
                        
                        // 显示跳转提示
                        showSeekHint(position)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    isSeeking = true
                    onSeekStartListener?.invoke()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    isSeeking = false
                    if (totalDuration > 0 && seekToPosition > 0) {
                        onSeekListener?.invoke(seekToPosition)
                        onSeekEndListener?.invoke()
                    }
                }
            })
        }
    }
    
    // 创建进度条Drawable
    private fun createSeekBarProgressDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f
            setColor(colorProgressBackground)
        }
    }

    // 创建进度条滑块
    private fun createSeekBarThumb(): android.graphics.drawable.Drawable {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorAccent)
            setSize(16.dpToPx(), 16.dpToPx())
        }
        return drawable
    }
    
    // 更新进度条
    fun updateProgressBar(currentPosition: Long, totalDuration: Long) {
        // 如果正在拖动，不自动更新进度
        if (isSeeking) return
        
        this.currentProgress = currentPosition
        this.totalDuration = totalDuration
        
        Handler(Looper.getMainLooper()).post {
            seekBar?.let {
                if (totalDuration > 0) {
                    val progress = (currentPosition * 1000 / totalDuration).toInt()
                    it.progress = progress.coerceIn(0, 1000)
                } else {
                    it.progress = 0
                }
            }
        }
    }
    
    // 更新时间显示
    fun updateTimeDisplay(currentPosition: Long, totalDuration: Long) {
        // 如果正在拖动，不自动更新时间显示
        if (isSeeking) return
        
        Handler(Looper.getMainLooper()).post {
            currentTimeText?.text = formatTime(currentPosition)
            totalTimeText?.text = formatTime(totalDuration)
        }
    }
    
    // 更新当前时间显示（用于拖动时）
    private fun updateCurrentTimeDisplay(position: Long) {
        Handler(Looper.getMainLooper()).post {
            currentTimeText?.text = formatTime(position)
        }
    }
    
    // 格式化时间显示
    fun formatTime(milliseconds: Long): String {
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
    
    // 显示跳转提示
    private fun showSeekHint(position: Long) {
        val timeText = formatTime(position)
        showQuickToast("跳转到: $timeText", Toast.LENGTH_SHORT)
    }
    
    private fun showQuickToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        Handler(Looper.getMainLooper()).post {
            val toast = Toast.makeText(context, message, duration)
            val view = toast.view ?: return@post
            
            view.apply {
                background = createRoundedDrawable(16f, colorSecondary)
                setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
                (findViewById<TextView>(android.R.id.message))?.apply {
                    setTextColor(colorOnSecondary)
                    textSize = 14f
                    gravity = Gravity.CENTER
                }
            }
            
            toast.show()
        }
    }
    
    private fun createRoundedDrawable(radius: Float, color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(color)
        drawable.cornerRadius = radius
        drawable.setStroke(1.dpToPx(), colorDivider)
        return drawable
    }
    
    // 重置进度条
    fun reset() {
        currentProgress = 0L
        totalDuration = 0L
        isSeeking = false
        seekToPosition = 0L
        
        Handler(Looper.getMainLooper()).post {
            seekBar?.progress = 0
            currentTimeText?.text = "--:--"
            totalTimeText?.text = "--:--"
        }
    }
}