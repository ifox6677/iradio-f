package com.example.iradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class NowPlayingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: "..."
        val station = intent.getStringExtra("station") ?: ""

        // 可选：全局处理
        //Toast.makeText(context, "正在播放：$title", Toast.LENGTH_SHORT).show()
    }
}
