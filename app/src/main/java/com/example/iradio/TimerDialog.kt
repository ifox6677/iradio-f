package com.example.iradio

import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 美观小巧的睡眠定时器弹窗
 * 自动关闭 5 秒，无取消按钮
 */
class TimerDialog(
    context: Context,
    private val presetMinutes: Array<Int>,
    private val callback: (minutes: Int) -> Unit
) : Dialog(context) {

    private val handler = Handler(Looper.getMainLooper())

    init {
        // 弹窗基本属性
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt()) // 半透明黑
            val padding = 24
            setPadding(padding, padding, padding, padding)
        }

        // 添加选项
        presetMinutes.forEach { minutes ->
            val tv = TextView(context).apply {
                text = "$minutes 分钟"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(16, 16, 16, 16)
                setOnClickListener {
                    callback.invoke(minutes)
                    dismiss()
                }
            }
            layout.addView(tv)
        }

        // 添加“关闭定时”
        val closeTv = TextView(context).apply {
            text = "关闭定时"
            setTextColor(0xFFFF5555.toInt())
            textSize = 16f
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                callback.invoke(0)
                dismiss()
            }
        }
        layout.addView(closeTv)

        setContentView(layout)

        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val params = attributes
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.CENTER
            attributes = params
        }

        // 自动关闭 5 秒
        handler.postDelayed({ dismiss() }, 5000)
    }

    override fun dismiss() {
        super.dismiss()
        handler.removeCallbacksAndMessages(null)
    }
}
