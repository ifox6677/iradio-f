package com.zhangjq0908.iradio

import android.content.Context
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView

class TimerAdapter(
    private val times: Array<Int>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<TimerAdapter.TimerViewHolder>() {

    class TimerViewHolder(val button: Button) : RecyclerView.ViewHolder(button)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TimerViewHolder {
        val btn = Button(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                120
            )
            setBackgroundResource(R.drawable.bg_timer_button)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
        }
        return TimerViewHolder(btn)
    }

    override fun getItemCount(): Int = times.size + 1 // 多一个自定义按钮

    override fun onBindViewHolder(holder: TimerViewHolder, position: Int) {
        if (position < times.size) {
            val min = times[position]
            holder.button.text = "$min 分钟"
            holder.button.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            holder.button.setOnClickListener { onClick(min) }
        } else {
            holder.button.text = "自定义"
            holder.button.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
            holder.button.setOnClickListener { showCustomDialog(holder.button.context) }
        }
    }

    private fun showCustomDialog(context: Context) {
        val editText = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入分钟数（1-999）"
            setText("30")
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val mins = editText.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 30
                onClick(mins)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
