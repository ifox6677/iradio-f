package com.zhangjq0908.iradio.download

import android.content.Context
import java.io.File

object PodcastFileHelper {

    // 支持的音频格式
    private val supportedExts = listOf("mp3", "m4a", "aac", "flac")

    // 获取本地文件（不指定格式，返回首个存在的文件）
    fun getDownloadedEpisode(context: Context, title: String): File? {
        val safeName = title.replace(Regex("[^a-zA-Z0-9一-龥]"), "_")
        val dir = context.getExternalFilesDir("podcast") ?: return null

        return supportedExts
            .map { ext -> File(dir, "$safeName.$ext") }
            .firstOrNull { it.exists() }
    }

    // 获取目标文件路径（下载时可用）
    fun getEpisodeFile(context: Context, title: String, ext: String = "mp3"): File {
        val safeName = title.replace(Regex("[^a-zA-Z0-9一-龥]"), "_")
        return File(context.getExternalFilesDir("podcast"), "$safeName.$ext")
    }

    // 检查是否已下载
    fun isDownloaded(context: Context, title: String): File? {
        return getDownloadedEpisode(context, title)
    }
}
