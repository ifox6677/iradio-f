package com.zhangjq0908.iradio
import android.os.Handler
import android.os.Looper

import android.annotation.SuppressLint
import android.content.*
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class WebViewActivity : AppCompatActivity() {

    companion object {
        private var lastGrabbedUrl: String? = null

        @JvmStatic
        fun play(context: Context, url: String, title: String) {
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra("url", url)
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var webView: WebView
    private lateinit var titleBar: TextView
    private lateinit var grabButton: TextView
    private lateinit var stopButton: TextView
    private var currentTitle: String = "网页电台"
    // 状态定义：0=未开始 1=抓流中 2=已抓到 3=播放中
    private var grabState = 0

    private var grabRetryCount = 0
    private val maxGrabRetry = 2
    private val grabTimeoutMillis = 2000L // 2秒超时
    private var lastPageUrl: String? = null
    private val webViewCleanupHandler = Handler(Looper.getMainLooper())
    
    // Runnable 要保存引用，方便取消
    private val delayedFreezeRunnable = Runnable {
        // ⚠️ 再次确认仍在播放
        if (grabState == 3 && WebViewPlaybackService.isPlaying()) {
            freezeWebView()
        }
    }	

    // 添加本地广播管理器
    private lateinit var localBroadcastManager: LocalBroadcastManager

    // ------------------ Broadcast Receivers -------------------
    private val destroyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = finish()
    }

    private val stopGrabReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                releaseOldGrab()
                webView.evaluateJavascript(
                    "document.querySelectorAll('audio,video').forEach(e=>{e.pause();});", null
                )
            }
        }
    }
    private fun freezeWebView() {
        if (!::webView.isInitialized) return
    
        // 1️⃣ 彻底停止加载
        webView.stopLoading()
    
        // 2️⃣ 停止所有音视频
        webView.evaluateJavascript("""
            (function(){
                document.querySelectorAll('audio,video').forEach(function(e){
                    e.pause();
                    e.src = '';
                    e.load();
                });
            })();
        """.trimIndent(), null)
    
        // 3️⃣ 清空页面
        //webView.loadUrl("about:blank")
    
        // 4️⃣ 可选：暂停 JS timers，进一步省电
        //
		webView.onPause()
        webView.pauseTimers()
    }

    private val noGrabDomains = listOf(
        "881903.com",
        "live.881903.com"
    )
    private fun isNoGrabPage(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return url.contains("881903", ignoreCase = true)
    }
  
    // 播放器状态广播接收器 - 更新以匹配ExoPlayer发送的格式
    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra("state", -1)
            val isPlaying = intent?.getBooleanExtra("isPlaying", false)
            val stateName = intent?.getStringExtra("stateName")
            
            
            runOnUiThread {
                handlePlayerStateChange(state, isPlaying, stateName)
            }
        }
    }
    
    // 处理播放器状态变化
    private fun handlePlayerStateChange(state: Int?, isPlaying: Boolean?, stateName: String?) {
        
        when (state) {
            3 -> { // READY
                if (isPlaying == true) {
                    // 播放中 → 启动 / 重置倒计时
					grabState = 3
                    updateButtonUI()
					stopAllWebPlayback()
                    webViewCleanupHandler.removeCallbacks(delayedFreezeRunnable)
                    webViewCleanupHandler.postDelayed(
                        delayedFreezeRunnable,
                        10 * 60 * 1000L
                    )
                    if (grabState == 1) {
                        //Toast.makeText(this, "播放器开始播放", Toast.LENGTH_SHORT).show()
                        stopGrabbing()
						
                    }
                }
            }
        
            1, 4 -> { // 状态1：空闲，状态4：播放结束
                // 播放停止，可以重新允许抓流
                if (grabState == 3) {
                    grabState = 0
                    updateButtonUI()
					stopAllWebPlayback()
                    webViewCleanupHandler.removeCallbacks(delayedFreezeRunnable)
                   
                }
            }

        }
    }
    // ===================== Activity Lifecycle =====================
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化本地广播管理器
        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        // 注册广播接收器
        registerSafeReceiver(destroyReceiver, "DESTROY_WEBVIEW")
        registerSafeReceiver(stopGrabReceiver, "STOP_WEB_GRAB")
        
        // 注册播放器状态广播（使用本地广播）
        localBroadcastManager.registerReceiver(playerStateReceiver, 
            IntentFilter("PLAYER_STATE_CHANGED"))

        // ---------- UI初始化 ----------
        titleBar = TextView(this).apply {
            text = "加载中..."
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(80, 0, 200, 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, 160, 1f)
        }

        grabButton = TextView(this).apply {
            text = "抓源"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(44, 0, 44, 0)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 120
            ).apply { setMargins(0, 0, 20, 0) }
            setOnClickListener {
                handleGrabButtonClick()
            }
            setOnLongClickListener {
                lastGrabbedUrl?.let {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("音频流地址", it))
                    //Toast.makeText(this@WebViewActivity, "已复制流地址", Toast.LENGTH_SHORT).show()
                } ?: Toast.makeText(this@WebViewActivity, "尚未抓到流", Toast.LENGTH_SHORT).show()
                true
            }
        }

        stopButton = TextView(this).apply {
            text = "停止"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(44, 0, 44, 0)
            setBackgroundColor(Color.parseColor("#F44336"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 120
            ).apply { setMargins(0, 0, 40, 0) }
            setOnClickListener {
			    webViewCleanupHandler.removeCallbacks(delayedFreezeRunnable)
                WebViewPlaybackService.stop(this@WebViewActivity)
                finish()
            }
        }

        // 标题栏容器
        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#2196F3"))
            addView(titleBar)
            addView(grabButton)
            addView(stopButton)
        }

        // WebView
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
        }

        // 主布局
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleContainer, LinearLayout.LayoutParams.MATCH_PARENT, 130)
            addView(webView)
            setContentView(this)
        }

        setupWebView()
        handleIntent(intent)
        
        // 初始检查ExoPlayer状态
        checkInitialExoPlayerState()
    }

    private fun registerSafeReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }
    
    // 检查ExoPlayer初始状态
    private fun checkInitialExoPlayerState() {
        // 如果ExoPlayer正在播放，初始状态设为播放中
        if (WebViewPlaybackService.isPlaying()) {
            grabState = 3
            updateButtonUI()
           
        }
    }
    
    // 处理抓流按钮点击
    private fun handleGrabButtonClick() {

        
        when (grabState) {
            0 -> startGrabbing()      // 未开始 → 开始抓流
            1 -> stopGrabbing()       // 抓流中 → 停止抓流
            2 -> restartGrabbing()    // 已抓到 → 重新抓流
            3 -> {                    // 播放中 → 先停止播放器再抓流
                // 停止播放器
                WebViewPlaybackService.stop(this)
                // 延迟更新状态，等待播放器状态变化广播
                webView.postDelayed({
                    grabState = 0
                    updateButtonUI()
                    //Toast.makeText(this, "已停止播放器，可以开始抓流", Toast.LENGTH_SHORT).show()
                   
                }, 300)
            }
        }
    }

    // ===================== 抓流逻辑 =====================
    private fun startGrabbing() {
        if (isNoGrabPage(lastPageUrl)) {
            Toast.makeText(this, "该网页不支持抓流", Toast.LENGTH_SHORT).show()
            grabState = 0
            updateButtonUI()
            return
        }
       
        
        // 检查是否正在播放
        if (grabState == 3) {
           
            return
        }
        
        //releaseOldGrab()
		injectGrabScript()
        grabState = 1
        updateButtonUI()
		
        Toast.makeText(this, "开始抓流…", Toast.LENGTH_SHORT).show()
        
        checkGrabResultWithTimeout()
    }

    private fun checkGrabResultWithTimeout() {
        val initialRetry = grabRetryCount
        webView.postDelayed({
           
            
            // 检查是否正在播放
            if (grabState == 3) {
               
                return@postDelayed
            }
            
            if (grabState != 2 && grabRetryCount == initialRetry && grabRetryCount < maxGrabRetry) {
                grabRetryCount++
                //Toast.makeText(this, "抓流未成功，重新尝试 (${grabRetryCount}) …", Toast.LENGTH_SHORT).show()
                grabState = 0
                releaseOldGrab()
                grabState = 1
                updateButtonUI()
                injectGrabScript()
                checkGrabResultWithTimeout()
            }
        }, grabTimeoutMillis)
    }

    private fun stopGrabbing() {
       
        releaseOldGrab()
        grabState = 0
        updateButtonUI()
       
    }

    private fun restartGrabbing() {
       
        
        // 检查是否正在播放
        if (grabState == 3) {
           
            return
        }
        
        releaseOldGrab()
        grabState = 1
        updateButtonUI()
        Toast.makeText(this, "已释放旧流，重新抓源中…", Toast.LENGTH_SHORT).show()
        injectGrabScript()
        checkGrabResultWithTimeout()
    }

    private fun releaseOldGrab() {
        try {
            lastGrabbedUrl = null
            // 只有不在播放状态时才重置为0
            if (grabState != 3) {
                grabState = 0
            }
            updateButtonUI()
            webView.evaluateJavascript("""
                (function(){
                    window.__grabInjected = false;
                    document.querySelectorAll('audio,video').forEach(function(e){
                        e.pause(); e.muted = true; e.removeAttribute('src'); e.load();
                    });
                })();
            """.trimIndent(), null)
           
        } catch (e: Exception) {
        }
    }

    @Synchronized
    private fun onAudioGrabbed(url: String) {
        if (isNoGrabPage(lastPageUrl)) {

            return
        }	
       
        
        // 检查状态，只有抓流中才处理
        if (grabState != 1) {
           
            return
        }
        
        // 验证音频流URL
        if (!isValidAudioStream(url)) {
           
            return
        }
        
        lastGrabbedUrl = url
        grabState = 2
        grabRetryCount = 0
        updateButtonUI()
        Toast.makeText(this, "已抓到媒体流，切换到独立播放器", Toast.LENGTH_SHORT).show()
        WebViewPlaybackService.playStream(this, url, currentTitle)

    }
    private fun disableNetworkHooks() {
        webView.evaluateJavascript("""
            (function(){
                try {
                    if (window.fetch) delete window.fetch;
                    if (XMLHttpRequest && XMLHttpRequest.prototype.open) {
                        delete XMLHttpRequest.prototype.open;
                    }
                } catch(e){}
            })();
        """.trimIndent(), null)
    }
		
    
    // 音频流验证
    private fun isValidAudioStream(url: String): Boolean {
        if (url.isBlank()) return false
        
        // 排除网页URL
        val invalidPatterns = listOf(
            "\\.html$", "\\.htm$", "\\.aspx$", "\\.php$",
            "youtube\\.com/watch", "youtu\\.be/", "bilibili\\.com/video"
        )
        
        invalidPatterns.forEach { pattern ->
            if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)) {
                return false
            }
        }
        
        // 必须是音频流URL
        return isAudioUrl(url)
    }
    private fun stopAllWebPlayback() {
        if (!::webView.isInitialized) return
        
        webView.evaluateJavascript("""
            (function() {
                // 1. 停止所有音视频元素
                document.querySelectorAll('audio, video').forEach(function(media) {
                    media.pause();
                    media.currentTime = 0;
                    media.src = '';
                    media.srcObject = null;
                    media.load();
                });
                
                // 2. 停止 WebAudio API
                if (window.AudioContext || window.webkitAudioContext) {
                    try {
                        var AudioContext = window.AudioContext || window.webkitAudioContext;
                        if (window.__audioContextInstances) {
                            window.__audioContextInstances.forEach(function(ctx) {
                                ctx.close && ctx.close();
                            });
                        }
                    } catch(e) {}
                }
                
                // 3. 移除所有相关的事件监听器（可选）
               /* document.querySelectorAll('audio, video').forEach(function(media) {
                    var clone = media.cloneNode(false);
                    media.parentNode.replaceChild(clone, media);
                }); */
                
                // 4. 清除媒体会话（iOS/Safari）
                if (navigator.mediaSession && navigator.mediaSession.playbackState) {
                    navigator.mediaSession.playbackState = 'none';
                }
                
                // 5. 停止所有正在进行的 fetch/XMLHttpRequest
                if (window.__originalFetch) {
                    window.fetch = window.__originalFetch;
                }
                
                console.log('Web playback stopped completely');
            })();
        """.trimIndent(), null)
    }	

    private fun injectGrabScript() {
        if (isNoGrabPage(lastPageUrl)) {
            return
        }
	
        val js = """
            (function(){
                if(window.__grabInjected) return;
                window.__grabInjected = true;
                function safeReport(url){ try{ if(url) AndroidAudioGrab.onAudioUrl(url); } catch(e){} }
                document.querySelectorAll('audio,video').forEach(function(el){
                    if(el.currentSrc) safeReport(el.currentSrc);
                });
                new MutationObserver(function(muts){
                    muts.forEach(function(m){
                        Array.from(m.addedNodes).forEach(function(n){
                            if(n.nodeName==='AUDIO'||n.nodeName==='VIDEO'){ if(n.currentSrc) safeReport(n.currentSrc); }
                        });
                    });
                }).observe(document.body,{childList:true,subtree:true});
                var origXHR = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method,url){
                    if(url) safeReport(url); 
                    return origXHR.apply(this,arguments);
                };
                if(window.fetch){
                    var origFetch = window.fetch;
                    window.fetch = function(req){
                        try{ var u = typeof req==='string'?req:req.url; if(u) safeReport(u); } catch(e){}
                        return origFetch.apply(this,arguments);
                    }
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        
    }

    private fun updateButtonUI() {
        when (grabState) {
            0 -> { 
                grabButton.text = "硬解"
                grabButton.setBackgroundColor(Color.parseColor("#4CAF50"))
               
            }
            1 -> { 
                grabButton.text = "停止抓流"
                grabButton.setBackgroundColor(Color.parseColor("#FF5722"))
                
            }
            2 -> { 
                grabButton.text = "重新抓流"
                grabButton.setBackgroundColor(Color.parseColor("#FF9800"))
                
            }
            3 -> { // 播放中状态
                grabButton.text = "播放中"
                grabButton.setBackgroundColor(Color.parseColor("#9C27B0"))
                
            }
        }
    }

    // ===================== WebView 设置 =====================
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Android 13; Mobile; rv:125.0) Gecko/125.0 Firefox/125.0"
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.isSoundEffectsEnabled = false
        try {
            val method = WebView::class.java.getDeclaredMethod("setAudioMuted", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(webView, true)
        } catch (_: Exception) {}

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onAudioUrl(url: String) {
                if (grabState == 1 && url.isNotBlank() && isAudioUrl(url)) onAudioGrabbed(url)
            }
        }, "AndroidAudioGrab")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (grabState == 3) {
                   
                    return
                }
                super.onPageFinished(view, url)
				lastPageUrl = url
                if (isNoGrabPage(lastPageUrl)) {
                    //Toast.makeText(this@WebViewActivity, "该网页不支持抓流", Toast.LENGTH_SHORT).show()
                    grabState = 0
                    updateButtonUI()
                    return
                }				
                releaseOldGrab()
				injectGrabScript()
                if (grabState == 0) {
                   grabRetryCount = 0
                   webView.postDelayed({
                       // 二次确认：仍然空闲 & 未播放
                       if (grabState == 0 && !WebViewPlaybackService.isPlaying()) {
                           startGrabbing()
                       }
                   }, 3000)
				    
                }
                
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                currentTitle = intent.getStringExtra("title") ?: title ?: "网页电台"
                titleBar.text = currentTitle
            }
        }
    }

    // ===================== URL 处理 =====================
    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra("url") ?: return
        currentTitle = intent.getStringExtra("title") ?: "网页电台"
        titleBar.text = currentTitle
        webView.loadUrl(url)
    }

    private fun isAudioUrl(url: String) = url.run {
        contains(".m3u8", true) || contains(".mp3", true) || contains(".m4a", true) ||
        contains(".aac", true) || contains("/audio/", true) || contains("rcs.revma.com", true) ||
        contains(".cnr.cn", true) || contains("rsc.cdn77.org", true) || contains("stream", true) || 
		contains("tencentplay.gztv.com", true) || contains("radio", true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ===================== 生命周期 =====================
    override fun onResume() {
        super.onResume()
        // 恢复时检查播放器状态
        if (WebViewPlaybackService.isPlaying()) {
            grabState = 3
            updateButtonUI()
        }
    }

    override fun onDestroy() {
	    webViewCleanupHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(stopGrabReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(destroyReceiver) } catch (_: Exception) {}
        try { localBroadcastManager.unregisterReceiver(playerStateReceiver) } catch (_: Exception) {}
        webView.takeIf { ::webView.isInitialized }?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.settings.javaScriptEnabled = false
            wv.clearHistory()
            wv.clearCache(true)
            wv.loadUrl("about:blank")
            wv.removeAllViews()
            wv.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else {
            WebViewPlaybackService.stop(this)
            super.onBackPressed()
        }
    }
    

}