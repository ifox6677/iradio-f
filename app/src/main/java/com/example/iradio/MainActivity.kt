package com.zhangjq0908.iradio

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
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
import java.net.URL

class MainActivity : AppCompatActivity() {

    private val uiScope = MainScope()
    private lateinit var pager: ViewPager2
    private var timerMenuItem: MenuItem? = null
    private lateinit var sleepTimer: SleepTimerManager
    private lateinit var tvNowPlaying: TextView
    private lateinit var btnStop: ImageView
    private lateinit var tvSleepTimer: TextView

    private val STATIONS_JSON_URL =
        "https://raw.githubusercontent.com/ifox6677/cctv/main/radioStations.json"

    // ==================== 播放信息广播 ====================
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

        // ==================== Chaquopy 初始化 ====================
        if (!com.chaquo.python.Python.isStarted()) {
            com.chaquo.python.Python.start(
                com.chaquo.python.android.AndroidPlatform(this)
            )
        }

        setContentView(R.layout.activity_main)

        // ==================== Toolbar ====================
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        tvNowPlaying = findViewById(R.id.tvNowPlaying)
        btnStop = findViewById(R.id.btnStop)
        pager = findViewById(R.id.viewPager)
        tvSleepTimer = findViewById(R.id.tvSleepTimer)
        tvSleepTimer.bringToFront()

        // ★ 修改这里：使用 Intent 停止播放
        btnStop.setOnClickListener {
            stopRadio() // 替换旧 stopRadio() 内部逻辑
            YTPlayer.stop(this)
        }

        sleepTimer = SleepTimerManager(
            this,
            Dispatchers.Main.immediate,
            onTick = { timeStr -> runOnUiThread { updateSleepTimerIcon(timeStr) } },
            onFinish = {
                Toast.makeText(this, "定时时间到，已停止播放", Toast.LENGTH_LONG).show()
                stopRadio()
            }
        )

        // ------------------- 注册播放状态广播 -------------------
        registerReceiver(
            nowPlayingReceiver,
            IntentFilter("NOW_PLAYING_UPDATE"),
            RECEIVER_NOT_EXPORTED
        )

        // ------------------- 加载电台 -------------------
        loadStations()
    }

    // ==================== 菜单 ====================
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        timerMenuItem = menu?.findItem(R.id.action_sleep_timer)
        timerMenuItem?.setIcon(R.drawable.ic_sleep_timer)
        updateSleepTimerIcon(null)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sleep_timer -> {
                showSleepTimerDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateSleepTimerIcon(timeStr: String?) {
        if (!timeStr.isNullOrEmpty()) {
            tvSleepTimer.text = timeStr
            tvSleepTimer.visibility = android.view.View.VISIBLE
        } else {
            tvSleepTimer.text = ""
            tvSleepTimer.visibility = android.view.View.GONE
        }
        tvSleepTimer.bringToFront()
    }

    private fun showSleepTimerDialog() {
        val presetTimes = arrayOf(1, 30, 60, 90, 120, 180)
        TimerDialog(this, presetTimes) { minutes ->
            sleepTimer.start(minutes)
        }.show()
    }

    // ==================== 电台加载 ====================
    private fun loadStations() {
        uiScope.launch {
            val cacheFile = File(filesDir, "radio_stations.json")
            val jsonText = withContext(Dispatchers.IO) {
                if (cacheFile.exists()) {
                    cacheFile.readText()
                } else {
                    runCatching {
                        if (STATIONS_JSON_URL.startsWith("file://")) {
                            File(STATIONS_JSON_URL.removePrefix("file://")).readText()
                        } else {
                            URL(STATIONS_JSON_URL).readText()
                        }
                    }.getOrNull()?.also { downloaded -> cacheFile.writeText(downloaded) }
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

    class GroupPagerAdapter(
        fa: FragmentActivity,
        private val groups: Map<String, List<Station>>
    ) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = groups.size
        override fun createFragment(position: Int): androidx.fragment.app.Fragment =
            GroupFragment.newInstance(groups.values.elementAt(position))
    }

    // ==================== 停止播放（修改为新 PlayerService 兼容方式） ====================
    private fun stopRadio() {
        startService(
            Intent(this, PlayerService::class.java).apply {
                action = PlayerService.ACTION_STOP
            }
        )
    }

    override fun onDestroy() {
        uiScope.cancel()
        sleepTimer.cancel()
        unregisterReceiver(nowPlayingReceiver)
        super.onDestroy()
    }
}
