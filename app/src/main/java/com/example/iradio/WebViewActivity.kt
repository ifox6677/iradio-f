package com.example.iradio

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

class WebViewActivity : AppCompatActivity() {

    companion object {
        private var lastGrabbedUrl: String? = null

        @JvmStatic
        fun play(context: Context, url: String, title: String) {
            WebViewPlaybackService.playStream(context, url, title)
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
    private var grabState = 0 // 0=未开始 1=抓流中 2=已抓到

    private var grabRetryCount = 0
    private val maxGrabRetry = 3
    private val grabTimeoutMillis = 2000L // 2秒超时

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

    // ===================== Activity Lifecycle =====================
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerSafeReceiver(destroyReceiver, "DESTROY_WEBVIEW")
        registerSafeReceiver(stopGrabReceiver, "STOP_WEB_GRAB")

        // ---------- UI ----------
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
                when (grabState) {
                    0 -> startGrabbing()
                    1 -> stopGrabbing()
                    2 -> releaseAndRestartGrabbing()
                }
            }
            setOnLongClickListener {
                lastGrabbedUrl?.let {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("音频流地址", it))
                    Toast.makeText(this@WebViewActivity, "已复制流地址", Toast.LENGTH_SHORT).show()
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
                WebViewPlaybackService.stop(this@WebViewActivity)
                finish()
            }
        }

        val titleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#2196F3"))
            addView(titleBar)
            addView(grabButton)
            addView(stopButton)
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
        }

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleContainer, LinearLayout.LayoutParams.MATCH_PARENT, 130)
            addView(webView)
            setContentView(this)
        }

        setupWebView()
        handleIntent(intent)
    }

    private fun registerSafeReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    // ===================== 抓流逻辑 =====================
    private fun startGrabbing() {
        releaseOldGrab()
        grabState = 1
        updateButtonUI()
        Toast.makeText(this, "开始抓流…", Toast.LENGTH_SHORT).show()
        injectGrabScript()
        checkGrabResultWithTimeout()
    }

    private fun checkGrabResultWithTimeout() {
        val initialRetry = grabRetryCount
        webView.postDelayed({
            if (grabState != 2 && grabRetryCount == initialRetry && grabRetryCount < maxGrabRetry) {
                grabRetryCount++
                Toast.makeText(this, "抓流未成功，重新尝试 (${grabRetryCount}) …", Toast.LENGTH_SHORT).show()
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
        Toast.makeText(this, "已停止抓流", Toast.LENGTH_SHORT).show()
    }

    private fun releaseAndRestartGrabbing() {
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
            grabState = 0
            updateButtonUI()
            webView.evaluateJavascript("""
                (function(){
                    window.__grabInjected = false;
                    document.querySelectorAll('audio,video').forEach(function(e){
                        e.pause(); e.muted = true; e.removeAttribute('src'); e.load();
                    });
                })();
            """.trimIndent(), null)
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun onAudioGrabbed(url: String) {
        if (grabState != 1) return
        lastGrabbedUrl = url
        grabState = 2
        grabRetryCount = 0
        updateButtonUI()
        Toast.makeText(this, "已切换到独立播放器", Toast.LENGTH_SHORT).show()
        WebViewPlaybackService.playStream(this, url, currentTitle)
    }

    private fun injectGrabScript() {
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
            0 -> { grabButton.text = "开始抓流"; grabButton.setBackgroundColor(Color.parseColor("#4CAF50")) }
            1 -> { grabButton.text = "停止抓流"; grabButton.setBackgroundColor(Color.parseColor("#FF5722")) }
            2 -> { grabButton.text = "重新抓流"; grabButton.setBackgroundColor(Color.parseColor("#FF9800")) }
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
                super.onPageFinished(view, url)
                releaseOldGrab()
                if (grabState == 0) {
                    grabRetryCount = 0
                    webView.postDelayed({ if (grabState == 0) startGrabbing() }, 1000)
                }
                injectGrabScript()
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
        contains("rsc.cdn77.org", true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ===================== 生命周期 =====================
    override fun onDestroy() {
        try { unregisterReceiver(stopGrabReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(destroyReceiver) } catch (_: Exception) {}
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
