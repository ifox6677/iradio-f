package com.zhangjq0908.iradio


import java.util.concurrent.Executors

/**
 * 受控线程池，避免每次解析 PLS 都新建线程
 */
object PlsExecutor {
    val executor = Executors.newSingleThreadExecutor()
}
