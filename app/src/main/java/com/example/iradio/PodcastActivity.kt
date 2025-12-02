package com.example.iradio

import android.content.*
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.iradio.databinding.ActivityPodcastBinding
import com.rometools.modules.itunes.EntryInformationImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class PodcastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPodcastBinding
    private val episodes = mutableListOf<Episode>()
    private lateinit var adapter: EpisodeAdapter
    private var currentPlayingIndex: Int = -1
    private var isContinuousMode = true
    private var isDescending = true

    // ---------------- 播客数据类 ----------------
    data class Episode(
        val title: String,
        val url: String,
        val duration: String,
        val pubDate: String,
        val pubDateTime: Long
    )

    // ---------------- DESTROY_PODCAST 广播 ----------------
    private val destroyPodcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // 接收到广播直接退出
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPodcastBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 注册 DESTROY_PODCAST 广播（兼容 Android 13+）
        val filter = IntentFilter("DESTROY_PODCAST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(destroyPodcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(destroyPodcastReceiver, filter)
        }

        val rssUrl = intent.getStringExtra("rss_url") ?: run { finish(); return }
        val podcastTitle = intent.getStringExtra("title") ?: "播客"
        binding.titleBar.text = podcastTitle

        // RecyclerView 初始化
        adapter = EpisodeAdapter(episodes) { ep -> playEpisode(ep) }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@PodcastActivity)
            this.adapter = this@PodcastActivity.adapter
        }

        // 排序按钮
        binding.sortButton.setOnClickListener {
            isDescending = !isDescending
            binding.sortButton.text = if (isDescending) "排序 ↓" else "排序 ↑"
            sortEpisodes()
        }

        // 播放模式按钮
        binding.playModeButton.setOnClickListener {
            isContinuousMode = !isContinuousMode
            binding.playModeButton.text = if (isContinuousMode) "连续播放" else "单集播放"
        }

        // 加载 RSS
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val feed = withContext(Dispatchers.IO) {
                try {
                    SyndFeedInput().build(XmlReader(URL(rssUrl).openStream()))
                } catch (e: Exception) {
                    Log.e("PodcastActivity", "加载失败", e)
                    null
                }
            }

            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (feed == null) {
                    Toast.makeText(this@PodcastActivity, "加载失败，请检查网络", Toast.LENGTH_LONG).show()
                    return@withContext
                }
                bindFeed(feed)
            }
        }

        // 播放完成回调
        WebViewPlaybackService.onPlaybackEnded = {
            if (isContinuousMode) playNextEpisode()
        }
    }

    // ---------------- 绑定播客信息 ----------------
    private fun bindFeed(feed: SyndFeed) {
        binding.titleText.text = feed.title?.trim() ?: "未知播客"
        binding.authorText.text = feed.author?.trim() ?: "未知作者"

        // 封面
        var coverUrl = feed.image?.url
        if (coverUrl.isNullOrBlank()) {
            feed.foreignMarkup?.forEach { element ->
                if (element.name == "image" && element.namespace.prefix == "itunes") {
                    coverUrl = element.getAttributeValue("href")
                    return@forEach
                }
            }
        }
        if (!coverUrl.isNullOrBlank()) Glide.with(this).load(coverUrl).into(binding.coverImage)

        episodes.clear()
        feed.entries?.forEach { entry ->
            val url = entry.enclosures?.firstOrNull()?.url ?: return@forEach
            val itunes = entry.modules?.filterIsInstance<EntryInformationImpl>()?.firstOrNull()
            val duration = itunes?.duration?.let {
                val s = it.milliseconds / 1000
                val h = s / 3600
                val m = (s % 3600) / 60
                val sec = s % 60
                if (h > 0) String.format("%d:%02d:%02d", h, m, sec)
                else String.format("%02d:%02d", m, sec)
            } ?: ""

            val pubDate = entry.publishedDate ?: Date()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(pubDate)

            episodes.add(Episode(entry.title?.trim() ?: "无标题", url, duration, dateStr, pubDate.time))
        }

        sortEpisodes()
        episodes.firstOrNull()?.let { playEpisode(it) }
    }

    private fun sortEpisodes() {
        if (isDescending) episodes.sortByDescending { it.pubDateTime }
        else episodes.sortBy { it.pubDateTime }
        adapter.notifyDataSetChanged()
    }

    // ---------------- 播放逻辑 ----------------
    private fun playEpisode(ep: Episode) {
        currentPlayingIndex = episodes.indexOf(ep)
        adapter.notifyDataSetChanged() // 刷新高亮
        WebViewPlaybackService.playStream(this, ep.url, "${binding.titleText.text} - ${ep.title}")
        Toast.makeText(this, "正在播放：${ep.title}", Toast.LENGTH_SHORT).show()
    }

    private fun playNextEpisode() {
        val nextIndex = currentPlayingIndex + 1
        if (nextIndex in episodes.indices) playEpisode(episodes[nextIndex])
        else currentPlayingIndex = -1
    }

    // ---------------- RecyclerView Adapter ----------------
    inner class EpisodeAdapter(
        private val list: List<Episode>,
        private val onClick: (Episode) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<EpisodeAdapter.VH>() {

        inner class VH(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(android.R.id.text1)
            val info: TextView = itemView.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(40, 35, 40, 35)
                setBackgroundResource(android.R.drawable.list_selector_background)

                ImageView(context).apply {
                    setImageResource(android.R.drawable.ic_media_play)
                    layoutParams = LinearLayout.LayoutParams(120, 120).apply { gravity = Gravity.CENTER_VERTICAL }
                    addView(this)
                }

                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setPadding(30, 0, 0, 0)

                    TextView(context).apply {
                        id = android.R.id.text1
                        textSize = 16f
                        setTextColor(-16777216)
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }.also { addView(it) }

                    TextView(context).apply {
                        id = android.R.id.text2
                        textSize = 13f
                        setTextColor(0xFF666666.toInt())
                    }.also { addView(it) }
                }.also { addView(it) }
            }
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            holder.title.text = ep.title
            holder.info.text = if (ep.duration.isNotBlank()) "${ep.pubDate}  ${ep.duration}" else ep.pubDate

            // 当前播放高亮
            if (position == currentPlayingIndex) {
                holder.itemView.setBackgroundColor(0x220711b2) // 淡蓝高亮
            } else {
                holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            holder.itemView.setOnClickListener { onClick(ep) }
        }

        override fun getItemCount() = list.size
    }

    // ---------------- 返回键逻辑 ----------------
    override fun onBackPressed() {
        WebViewPlaybackService.stop(this)
        super.onBackPressed()
    }

    // ---------------- 生命周期 ----------------
    override fun onDestroy() {
        try { unregisterReceiver(destroyPodcastReceiver) } catch (_: Exception) {}
        WebViewPlaybackService.stop(this)
        super.onDestroy()
    }
}
