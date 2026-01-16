package com.zhangjq0908.iradio

import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.zhangjq0908.iradio.databinding.ActivityPodcastBinding
import kotlinx.coroutines.launch
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.zhangjq0908.iradio.model.Episode
import com.zhangjq0908.iradio.download.*
import java.io.File

class PodcastActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPodcastBinding
    private val episodes = mutableListOf<Episode>()
    private lateinit var adapter: EpisodeAdapter
    private var currentPlayingIndex: Int = -1
    private var isContinuousMode = true
    private var isDescending = true
    private var stationImageUrl: String? = null

    // 播放状态
    private var isPlaying: Boolean = false
    private var currentEpisode: Episode? = null
    private var downloadFinishReceiver: BroadcastReceiver? = null

    // 进度条管理器
    private lateinit var progressBarManager: ProgressBarManager
	private var totalDuration: Long = 0L
    
    // RSS解析器
    private val rssParser = RssParser()

    // 颜色定义
    private val colorPrimary = Color.parseColor("#1E1E2E")
    private val colorSecondary = Color.parseColor("#2D2D3F")
    private val colorAccent = Color.parseColor("#6366F1")
    private val colorSurface = Color.parseColor("#242434")
    private val colorOnPrimary = Color.parseColor("#FFFFFF")
    private val colorOnSecondary = Color.parseColor("#E0E0E0")
    private val colorOnSurface = Color.parseColor("#CCCCCC")
    private val colorPlaying = Color.parseColor("#3B3B58")
    private val colorDivider = Color.parseColor("#333344")
    private val colorProgressBackground = Color.parseColor("#3A3A4D")
    private val colorProgress = Color.parseColor("#6366F1")
	

    private val destroyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    // 播放状态接收器
    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "PLAYBACK_PROGRESS" -> {
                    val currentPosition = intent.getLongExtra("current_position", 0L)
                    val newTotalDuration = intent.getLongExtra("duration", 0L)
                    
                    // 更新总时长
                    totalDuration = newTotalDuration
                    
                    // 更新进度条
                    progressBarManager.updateProgressBar(currentPosition, newTotalDuration)
                    progressBarManager.updateTimeDisplay(currentPosition, newTotalDuration)
                }			
                "PLAYBACK_STARTED" -> {
                    isPlaying = true
                    updatePlaybackUI()
                }
                "PLAYBACK_PAUSED" -> {
                    isPlaying = false
                    updatePlaybackUI()
                }
                "PLAYBACK_STOPPED" -> {
                    isPlaying = false
                    currentEpisode = null
                    updatePlaybackUI()
                    // 如果是连续播放模式且当前有播放集，播放下一集
                    if (isContinuousMode && currentPlayingIndex != -1 && currentPlayingIndex + 1 < episodes.size) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            playNextEpisode()
                        }, 500)
                    }
                }
                "PLAYBACK_PROGRESS" -> {
                    val currentPosition = intent.getLongExtra("current_position", 0L)
                    val totalDuration = intent.getLongExtra("duration", 0L)
                    val isPlayingNow = intent.getBooleanExtra("is_playing", false)
                    
                    // 更新进度条
                    progressBarManager.updateProgressBar(currentPosition, totalDuration)
                    
                    // 更新播放时间显示
                    progressBarManager.updateTimeDisplay(currentPosition, totalDuration)
                }
                "SEEK_COMPLETED" -> {
                    val seekPosition = intent.getLongExtra("seek_position", 0L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化进度条管理器
        progressBarManager = ProgressBarManager(
            this,
            colorPrimary, colorSecondary, colorAccent, colorSurface,
            colorOnPrimary, colorOnSecondary, colorOnSurface,
            colorPlaying, colorDivider, colorProgressBackground, colorProgress
        )
        
        binding = ActivityPodcastBinding.inflate(layoutInflater)
        
        // 注册广播接收器
        registerReceivers()
        
        setContentView(binding.root)
        stationImageUrl = intent.getStringExtra("station_image_url")
    
        // 设置深色主题背景
        window.decorView.setBackgroundColor(colorPrimary)
        supportActionBar?.hide()
    
        // 初始化UI样式
        initThemeStyles()
    
        // 设置进度条
        setupProgressBar()
    
        // 注册销毁接收器
        val filter = IntentFilter("DESTROY_PODCAST")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(destroyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(destroyReceiver, filter)
        }
    
        val rssUrl = intent.getStringExtra("rss_url") ?: run { finish(); return }
        val podcastTitle = intent.getStringExtra("title") ?: "播客"
        binding.titleBar.text = podcastTitle
    
        // 初始化RecyclerView
        adapter = EpisodeAdapter(episodes) { ep -> 
            handleEpisodeClick(ep)
        }
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@PodcastActivity)
            adapter = this@PodcastActivity.adapter
            setBackgroundColor(Color.TRANSPARENT)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    super.getItemOffsets(outRect, view, parent, state)
                    val position = parent.getChildAdapterPosition(view)
                    outRect.bottom = if (position == parent.adapter?.itemCount?.minus(1)) 30 else 8
                    outRect.left = 20
                    outRect.right = 20
                }
            })
        }
    
        // 排序按钮
        binding.sortButton.setOnClickListener {
            isDescending = !isDescending
            binding.sortButton.text = if (isDescending) "最新优先 ↓" else "最早优先 ↑"
            sortEpisodes()
        }
    
        // 播放模式按钮
        binding.playModeButton.setOnClickListener {
            isContinuousMode = !isContinuousMode
            binding.playModeButton.text = if (isContinuousMode) "连续播放 ✅" else "单集播放"
        }
    
        // 加载数据
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val parseResult = rssParser.parseRssFeed(rssUrl, stationImageUrl)
            binding.progressBar.visibility = View.GONE
            
            if (parseResult == null) {
                return@launch
            }
            
            bindFeed(parseResult)
        }
    }

    private fun registerReceivers() {
        // 注册下载广播接收器
        LocalBroadcastManager.getInstance(this).registerReceiver(
            downloadReceiver,
            IntentFilter().apply {
                addAction(DownloadBroadcast.ACTION_DOWNLOAD_FINISHED)
            }
        )
        
        // 注册播放状态接收器
        LocalBroadcastManager.getInstance(this).registerReceiver(
            playbackReceiver,
            IntentFilter().apply {
                addAction("PLAYBACK_STARTED")
                addAction("PLAYBACK_PAUSED")
                addAction("PLAYBACK_STOPPED")
                addAction("PLAYBACK_PROGRESS")
                addAction("SEEK_COMPLETED")
            }
        )
    }

    // 绑定RSS数据
    private fun bindFeed(parseResult: RssParser.ParseResult) {
        binding.titleText.text = parseResult.title
        binding.authorText.text = parseResult.author
        
        // 显示封面
        parseResult.coverUrl?.let { cover ->
            Glide.with(this)
                .load(cover)
                .transform(CenterCrop(), RoundedCorners(12.dpToPx()))
                .into(binding.coverImage)
        } ?: run {
            binding.coverImage.setImageDrawable(
                getRoundedDrawable(12f, colorSurface)
            )
        }
        
        episodes.clear()
        episodes.addAll(parseResult.episodes)
        
        // 扫描本地下载状态
        episodes.forEach { ep ->
            PodcastFileHelper
                .isDownloaded(this, ep.title)
                ?.let { file ->
                    ep.localPath = file.absolutePath
                    ep.downloadState = DownloadState.COMPLETED
                }
        }
        if (episodes.isEmpty())       
        sortEpisodes()
        adapter.notifyDataSetChanged()
    }
    
    // 设置进度条
    private fun setupProgressBar() {
        // 创建进度条容器
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            setBackgroundColor(colorSurface)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16.dpToPx(), 0, 16.dpToPx(), 8.dpToPx())
            }
        }

        // 时间显示容器
        val timeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 当前时间
        val currentTimeText = TextView(this).apply {
            id = R.id.current_time
            text = "--:--"
            textSize = 12f
            setTextColor(colorOnSecondary)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // 总时长
        val totalTimeText = TextView(this).apply {
            id = R.id.total_time
            text = "--:--"
            textSize = 12f
            setTextColor(colorOnSecondary)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            gravity = Gravity.END
        }

        timeContainer.addView(currentTimeText)
        timeContainer.addView(totalTimeText)

        // 可拖动的进度条
        val seekBar = SeekBar(this).apply {
            id = R.id.playback_seekbar
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                32.dpToPx()
            ).apply {
                topMargin = 4.dpToPx()
                bottomMargin = 4.dpToPx()
            }
        }

        progressContainer.addView(seekBar)
        progressContainer.addView(timeContainer)

        // 添加到主布局中
        val root = binding.rootLayout as LinearLayout
        val recyclerViewIndex = root.indexOfChild(binding.recyclerView)
        root.addView(progressContainer, recyclerViewIndex)
        
        // 初始化进度条管理器
        progressBarManager.setupProgressBar(seekBar, currentTimeText, totalTimeText)
        progressBarManager.onSeekListener = { position ->
            performSeek(position)
        }
        progressBarManager.onSeekStartListener = {
            
        }
        progressBarManager.onSeekEndListener = {
           
        }
    }
    // 初始化主题样式
    private fun initThemeStyles() {
        // 标题栏样式
        binding.titleBar.apply {
            setTextColor(colorOnPrimary)
            textSize = 20f
            setPadding(20, 20, 20, 10)
            setBackgroundColor(colorPrimary)
        }
    
        // 头部信息区域
        binding.headerLayout.apply {
            setBackgroundColor(colorPrimary)
            setPadding(20, 10, 20, 20)
        }
    
        // 封面图片样式
        binding.coverImage.apply {
            layoutParams = LinearLayout.LayoutParams(180.dpToPx(), 180.dpToPx()).apply {
                marginEnd = 20.dpToPx()
                bottomMargin = 10.dpToPx()
            }
            clipToOutline = true
            background = getRoundedDrawable(12f, colorSurface)
        }
    
        // 文本样式
        binding.titleText.apply {
            setTextColor(colorOnPrimary)
            textSize = 18f
            setPadding(0, 0, 0, 8.dpToPx())
        }
    
        binding.authorText.apply {
            setTextColor(colorOnSecondary)
            textSize = 14f
            setPadding(0, 0, 0, 10.dpToPx())
        }
    
        // 按钮样式
        val buttonStyle = { button: Button ->
            button.apply {
                setTextColor(colorOnSecondary)
                setTextSize(14f)
                setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
                background = getRoundedDrawable(24f, colorSecondary)
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.setBackgroundColor(ColorUtils.blendARGB(colorSecondary, Color.BLACK, 0.2f))
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.setBackgroundColor(colorSecondary)
                    }
                    false
                }
            }
        }
    
        buttonStyle(binding.sortButton)
        buttonStyle(binding.playModeButton)
    
        
        // 主容器背景
        binding.rootLayout.setBackgroundColor(colorPrimary)
    }
    // dp转px扩展函数
    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    // 创建圆角背景Drawable
    private fun getRoundedDrawable(radius: Float, color: Int): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.RECTANGLE
        drawable.setColor(color)
        drawable.cornerRadius = radius
        drawable.setStroke(1.dpToPx(), colorDivider)
        return drawable
    }

    // 更新播放UI
    private fun updatePlaybackUI() {
        if (currentPlayingIndex != -1) {
            adapter.notifyItemChanged(currentPlayingIndex)
        }
    }

    // 执行跳转操作
    private fun performSeek(position: Long) {
        WebViewPlaybackService.seekTo(this, position)
        
        // 立即更新进度显示
        progressBarManager.updateProgressBar(position, totalDuration)
        progressBarManager.updateTimeDisplay(position, totalDuration)
        
    }

    // 处理剧集点击
    private fun handleEpisodeClick(ep: Episode) {
        if (currentEpisode == ep) {
            WebViewPlaybackService.togglePlayPause(this)
        } else {
            playEpisode(ep)
        }
    }

    // 播放剧集
    private fun playEpisode(ep: Episode) {
        val previousIndex = currentPlayingIndex
        currentPlayingIndex = episodes.indexOf(ep)
        currentEpisode = ep
        
        // 通知适配器更新之前的播放项和新的播放项
        if (previousIndex != -1 && previousIndex != currentPlayingIndex) {
            adapter.notifyItemChanged(previousIndex)
        }
        adapter.notifyItemChanged(currentPlayingIndex)

        // 更新总时长显示
        if (ep.durationInMillis > 0) {
            totalDuration = ep.durationInMillis
            progressBarManager.totalTimeText?.text = progressBarManager.formatTime(ep.durationInMillis)
        }

        val url = ep.url.trim()
        val lower = url.lowercase()
        val isPodcastDirect = lower.endsWith(".m4a") || lower.endsWith(".mp3") ||
                lower.endsWith(".aac") || lower.contains("xyzcdn.net") || lower.endsWith(".ogg")

        // 优先播放本地文件
        ep.localPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                Log.i("PodcastActivity", "播放本地播客: $path")
        
                WebViewPlaybackService.playStreamFromPodcast(
                    this,
                    file.toURI().toString(),
                    "${binding.titleText.text} - ${ep.title}"
                )
                return
            }
        }
        
        if (isPodcastDirect) {
            
            WebViewPlaybackService.playStreamFromPodcast(
                this,
                url,
                "${binding.titleText.text} - ${ep.title}"
            )
            return
        }
        
        // YouTube 播放
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            WebViewPlaybackService.setCurrentSource(url)

            lifecycleScope.launch {
                YTPlayer.ytPlay(this@PodcastActivity, url) { title ->
                    Log.i("PodcastActivity", "YTPlayer started: $title")
        
                    // YouTube 播放，重置进度显示
                    progressBarManager.reset()
                }
            }
            return
        }

        Log.i("PodcastActivity", "未知类型，尝试直接播放: $url")
        WebViewPlaybackService.playStream(this, url, "${binding.titleText.text} - ${ep.title}")
    }

    private fun playNextEpisode() {
        val next = currentPlayingIndex + 1
        if (next in episodes.indices) {
            playEpisode(episodes[next])
        } 
    }

    private fun sortEpisodes() {
        if (isDescending) episodes.sortByDescending { it.pubDateTime }
        else episodes.sortBy { it.pubDateTime }
        adapter.notifyDataSetChanged()
    }

    private fun deleteDownloadedEpisode(ep: Episode): Boolean {
        val path = ep.localPath ?: return false
        val file = File(path)
        if (!file.exists()) return false
    
        val success = file.delete()
        if (success) {
            ep.localPath = null
            ep.downloadState = DownloadState.NONE
        }
        return success
    }


    // 处理下载完成自动切换
    private fun handleDownloadFinishedAutoSwitch(
        sourceUrl: String,
        localPath: String
    ) {
        val ep = currentEpisode ?: return
    
        val file = File(localPath)
        if (!file.exists()) return
    
        Log.i("Podcast", "下载完成，自动切换本地播放: $localPath")
    
        //WebViewPlaybackService.stop(this)
    
        WebViewPlaybackService.playStreamFromPodcast(
            this,
            file.toURI().toString(),
            "${binding.titleText.text} - ${ep.title}"
        )
    
    }

    inner class EpisodeAdapter(
        private val list: List<Episode>,
        private val onClick: (Episode) -> Unit
    ) : RecyclerView.Adapter<EpisodeAdapter.VH>() {
    
        // 缓存 Drawable，避免重复创建
        private val normalBg by lazy { getRoundedDrawable(12f, colorSurface) }
        private val playingBg by lazy { getRoundedDrawable(12f, colorPlaying).apply { setStroke(2.dpToPx(), colorAccent) } }
        private val thumbPlaceholder by lazy { getRoundedDrawable(8f, colorSecondary) }
        private val downloadingBg by lazy { getRoundedDrawable(12f, colorSurface).apply { setStroke(2.dpToPx(), colorAccent) } }
    
        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.episode_title)
            val info: TextView = itemView.findViewById(R.id.episode_info)
            val thumbImage: ImageView = itemView.findViewById(R.id.episode_thumb)
            val playIcon: ImageView = itemView.findViewById(R.id.play_button)
            val cardView: LinearLayout = itemView.findViewById(R.id.episode_card)
            val durationBadge: TextView = itemView.findViewById(R.id.duration_badge)
            val downloadIcon: ImageView = itemView.findViewById(R.id.download_icon)
        }
    
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val inflater = LayoutInflater.from(parent.context)
            val view = inflater.inflate(R.layout.item_episode, parent, false)
            return VH(view)
        }
    
        override fun onBindViewHolder(holder: VH, position: Int) {
            val ep = list[position]
            val isCurrentEpisode = position == currentPlayingIndex
            val isThisPlaying = isCurrentEpisode && isPlaying
    
            // 文本
            holder.title.text = ep.title
            holder.info.text = ep.pubDate
    
            // 时长徽章
            holder.durationBadge.visibility = if (ep.duration.isNotBlank()) View.VISIBLE else View.GONE
            holder.durationBadge.text = ep.duration
    
            // 缩略图
            val thumbUrl = ep.thumbUrl.ifEmpty { stationImageUrl ?: "" }
            Glide.with(holder.thumbImage.context)
                .load(thumbUrl)
                .placeholder(thumbPlaceholder)
                .error(thumbPlaceholder)
                .transform(CenterCrop(), RoundedCorners(8.dpToPx()))
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(holder.thumbImage)
    
            // 播放状态背景
            holder.cardView.background = when {
                isCurrentEpisode && isThisPlaying -> playingBg
                ep.downloadState == DownloadState.DOWNLOADING -> downloadingBg
                else -> normalBg
            }
    
            // 播放图标
            holder.playIcon.setImageResource(
                if (isThisPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            holder.playIcon.setColorFilter(
                if (isCurrentEpisode) colorAccent else colorOnSecondary
            )
    
            // 下载图标状态
            when (ep.downloadState) {
                DownloadState.NONE -> {
                    holder.downloadIcon.setImageResource(android.R.drawable.stat_sys_download)
                    holder.downloadIcon.setColorFilter(colorOnSecondary)
                }
                DownloadState.DOWNLOADING -> {
                    holder.downloadIcon.setImageResource(android.R.drawable.stat_sys_upload)
                    holder.downloadIcon.setColorFilter(colorAccent)
                }
                DownloadState.COMPLETED -> {
                    holder.downloadIcon.setImageResource(android.R.drawable.stat_sys_download_done)
                    holder.downloadIcon.setColorFilter(Color.GREEN)
                }
                DownloadState.FAILED -> {
                    holder.downloadIcon.setImageResource(android.R.drawable.stat_notify_error)
                    holder.downloadIcon.setColorFilter(Color.RED)
                }
            }
    
            // 点击事件 - 播放/暂停
            holder.itemView.setOnClickListener { 
                // 确保点击的是正确的位置
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition < list.size) {
                    onClick(list[adapterPosition])
                }
            }
    
            // 下载图标点击事件
// ================= EpisodeAdapter 中的下载按钮 =================
            holder.downloadIcon.setOnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition == RecyclerView.NO_POSITION || adapterPosition >= list.size) return@setOnClickListener
            
                val episode = list[adapterPosition]
            
                when (episode.downloadState) {
                    DownloadState.NONE, DownloadState.FAILED -> {
                        episode.downloadState = DownloadState.DOWNLOADING
                        notifyItemChanged(adapterPosition)
            
                        val url = episode.url.trim()
                        val lower = url.lowercase()
            
                        if (lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.contains("xyzcdn.net")) {
                            PodcastDownloadManager.download(holder.itemView.context, episode.title, url)
                        } else if (url.contains("youtube.com") || url.contains("youtu.be")) {
                            this@PodcastActivity.lifecycleScope.launch {
                                try {
                                    val realUrl = YTPlayer.getDownloadUrl(url)
                                    if (!realUrl.isNullOrBlank()) {
                                        PodcastDownloadManager.download(holder.itemView.context, episode.title, realUrl)
                                    } else {
                                        episode.downloadState = DownloadState.FAILED
                                        notifyItemChanged(adapterPosition)
                                    }
                                } catch (e: Exception) {
                                    episode.downloadState = DownloadState.FAILED
                                    notifyItemChanged(adapterPosition)
                                }
                            }
                        } else {
                            PodcastDownloadManager.download(holder.itemView.context, episode.title, url)
                        }
                    }
                    DownloadState.DOWNLOADING -> {}
                    DownloadState.COMPLETED -> {}
                }
            }
            
            // ================= 长按删除 =================
            holder.downloadIcon.setOnLongClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition == RecyclerView.NO_POSITION || adapterPosition >= list.size) return@setOnLongClickListener true
            
                val episode = list[adapterPosition]
            
                if (episode.downloadState == DownloadState.COMPLETED) {
                    androidx.appcompat.app.AlertDialog.Builder(holder.itemView.context)
                        .setTitle("删除下载")
                        .setMessage("确定要删除本地文件吗？\n${episode.title}")
                        .setPositiveButton("删除") { _, _ ->
                            val file = episode.localPath?.let { File(it) }
                            if (file != null && file.exists() && file.delete()) {
                                episode.downloadState = DownloadState.NONE
                                episode.localPath = null
                                notifyItemChanged(adapterPosition)
                            } 
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                true
            }

            // 点击反馈，使用颜色混合
            holder.itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        holder.cardView.setBackgroundColor(
                            when {
                                isCurrentEpisode && isThisPlaying -> 
                                    ColorUtils.blendARGB(colorPlaying, Color.BLACK, 0.2f)
                                ep.downloadState == DownloadState.DOWNLOADING ->  // 修正：使用ep
                                    ColorUtils.blendARGB(colorSurface, colorAccent, 0.2f)
                                else -> 
                                    ColorUtils.blendARGB(colorSurface, Color.BLACK, 0.2f)
                            }
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        holder.cardView.background = when {
                            isCurrentEpisode && isThisPlaying -> playingBg
                            ep.downloadState == DownloadState.DOWNLOADING -> downloadingBg  // 修正：使用ep
                            else -> normalBg
                        }
                    }
                }
                false
            }
        }
    
        override fun getItemCount() = list.size
        
        // 更新下载状态的方法
        fun updateDownloadState(url: String, localPath: String?) {
            val index = list.indexOfFirst { it.url == url }
            if (index != -1) {
                val episode = list[index]
                episode.localPath = localPath
                episode.downloadState = if (localPath != null) DownloadState.COMPLETED else DownloadState.FAILED
                notifyItemChanged(index)
            }
        }
        
        // 开始下载状态
        fun startDownloading(url: String) {
            val index = list.indexOfFirst { it.url == url }
            if (index != -1) {
                list[index].downloadState = DownloadState.DOWNLOADING
                notifyItemChanged(index)
            }
        }
    }
    // 下载广播接收器
