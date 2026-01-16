package com.zhangjq0908.iradio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
//import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.chaquo.python.Python
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * YouTube 播放器工具类
 */
object YTPlayer {

    private const val TAG = "YTPlayer"
    private const val CUSTOM_USER_AGENT = "Mozilla/5.0"

    private val FILTERED_URL_PREFIXES = listOf(
        "https://manifest.googlevideo.com/api/manifest/dash/",
        "https://manifest.googlevideo.com/api/manifest/dash/muse/"
    )

    /** 播放 YouTube 链接 */
    suspend fun ytPlay(
        context: Context,
        ytUrl: String,
        lifecycleOwner: LifecycleOwner? = null,
        onReady: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (ytUrl.isBlank()) {
            handleError(context, "YouTube 链接不能为空", onError)
            return
        }

        try {
            val (playUrl, title, channelName) = runInterruptible(Dispatchers.IO) {
                parseYouTube(ytUrl)
            }

            // 播放
            playStream(context, lifecycleOwner, playUrl, title, channelName, onReady)

        } catch (e: CancellationException) {
            withContext(Dispatchers.Main) {
            }
        } catch (e: Exception) {
            val errorMsg = "解析失败: ${e.message ?: "未知错误"}"
            Log.e(TAG, errorMsg, e)
            handleError(context, errorMsg, onError)
        }
    }

    /** 获取可下载音频 URL（供 PodcastDownloadManager 调用） */
    suspend fun getDownloadUrl(ytUrl: String): String? {
        return try {
            val (playUrl, _, _) = runInterruptible(Dispatchers.IO) {
                parseYouTube(ytUrl)
            }
            playUrl
        } catch (e: Exception) {
            Log.e(TAG, "解析下载链接失败", e)
            null
        }
    }

    /** 解析 YouTube 音频流（内部统一方法） - 新版本：按格式和比特率优选 */
    private fun parseYouTube(ytUrl: String): Triple<String, String, String> {
        val py = Python.getInstance()
        val module = py.getModule("yt_parse")

        val jsonStr = module.callAttr("get_audio_stream", ytUrl, CUSTOM_USER_AGENT).toString()
        val gson = Gson()
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val resultMap: Map<String, Any> = gson.fromJson(jsonStr, type)

        val title = resultMap["title"] as? String ?: ytUrl
        val channelName = resultMap["channel"] as? String ?: "YouTube"
        val streams = resultMap["streams"] as? List<Map<String, Any>> ?: emptyList()

        val validAudioStreams = streams.filter {
            val url = it["url"] as? String ?: ""
            (it["acodec"] as? String ?: "none") != "none" &&
            url.isNotBlank() &&
            isValidStreamUrl(url) &&
            !isFilteredUrl(url)
        }

        if (validAudioStreams.isEmpty()) throw IllegalStateException("无有效音频流可播放（已过滤 DASH）")

        /* ==============================================
         * 旧版排序逻辑（注释掉，保留参考）
         * 按 URL 字符串长度和比特率排序
        val sortedStreams = validAudioStreams.sortedWith(
            compareBy(
                { (it["url"] as String).toByteArray(Charsets.UTF_8).size },
                { it["abr"] as? Double ?: Double.MAX_VALUE }
            )
        )
        ============================================== */

        /* ==============================================
         * 新版排序逻辑：按格式和比特率优选
         * 优先级：纯音频 m4a > mp4 > webm > 其他格式
         * 同格式内：低比特率优先（文件更小）
         * 同比特率：AAC编码优先（mp4a）
         ============================================== */
        val sortedStreams = validAudioStreams.sortedWith(
            compareBy(
                // 第一优先级：格式类型 (m4a > mp4 > webm > 其他)
                { stream ->
                    when (stream["ext"] as? String) {
                        "m4a" -> 1  // 最高优先级：纯音频格式，文件小
                        "mp4" -> 2  // 次优先级：兼容性好
                        "webm" -> 3 // 第三优先级
                        else -> 4   // 其他格式
                    }
                },
                // 第二优先级：比特率（越低越好，文件越小）
                { stream ->
                    stream["abr"] as? Double ?: Double.MAX_VALUE
                },
                // 第三优先级：音频编码（AAC优先，兼容性好）
                { stream ->
                    val acodec = stream["acodec"] as? String ?: ""
                    when {
                        acodec.contains("mp4a") -> 1  // AAC编码
                        acodec.contains("opus") -> 2  // Opus编码
                        else -> 3
                    }
                }
            )
        )

        // 调试日志：显示排序结果
        sortedStreams.take(3).forEachIndexed { index, stream ->
            val ext = stream["ext"] as? String ?: "unknown"
            val abr = stream["abr"] as? Double ?: 0.0
            val acodec = stream["acodec"] as? String ?: "unknown"
        }

        val preferredExts = listOf("m4a", "mp4")
        val preferredStreams = sortedStreams.filter { (it["ext"] as? String) in preferredExts }
        val finalStreams = if (preferredStreams.isNotEmpty()) preferredStreams else sortedStreams

        // 选择最优流
        val bestStream = finalStreams.first()
        val playUrl = bestStream["url"] as String
        
        // 显示最终选择
        val selectedExt = bestStream["ext"] as? String ?: "unknown"
        val selectedAbr = bestStream["abr"] as? Double ?: 0.0
        
        return Triple(playUrl, title, channelName)
    }

    /** 播放流 + 广播频道名 */
    private suspend fun playStream(
        context: Context,
        lifecycleOwner: LifecycleOwner?,
        playUrl: String,
        title: String,
        channelName: String,
        onReady: ((String) -> Unit)?
    ) = withContext(Dispatchers.Main) {
        val isLifecycleValid = lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)
            ?: true
        if (!isLifecycleValid) {
            return@withContext
        }

        sendNowPlayingBroadcast(context, title, channelName)
        WebViewPlaybackService.playStream(context, playUrl, title)
        onReady?.invoke(title)
    }

    /** 发送正在播放广播 */
    private fun sendNowPlayingBroadcast(context: Context, title: String, channel: String) {
        val appContext = context.applicationContext
        val intent = Intent("NOW_PLAYING_UPDATE").apply {
            putExtra("stationName", channel)
            putExtra("title", title)
            setPackage(appContext.packageName)
        }
        appContext.sendBroadcast(intent)
    }

    /** 处理错误 */
    private suspend fun handleError(
        context: Context,
        errorMsg: String,
        onError: ((String) -> Unit)?
    ) = withContext(Dispatchers.Main) {
        onError?.invoke(errorMsg)
    }

    /** 校验 URL 是否有效 */
    private fun isValidStreamUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    /** 校验是否是 DASH 清单 URL */
    private fun isFilteredUrl(url: String): Boolean {
        return FILTERED_URL_PREFIXES.any { prefix -> url.startsWith(prefix) }
    }

    /** 外部可调用停止播放 */
    fun stop(context: Context) {
        WebViewPlaybackService.stop(context)
    }
}