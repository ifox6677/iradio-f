package com.example.iradio

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import java.util.concurrent.Executors

class GroupFragment : Fragment() {

    companion object {
        private const val ARG_LIST = "stations"

        fun newInstance(list: List<Station>): GroupFragment = GroupFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_LIST, JSONArray(list.map {
                    mapOf("name" to it.name,
                          "imageUrl" to it.imageUrl,
                          "iurl" to it.iurl,
                          "category" to it.category)
                }).toString())
            }
        }
    }

    private lateinit var stations: List<Station>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arr = JSONArray(arguments?.getString(ARG_LIST) ?: "[]")
        stations = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Station(o.optString("name"),
                    o.optString("imageUrl"),
                    o.optString("iurl"),
                    o.optString("category"))
        }
    }

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(R.layout.fragment_group, container, false)
        root.findViewById<RecyclerView>(R.id.grid).apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = Adapter(stations)
        }
        return root
    }

    /* -------------- 内部 Adapter -------------- */
    private inner class Adapter(private val data: List<Station>)
        : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val img: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
            val btn: MaterialButton = v.findViewById(R.id.playBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context)
                   .inflate(R.layout.item_station_grid, parent, false))

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val s = data[pos]
            h.name.text = s.name
            Glide.with(h.img).load(s.imageUrl).into(h.img)

            val listener = View.OnClickListener {
                when {
                    /* 1. 播客 XML -> 直接进 PodcastActivity */
                    isPodcastUrl(s.iurl) -> {
                        sendStopBroadcasts()
                        startActivity(Intent(requireContext(), PodcastActivity::class.java).apply {
                            putExtra("rss_url", s.iurl)
                            putExtra("title", s.name)
                        })
                    }
                    /* 2. 直播流 -> ExoPlayer */
                    isStream(s.iurl)     -> playStream(s)
                    /* 3. 其它 -> 网页电台 */
                    else                 -> playWebRadio(s)
                }
            }
            h.img.setOnClickListener(listener)
            h.btn.setOnClickListener(listener)
        }

        /* 直播流播放 */
        private fun playStream(s: Station) {
            sendStopBroadcasts()
            if (s.iurl.endsWith(".pls", true)) {
                Executors.newSingleThreadExecutor().submit {
                    val real = PlayerService.fetchPlsUrls(s.iurl).firstOrNull() ?: s.iurl
                    requireContext().startService(Intent(requireContext(), PlayerService::class.java).apply {
                        action = PlayerService.ACTION_PLAY
                        putExtra(PlayerService.EXTRA_STREAM_URL, real)
                        putExtra(PlayerService.EXTRA_TITLE, s.name)
                    })
                }
            } else {
                requireContext().startService(Intent(requireContext(), PlayerService::class.java).apply {
                    action = PlayerService.ACTION_PLAY
                    putExtra(PlayerService.EXTRA_STREAM_URL, s.iurl)
                    putExtra(PlayerService.EXTRA_TITLE, s.name)
                })
            }
        }

        /* 网页电台 */
        private fun playWebRadio(s: Station) {
            sendStopBroadcasts()
            WebViewActivity.play(requireContext(), s.iurl, s.name)
        }

        /* 统一发停止广播 */
        private fun sendStopBroadcasts() {
            // 停 Exo
            requireContext().startService(
                Intent(requireContext(), PlayerService::class.java)
                    .apply { action = PlayerService.ACTION_STOP })
            // 停 WebView
            requireContext().sendBroadcast(
                Intent("STOP_WEB_RADIO")
                    .apply { `package` = requireContext().packageName })
        }

        /* 判断规则 */
        private fun isStream(url: String): Boolean {
            val l = url.lowercase()
            // 明确需要走 WebView 的域名
            if (l.contains("www.881903.com") ||
                l.contains("xyzfm.space") ||
                l.contains("metroradio.com.hk")) return false

            val ext = listOf(".m3u8", ".mp3", ".aac", ".pls", ".m3u", ".wav", ".flac")
            if (ext.any { l.endsWith(it) }) return true
            if (l.contains("/live") || l.contains("stm") ||
                l.contains("/stream") || l.contains("listen")) return true
            return false
        }

        private fun isPodcastUrl(url: String): Boolean =
            url.contains(".xml", true) ||
            url.contains("rss", true) ||
            url.contains("feed", true) ||
            url.contains("podcast", true)
    }
}