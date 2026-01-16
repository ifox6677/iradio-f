package com.zhangjq0908.iradio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import androidx.lifecycle.lifecycleScope


class GroupFragment : Fragment() {
    
    companion object {
        private const val ARG_LIST = "stations"

        fun newInstance(stations: List<Station>): GroupFragment {
            return GroupFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_LIST, JSONArray(stations.map {
                        mapOf(
                            "name" to it.name,
                            "imageUrl" to it.imageUrl,
                            "iurl" to it.iurl,
                            "category" to it.category
                        )
                    }).toString())
                }
            }
        }
    }

    private lateinit var stations: List<Station>
    private var adapter: Adapter? = null
    private var recyclerView: RecyclerView? = null
    
    // 显式声明 lambda 类型
    private val playerStateListener: (Boolean, String?) -> Unit = { isPlaying, playingUrl ->
        // 在主线程更新UI
        activity?.runOnUiThread {
            adapter?.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 解析参数
        val stationsJson = arguments?.getString(ARG_LIST) ?: "[]"
        
        val arr = JSONArray(stationsJson)
        stations = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Station(
                o.optString("name"),
                o.optString("imageUrl"),
                o.optString("iurl"),
                o.optString("category")
            )
        }
        
        // 注册全局播放状态监听器
        GlobalPlayerState.addListener(playerStateListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = Adapter(stations).also { 
                this@GroupFragment.adapter = it 
            }
            setHasFixedSize(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // 确保UI状态是最新的
        adapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除监听器，防止内存泄漏
        GlobalPlayerState.removeListener(playerStateListener)
        adapter = null
        recyclerView = null
    }

    /* --------------------- Adapter --------------------- */
    private inner class Adapter(private val data: List<Station>) :
        RecyclerView.Adapter<Adapter.Holder>() {
    
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
            val btn: MaterialButton = v.findViewById(R.id.playBtn)
        }
    
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_station_grid, parent, false)
            )
        }
    
        override fun getItemCount(): Int = data.size
    
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val station = data[position]
            val url = station.iurl
            
            // 只允许「无第二页」的电台显示播放状态
            val allowPlayState = isStream(url) || isYouTubeVideo(url)
            
            // 使用全局状态判断是否正在播放
            val isThisPlaying = allowPlayState && 
                GlobalPlayerState.isPlaying && 
                station.iurl == GlobalPlayerState.playingUrl
            
            // 更新UI状态
            updateUI(holder, station, isThisPlaying)
            
            // 设置点击监听器
            setupClickListeners(holder, station, allowPlayState, isThisPlaying)
        }
        
        private fun updateUI(holder: Holder, station: Station, isPlaying: Boolean) {
            // 播放按钮图标
            holder.btn.setIconResource(
                if (isPlaying) R.drawable.ic_playing
                else R.drawable.ic_play
            )
            
            // 背景
            holder.itemView.setBackgroundResource(
                if (isPlaying) R.drawable.bg_playing
                else R.drawable.bg_normal
            )
            
            // 电台名称
            holder.name.text = station.name
            
            // 图片
            Glide.with(holder.itemView.context)
                .load(station.imageUrl)
                //.placeholder(R.drawable.ic_radio_placeholder)
                //.error(R.drawable.ic_radio_error)
                .into(holder.img)
        }
        
        private fun setupClickListeners(
            holder: Holder, 
            station: Station, 
            allowPlayState: Boolean,
            isCurrentlyPlaying: Boolean
        ) {
            val clickListener = View.OnClickListener { view ->
                val context = view.context
                val url = station.iurl
                
                // 如果允许播放状态，处理播放/停止逻辑
                if (allowPlayState) {
                    // 再次点击同一个正在播放的电台 → 停止播放
                    if (isCurrentlyPlaying) {
                        stopAllPlayback(context)
                        GlobalPlayerState.stop()
                        notifyDataSetChanged()
                        return@OnClickListener
                    }
                    
                    // 停止当前播放
                    stopAllPlayback(context)
                    
                    // 设置新的播放状态
                    GlobalPlayerState.setPlaying(url)
                    
                    // 更新UI
                    notifyDataSetChanged()
                }
                
                // 根据URL类型执行不同的播放逻辑
                handlePlaybackByUrlType(context, station, url)
            }
            
            // 设置点击监听器
            holder.img.setOnClickListener(clickListener)
            holder.btn.setOnClickListener(clickListener)
            holder.itemView.setOnClickListener(clickListener)
        }
    }

    /* ---------------- 停止所有播放 ---------------- */
    private fun stopAllPlayback(context: Context) {
        try {
            // 1. 停止 ExoPlayer 流媒体播放
            context.startService(
                Intent(context, PlayerService::class.java).apply {
                    action = PlayerService.ACTION_STOP
                }
            )
            
            // 2. 停止 YouTube/WebView 播放
            WebViewPlaybackService.stop(context)
            
            // 3. 停止 Web 电台（广播方式）
            context.sendBroadcast(
                Intent("STOP_WEB_RADIO").apply {
                    `package` = context.packageName
                }
            )
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /* ---------------- 根据URL类型处理播放 ---------------- */
    private fun handlePlaybackByUrlType(context: Context, station: Station, url: String) {
        when {
            // YouTube RSS → 播客页面
            isYouTubeRss(url) -> {
                context.startActivity(
                    Intent(context, PodcastActivity::class.java).apply {
                        putExtra("rss_url", url)
                        putExtra("title", station.name)
                        putExtra("station_image_url", station.imageUrl)
                    }
                )
            }
            
            // YouTube 单视频或直播
            isYouTubeVideo(url) -> {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        //YTPlayer.ytPlay(context, url)
						YTPlayer.ytPlay(requireContext(), url)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        activity?.runOnUiThread {
                            // 可以显示错误提示
                        }
                    }
                }
            }
            
            // 普通 Podcast RSS
            isPodcastUrl(url) -> {
                context.startActivity(
                    Intent(context, PodcastActivity::class.java).apply {
                        putExtra("rss_url", url)
                        putExtra("title", station.name)
                        putExtra("station_image_url", station.imageUrl)
                    }
                )
            }
            
            // 流媒体
            isStream(url) -> playStream(station)
            
            // 网页电台
            else -> playWebRadio(station)
        }
    }

    /* ---------------- 播放直播流 ---------------- */
    private fun playStream(station: Station) {
        try {
            val context = requireContext()
            
            if (station.iurl.endsWith(".pls", ignoreCase = true)) {
            
                // 使用受控线程池（避免无限创建线程）
                PlsExecutor.executor.execute {
                    try {
                        // ⏱️ 带超时 + 限制条目数
                        val realUrl = PlayerService
                            .fetchPlsUrlsSafe(
                                plsUrl = station.iurl,
                                maxEntries = 5,
                                timeoutMs = 8000
                            )
                            .firstOrNull()
                            ?: station.iurl
            
                        context.startService(
                            Intent(context, PlayerService::class.java).apply {
                                action = PlayerService.ACTION_PLAY
                                putExtra(PlayerService.EXTRA_STREAM_URL, realUrl)
                                putExtra(PlayerService.EXTRA_TITLE, station.name)
                            }
                        )
            
                    } catch (e: Exception) {
                        e.printStackTrace()
            
                        // ⛑️ 兜底：直接尝试原始 URL
                        context.startService(
                            Intent(context, PlayerService::class.java).apply {
                                action = PlayerService.ACTION_PLAY
                                putExtra(PlayerService.EXTRA_STREAM_URL, station.iurl)
                                putExtra(PlayerService.EXTRA_TITLE, station.name)
                            }
                        )
                    }
                }
            
            
            } else {
                // 直接播放流媒体
                context.startService(
                    Intent(context, PlayerService::class.java).apply {
                        action = PlayerService.ACTION_PLAY
                        putExtra(PlayerService.EXTRA_STREAM_URL, station.iurl)
                        putExtra(PlayerService.EXTRA_TITLE, station.name)
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /* ---------------- 播放网页电台 ---------------- */
    private fun playWebRadio(station: Station) {
        try {
            WebViewActivity.play(requireContext(), station.iurl, station.name)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /* -------------------- URL类型判断函数 -------------------- */
    
    /* 普通 Podcast RSS */
    private fun isPodcastUrl(url: String): Boolean =
        url.contains(".xml", ignoreCase = true) ||
        url.contains("rss", ignoreCase = true) ||
        url.contains("feed", ignoreCase = true) ||
        url.contains("podcast", ignoreCase = true)

    /* YouTube RSS */
    private fun isYouTubeRss(url: String): Boolean =
        url.lowercase().contains("youtube.com/feeds/videos.xml")

    /* YouTube 单视频 */
    private fun isYouTubeVideo(url: String): Boolean {
        val u = url.lowercase()
        if (isYouTubeRss(url)) return false
        return (u.contains("youtube.com/watch") || u.contains("youtu.be/"))
    }

    /* 流媒体 */
    private fun isStream(url: String): Boolean {
        val l = url.lowercase()
        
        // 排除 Web 专用网址
        if (l.contains("www.881903.com") ||
            l.contains("xyzfm.space") ||
            l.contains("metroradio.com.hk")) {
            return false
        }

        // 检查流媒体扩展名
        val playableExt = listOf(".m3u8", ".mp3", ".aac", ".pls", ".m3u", ".wav", ".flac")
        if (playableExt.any { l.contains(it) }) return true

        // 检查流媒体关键词
        val keywords = listOf("/live", "/stm", "/stream", "/listen")
        if (keywords.any { l.contains(it) }) return true

        // 检查 IP:Port 格式
        val ipPortRegex = Regex("""\d{1,3}(\.\d{1,3}){3}:\d+""")
        if (ipPortRegex.containsMatchIn(l)) return true

        return false
    }
    
    /* -------------------- 其他功能 -------------------- */
    
    // 获取当前显示的电台列表
    fun getStations(): List<Station> = stations
    
    // 刷新列表（如果需要动态更新电台）
    fun updateStations(newStations: List<Station>) {
        stations = newStations
        adapter = Adapter(newStations)
        recyclerView?.adapter = adapter
        adapter?.notifyDataSetChanged()
    }
}