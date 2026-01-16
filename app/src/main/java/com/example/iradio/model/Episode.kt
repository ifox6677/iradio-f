package com.zhangjq0908.iradio.model

import com.zhangjq0908.iradio.download.DownloadState

data class Episode(

    /* ===== 固定内容（RSS / 网络） ===== */
    val title: String,
    val url: String,
    val duration: String,
    val pubDate: String,
    val pubDateTime: Long,

    /* ===== 可补充内容 ===== */
    var thumbUrl: String = "",
    var description: String = "",
    var durationInSeconds: Int = 0,
    var durationInMillis: Long = 0L,
	   

    /* ===== 本地 / 下载状态 ===== */
    var localPath: String? = null,
    var downloadState: DownloadState = DownloadState.NONE
)
