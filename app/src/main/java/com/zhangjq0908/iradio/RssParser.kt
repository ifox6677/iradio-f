package com.zhangjq0908.iradio

import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import com.zhangjq0908.iradio.model.Episode
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RssParser {
    
    data class ParseResult(
        val title: String,
        val author: String,
        val episodes: List<Episode>,
        val coverUrl: String?
    )
    
    suspend fun parseRssFeed(rssUrl: String, stationImageUrl: String? = null): ParseResult? {
        return withContext(Dispatchers.IO) {
            try {
                val feed = SyndFeedInput().build(XmlReader(URL(rssUrl).openStream()))
                if (feed != null) {
                    convertToParseResult(feed, rssUrl, stationImageUrl)
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    private fun convertToParseResult(feed: SyndFeed, rssUrl: String, stationImageUrl: String?): ParseResult {
        val title = feed.title ?: "未知播客"
        val author = feed.author ?: "未知作者"
        
        // 获取封面URL
        val coverUrl = getCoverUrl(feed, stationImageUrl)
        
        val episodes = mutableListOf<Episode>()
        feed.entries?.forEach { entry ->
            parseEpisode(entry)?.let { episodes.add(it) }
        }
        
        return ParseResult(title, author, episodes, coverUrl)
    }
    
    private fun getCoverUrl(feed: SyndFeed, stationImageUrl: String?): String? {
        // 1️⃣ RSS 封面
        var cover = feed.image?.url
        
        // 2️⃣ iTunes image
        if (cover.isNullOrBlank()) {
            cover = feed.foreignMarkup
                ?.find { it.name == "image" && it.namespace?.prefix == "itunes" }
                ?.getAttributeValue("href")
        }
        
        // 3️⃣ 应用内 Station 封面
        if (cover.isNullOrBlank()) {
            cover = stationImageUrl
        }
        
        return cover
    }
    
    private fun parseEpisode(entry: SyndEntry): Episode? {
        // YouTube RSS 解析 yt:videoId
        val ytVideoIdFromForeign = entry.foreignMarkup
            ?.firstOrNull {
                it.name.equals("videoId", true) &&
                it.namespace?.uri == "http://www.youtube.com/xml/schemas/2015"
            }
            ?.value
        
        // 优先选择 URL：YouTube > <link> > enclosure > <guid>
        val url = when {
            // 1️⃣ YouTube 官方 RSS
            !ytVideoIdFromForeign.isNullOrBlank() ->
                "https://www.youtube.com/watch?v=$ytVideoIdFromForeign"
            
            // 2️⃣ 正规 Podcast：只接受 audio enclosure
            !entry.enclosures.isNullOrEmpty() -> {
                entry.enclosures.firstOrNull {
                    !it.url.isNullOrBlank() &&
                    (
                        it.type?.startsWith("audio") == true ||
                        it.url.endsWith(".mp3", true) ||
                        it.url.endsWith(".m4a", true) ||
                        it.url.endsWith(".aac", true)
                    )
                }?.url ?: ""
            }
            
            // 3️⃣ 自制 YT RSS：<link> 是 watch URL
            !entry.link.isNullOrBlank() &&
                entry.link.contains("youtube.com/watch", true) ->
                entry.link
            
            // 4️⃣ 自制 YT RSS：<guid> 是 watch URL（Rome 用 entry.uri）
            !entry.uri.isNullOrBlank() &&
                entry.uri.contains("youtube.com/watch", true) ->
                entry.uri
            
            else -> ""
        }
        
        if (url.isBlank()) return null
        
        val pubDate = entry.publishedDate ?: Date()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(pubDate)
        
        val durationSec = entry.foreignMarkup
            ?.find { it.name == "duration" && it.namespace?.prefix == "itunes" }
            ?.value?.toIntOrNull() ?: 0
        
        val duration = if (durationSec == 0) "" else {
            val h = durationSec / 3600
            val m = (durationSec % 3600) / 60
            val s = durationSec % 60
            if (h > 0) "%d:%02d:%02d".format(h, m, s)
            else "%02d:%02d".format(m, s)
        }
        
        return Episode(
            title = entry.title ?: "无标题",
            url = url,
            duration = duration,
            pubDate = dateStr,
            pubDateTime = pubDate.time,
            durationInSeconds = durationSec,
            durationInMillis = durationSec * 1000L
        )
    }
    
    fun parseYouTubeRss(input: InputStream): List<YouTubeExtra> {
        val list = mutableListOf<YouTubeExtra>()
        try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val p = factory.newPullParser()
            p.setInput(input, null)
    
            val nsYT = "http://www.youtube.com/xml/schemas/2015"
            val nsMedia = "http://search.yahoo.com/mrss/"
    
            var event = p.eventType
            var curVideoId = ""
            var curThumb = ""
            var curDesc = ""
            var insideEntry = false
            var insideMediaGroup = false
    
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = p.name ?: ""
                        val ns = p.namespace ?: ""
    
                        if (name.equals("entry", ignoreCase = true)) {
                            insideEntry = true
                            curVideoId = ""
                            curThumb = ""
                            curDesc = ""
                        }
    
                        if (insideEntry && name.equals("videoId", ignoreCase = true) && ns == nsYT) {
                            curVideoId = try { p.nextText().trim() } catch (_: Exception) { "" }
                        }
    
                        if (insideEntry && name.equals("group", ignoreCase = true) && ns == nsMedia) {
                            insideMediaGroup = true
                        }
    
                        if (insideMediaGroup && name.equals("thumbnail", ignoreCase = true)) {
                            curThumb = p.getAttributeValue(null, "url") ?: ""
                        }
    
                        if (insideMediaGroup && name.equals("description", ignoreCase = true) && ns == nsMedia) {
                            curDesc = try { p.nextText().trim() } catch (_: Exception) { "" }
                        }
                    }
    
                    XmlPullParser.END_TAG -> {
                        val name = p.name ?: ""
    
                        if (name.equals("group", ignoreCase = true)) {
                            insideMediaGroup = false
                        }
    
                        if (name.equals("entry", ignoreCase = true)) {
                            if (curVideoId.isNotBlank()) {
                                list += YouTubeExtra(curVideoId, curThumb, curDesc)
                            }
                            insideEntry = false
                            insideMediaGroup = false
                            curVideoId = ""
                            curThumb = ""
                            curDesc = ""
                        }
                    }
                }
                event = p.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
        return list
    }
    
    data class YouTubeExtra(
        val videoId: String,
        val thumbUrl: String,
        val description: String
    )
}