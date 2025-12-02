package com.example.iradio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.net.URL

/**
 * 主界面 Activity
 * 功能：
 * 1. 显示电台列表（ViewPager2 + Fragment）
 * 2. 显示当前播放电台
 * 3. 支持播放/停止操作
 * 4. 支持睡眠定时器，显示倒计时在屏幕中间
 *
 * 更新：电台列表仅第一次安装后联网加载，之后永久读取本地缓存。
 */
class MainActivity : AppCompatActivity() {

    // UI 协程作用域，用于网络加载等异步操作
    private val uiScope = MainScope()

    // ViewPager2 用于显示电台列表
    private lateinit var pager: ViewPager2
    // 菜单项引用（用于睡眠定时器菜单图标）
    private var timerMenuItem: MenuItem? = null

    // 睡眠定时器管理器
    private lateinit var sleepTimer: SleepTimerManager

    // 播放状态显示相关控件
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnStop: ImageView
    private lateinit var tvSleepTimer: TextView // 屏幕中央倒计时显示

    // 电台列表 JSON URL
    private val STATIONS_JSON_URL =
        "https://raw.githubusercontent.com/ifox6677/cctv/main/radioStations.json"

    /**
     * 播放状态广播
     * 用于接收 PlayerService 发送的广播，更新当前正在播放的电台名称
     */
    private val nowPlayingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "NOW_PLAYING_UPDATE") {
                val name = intent.getStringExtra("stationName") ?: "未播放"
                tvNowPlaying.text = name
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // 绑定布局

        // ==================== Toolbar 设置 ====================
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false) // 隐藏默认标题

        // ==================== 绑定控件 ====================
        tvNowPlaying = findViewById(R.id.tvNowPlaying)   // 顶部显示播放电台名称
        btnStop = findViewById(R.id.btnStop)             // 停止播放按钮
        pager = findViewById(R.id.viewPager)            // 电台列表 ViewPager
        tvSleepTimer = findViewById(R.id.tvSleepTimer)  // 屏幕中央倒计时 TextView

        // 确保倒计时 TextView 在最上层，不被其他控件遮挡
        tvSleepTimer.bringToFront()

        // 停止播放按钮点击事件
        btnStop.setOnClickListener { stopRadio() }

        // ==================== 初始化睡眠定时器 ====================
        sleepTimer = SleepTimerManager(
            this,
            Dispatchers.Main.immediate, // UI 协程调度器
            onTick = { timeStr ->
                // 倒计时每秒回调，将时间显示在中央 TextView
                runOnUiThread {
                    updateSleepTimerIcon(timeStr)
                }
            },
            onFinish = {
                // 定时器结束时提示
                Toast.makeText(this, "定时时间到，已停止播放", Toast.LENGTH_LONG).show()
            }
        )

        // ==================== 注册播放状态广播 ====================
        // Android 13+ 必须显式注册，否则 MainActivity 收不到广播
        // RECEIVER_NOT_EXPORTED 表示仅本应用可发送广播，不对外暴露
        registerReceiver(
            nowPlayingReceiver,
            IntentFilter("NOW_PLAYING_UPDATE"),
            RECEIVER_NOT_EXPORTED
        )

        // ==================== 加载电台数据（仅第一次联网） ====================
        loadStations()
    }

    // ==================== 菜单 ====================
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        timerMenuItem = menu?.findItem(R.id.action_sleep_timer)
        timerMenuItem?.setIcon(R.drawable.ic_sleep_timer)
        updateSleepTimerIcon(null) // 初始隐藏倒计时
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sleep_timer -> {
                // 点击菜单弹出定时器选择
                showSleepTimerDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /**
     * 更新倒计时显示（TextView 版）
     * @param timeStr: 剩余时间，空则隐藏
     */
    private fun updateSleepTimerIcon(timeStr: String?) {
        if (!timeStr.isNullOrEmpty()) {
            tvSleepTimer.text = timeStr
            tvSleepTimer.visibility = android.view.View.VISIBLE
        } else {
            tvSleepTimer.text = ""
            tvSleepTimer.visibility = android.view.View.GONE
        }
        tvSleepTimer.bringToFront() // 确保显示在最上层
    }

    /**
     * 弹出定时器选择对话框
     */
    private fun showSleepTimerDialog() {
        val presetTimes = arrayOf(2, 30, 60, 90, 120, 180) // 可选时间（分钟）
        TimerDialog(this, presetTimes) { minutes ->
            sleepTimer.start(minutes) // 启动定时器
        }.show()
    }

    // ==================== 电台加载（仅第一次联网，以后读本地） ====================
    private fun loadStations() {
        uiScope.launch {
            // 1. 本地缓存文件
            val cacheFile = File(filesDir, "radio_stations.json")

            // 2. 读本地 or 下载
            val jsonText = withContext(Dispatchers.IO) {
                if (cacheFile.exists()) {
                    // 已有缓存，直接读取
                    cacheFile.readText()
                } else {
                    // 第一次运行：联网下载
                    runCatching {
                        if (STATIONS_JSON_URL.startsWith("file://")) {
                            File(STATIONS_JSON_URL.removePrefix("file://")).readText()
                        } else {
                            URL(STATIONS_JSON_URL).readText()
                        }
                    }.getOrNull()?.also { downloaded ->
                        // 把下载到的内容写进本地文件，下次直接读
                        cacheFile.writeText(downloaded)
                    }
                }
            }

            if (jsonText.isNullOrEmpty()) {
                Toast.makeText(this@MainActivity, "加载电台列表失败", Toast.LENGTH_LONG).show()
                return@launch
            }

            val groups = parseGroups(jsonText)
            pager.adapter = GroupPagerAdapter(this@MainActivity, groups)
        }
    }

    /**
     * JSON 解析成 Map<分类, 电台列表>
     */
    private fun parseGroups(json: String): Map<String, List<Station>> {
        val arr = JSONArray(json)
        val map = linkedMapOf<String, MutableList<Station>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val st = Station(
                name = o.optString("name"),
                imageUrl = o.optString("imageUrl"),
                iurl = o.optString("iurl"),
                category = o.optString("category", "其它")
            )
            map.getOrPut(st.category ?: "其它") { mutableListOf() }.add(st)
        }
        return map
    }

    /**
     * ViewPager 适配器，按分类显示电台 Fragment
     */
    class GroupPagerAdapter(
        fa: FragmentActivity,
        private val groups: Map<String, List<Station>>
    ) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = groups.size
        override fun createFragment(position: Int): Fragment =
            GroupFragment.newInstance(groups.values.elementAt(position))
    }

    // ==================== 停止播放 ====================
    private fun stopRadio() {
        tvNowPlaying.text = "未播放"
        startService(Intent(this, PlayerService::class.java).apply {
            action = PlayerService.ACTION_STOP
        })
    }

    override fun onDestroy() {
        // 页面销毁时取消协程，停止定时器，注销广播
        uiScope.cancel()
        sleepTimer.cancel()
        unregisterReceiver(nowPlayingReceiver)
        super.onDestroy()
    }
}