// ================= 下载广播接收器 =================
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val episodeTitle = intent.getStringExtra(DownloadBroadcast.EXTRA_EPISODE_TITLE)
            val localPath = intent.getStringExtra(DownloadBroadcast.EXTRA_LOCAL_PATH)
            if (episodeTitle.isNullOrBlank()) return
    
            runOnUiThread {
                // 用标题匹配 Episode
                val index = episodes.indexOfFirst { it.title == episodeTitle }
                if (index != -1) {
                    val ep = episodes[index]
                    ep.localPath = localPath
                    ep.downloadState = if (!localPath.isNullOrBlank()) DownloadState.COMPLETED else DownloadState.FAILED
                    adapter.notifyItemChanged(index)
                if (!localPath.isNullOrBlank() && currentEpisode == ep) {
                    handleDownloadFinishedAutoSwitch(ep.url, localPath)					
                } else {
                    Log.e("DownloadReceiver", "未找到对应的Episode: $episodeTitle")
                }

                }				
				
            }
        }
    }


    override fun onBackPressed() {
        WebViewPlaybackService.stop(this)
        super.onBackPressed()
    }

    override fun onDestroy() {
        // 注销广播接收器
        try { 
            unregisterReceiver(destroyReceiver)
        } catch (_: Exception) {}
        
        LocalBroadcastManager.getInstance(this).apply {
            try { unregisterReceiver(playbackReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        }
        
        // 停止播放
        WebViewPlaybackService.stop(this)
        
        super.onDestroy()
    }
}