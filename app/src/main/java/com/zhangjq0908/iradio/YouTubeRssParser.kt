package com.zhangjq0908.iradio

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

data class YouTubeVideo(
    val videoId: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String,
    val published: Date
)

object YouTubeRssParser {

    fun parse(inputStream: InputStream): List<YouTubeVideo> {
        val videos = mutableListOf<YouTubeVideo>()
        val parserFactory = XmlPullParserFactory.newInstance()
        val parser = parserFactory.newPullParser()
        parser.setInput(inputStream, null)

        var eventType = parser.eventType
        var currentVideo: YouTubeVideo? = null
        var text = ""
        var title = ""
        var videoId = ""
        var description = ""
        var thumbnail = ""
        var published: Date = Date()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())

        while (eventType != XmlPullParser.END_DOCUMENT) {
            val tagName = parser.name
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (tagName == "entry") {
                        title = ""
                        videoId = ""
                        description = ""
                        thumbnail = ""
                        published = Date()
                    }
                }
                XmlPullParser.TEXT -> text = parser.text
                XmlPullParser.END_TAG -> {
                    when (tagName) {
                        "yt:videoId" -> videoId = text
                        "title" -> title = text
                        "media:description" -> description = text
                        "media:thumbnail" -> thumbnail = parser.getAttributeValue(null, "url") ?: ""
                        "published" -> {
                            try { published = dateFormat.parse(text) ?: Date() } catch (_: Exception) {}
                        }
                        "entry" -> {
                            if (videoId.isNotBlank()) {
                                videos.add(YouTubeVideo(videoId, title, description, thumbnail, published))
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return videos
    }
}
