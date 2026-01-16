package com.zhangjq0908.iradio.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.RandomAccessFile
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import okhttp3.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat

object PodcastDownloadManager {

    private const val TAG = "PodcastDownloadManager"
    const val ACTION_DOWNLOAD_FINISHED = "com.example.iradio.DOWNLOAD_FINISHED"
    const val EXTRA_EPISODE_TITLE = "episode_title"
    const val EXTRA_LOCAL_PATH = "local_path"

    /* ================= 文件工具 ================= */

    fun getEpisodeFile(context: Context, title: String): File {
        val safeName = title.replace(Regex("[^a-zA-Z0-9一-龥]"), "_")
        return File(context.getExternalFilesDir("podcast"), "$safeName.m4a")
    }

    fun isDownloaded(context: Context, title: String): File? {
        val f = getEpisodeFile(context, title)
        return if (f.exists()) f else null
    }

    /* ================= 下载入口 ================= */

    fun download(context: Context, episodeTitle: String, episodeUrl: String) {
        val file = getEpisodeFile(context, episodeTitle)
        if (file.exists()) {
            onDownloadFinished(context, episodeTitle, file)
            return
        }

        when (resolveDownloadType(episodeUrl)) {
            DownloadType.DIRECT_AUDIO ->
                downloadWithDownloadManager(context, episodeTitle, episodeUrl, file)
            DownloadType.GOOGLEVIDEO ->
                downloadWithOkHttp(context, episodeTitle, episodeUrl, file)
            DownloadType.UNKNOWN ->
                Log.w(TAG, "无法识别的下载链接：$episodeUrl")
        }
    }

    /* ================= 类型识别 ================= */

    private enum class DownloadType {
        DIRECT_AUDIO,
        GOOGLEVIDEO,
        UNKNOWN
    }

    private fun resolveDownloadType(url: String): DownloadType {
        val u = url.lowercase()
        if (u.matches(Regex(".*\\.(mp3|m4a|aac)(\\?.*)?$"))) return DownloadType.DIRECT_AUDIO
        if (u.contains("googlevideo.com/videoplayback")) return DownloadType.GOOGLEVIDEO
        return DownloadType.UNKNOWN
    }

    /* ================= 普通播客 ================= */

    private fun downloadWithDownloadManager(
        context: Context,
        title: String,
        url: String,
        file: File
    ) {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(title)
            setDescription("正在下载播客")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(file))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        pollDownloadStatus(context, dm, id, title, file)
    }

    private fun pollDownloadStatus(
        context: Context,
        dm: DownloadManager,
        id: Long,
        title: String,
        file: File
    ) {
        val handler = Handler(Looper.getMainLooper())
        handler.post(object : Runnable {
            override fun run() {
                dm.query(DownloadManager.Query().setFilterById(id))?.use {
                    if (it.moveToFirst()) {
                        when (it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                onDownloadFinished(context, title, file)
                                return
                            }
                            DownloadManager.STATUS_FAILED -> {
                                Log.w(TAG, "DownloadManager 失败：$title")
                                return
                            }
                        }
                    }
                }
                handler.postDelayed(this, 500)
            }
        })
    }

    /* ================= googlevideo 加速下载 ================= */

    private val downloadExecutor = Executors.newFixedThreadPool(2)
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 10, TimeUnit.MINUTES))
            .dispatcher(Dispatcher(downloadExecutor).apply {
                maxRequests = 4
                maxRequestsPerHost = 2
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun downloadWithOkHttp(context: Context, episodeTitle: String, url: String, file: File) {
        downloadExecutor.execute {
            try {
                val chunkSize = 2L * 1024 * 1024
                val maxRetry = 3
                var downloaded = if (file.exists()) file.length() else 0L
                var totalLength = -1L
                var finished = false

                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(downloaded)
                    while (!finished) {
                        val end = downloaded + chunkSize - 1
                        var attempt = 0
                        var success = false

                        while (attempt < maxRetry && !success) {
                            attempt++
                            try {
                                val request = Request.Builder()
                                    .url(url)
                                    .header("User-Agent", "Mozilla/5.0 (Android 13; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0")
                                    .header("Accept", "*/*")
                                    .header("Accept-Encoding", "identity")
                                    .header("Connection", "keep-alive")
                                    .header("Range", "bytes=$downloaded-$end")
                                    .build()

                                val response = okHttpClient.newCall(request).execute()
                                if (response.code != 206 && response.code != 200) {
                                    response.close()
                                    throw RuntimeException("HTTP ${response.code}")
                                }

                                if (totalLength <= 0) {
                                    response.header("Content-Range")?.let {
                                        val slash = it.lastIndexOf('/')
                                        if (slash > 0) totalLength = it.substring(slash + 1).toLongOrNull() ?: -1
                                    }
                                }

                                val body = response.body ?: run {
                                    response.close()
                                    throw RuntimeException("empty body")
                                }

                                val bytes = body.bytes()
                                response.close()
                                if (bytes.isEmpty()) throw RuntimeException("empty chunk")

                                raf.write(bytes)
                                downloaded += bytes.size
                                success = true
                                Log.i(TAG, "chunk ok: ${downloaded / 1024 / 1024}MB / ${if (totalLength > 0) totalLength / 1024 / 1024 else "?"}MB")

                            } catch (e: Exception) {
                                Log.w(TAG, "chunk 失败（第 $attempt 次），${e.message}")
                                Thread.sleep(500L * attempt)
                            }
                        }

                        if (!success) {
                            Log.e(TAG, "块下载失败，已达最大重试")
                            break
                        }

                        if (totalLength > 0 && downloaded >= totalLength) finished = true
                    }
                }

                if (totalLength > 0 && file.length() != totalLength) {
                    Log.e(TAG, "文件校验失败：${file.length()} / $totalLength")
                    return@execute
                }

                Handler(Looper.getMainLooper()).post {
                    onDownloadFinished(context, episodeTitle, file)
                    showDownloadNotification(context, episodeTitle, file)
                }

            } catch (e: Exception) {
                Log.e(TAG, "googlevideo 下载异常", e)
            }
        }
    }

    private fun showDownloadNotification(context: Context, title: String, file: File) {
        val channelId = "podcast_download"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "播客下载", NotificationManager.IMPORTANCE_DEFAULT))
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(title)
            .setAutoCancel(true)
            .build()

        nm.notify(file.hashCode(), notification)
    }

    /* ================= 完成 ================= */

    private fun onDownloadFinished(context: Context, title: String, file: File) {
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(ACTION_DOWNLOAD_FINISHED).apply {
                putExtra(EXTRA_EPISODE_TITLE, title)
                putExtra(EXTRA_LOCAL_PATH, file.absolutePath)
            }
        )
        Log.i(TAG, "下载完成: ${file.absolutePath}")
    }
}
