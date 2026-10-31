package com.vscode.desktop.standalone

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen in-app WebView:
 *  - Mode 1 -> noVNC (http://127.0.0.1:6080/vnc.html...) — Electron desktop
 *  - Mode 2 -> http://127.0.0.1:8080/?folder=/root/Workspace — VS Code web workbench
 *
 * Agar inbuilt WebView fail ho (WebView hi na bane, page load na ho, ya render process crash ho)
 * to automatic browser intent (ACTION_VIEW) se wahi URL khol diya jata hai.
 */
class WorkbenchActivity : AppCompatActivity() {

    private lateinit var url: String
    private lateinit var mode: String
    private lateinit var prootManager: ProotProcessManager

    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var fellBack = false

    private val isMode2: Boolean get() = mode == MainActivity.MODE_DIRECT

    /** Mode 2 idle-watcher ko batata hai ki user abhi screen par hai (server ko hibernate mat karo). */
    private val heartbeat = object : Runnable {
        override fun run() {
            prootManager.touchMode2Heartbeat()
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private data class Combo(
        val label: String,
        val key: String,
        val code: String,
        val keyCode: Int,
        val ctrl: Boolean = false,
        val shift: Boolean = false
    )

    private val combos = listOf(
        Combo("ESC", "Escape", "Escape", 27),
        Combo("TAB", "Tab", "Tab", 9),
        Combo("Cmd Palette", "P", "KeyP", 80, ctrl = true, shift = true),
        Combo("F1", "F1", "F1", 112),
        Combo("Quick Open", "p", "KeyP", 80, ctrl = true),
        Combo("Terminal", "`", "Backquote", 192, ctrl = true),
        Combo("Save", "s", "KeyS", 83, ctrl = true),
        Combo("Find", "f", "KeyF", 70, ctrl = true)
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prootManager = ProotProcessManager(this)
        url = intent.getStringExtra(EXTRA_URL) ?: "http://127.0.0.1:${ProotProcessManager.MODE2_PORT}/?folder=/root/Workspace"
        mode = intent.getStringExtra(EXTRA_MODE) ?: MainActivity.MODE_DIRECT

        val wv: WebView = try {
            WebView(this)
        } catch (e: Exception) {
            // Inbuilt WebView hi available nahi -> seedha browser
            openInExternalBrowser("WebView available nahi hai")
            finish()
            return
        }
        webView = wv

        wv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true       // VS Code web session / tabs / layout yahin save hota hai
            databaseEnabled = true
            allowFileAccess = false
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            if (!isMode2) {
                useWideViewPort = true
                loadWithOverviewMode = true
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            // Saare links isi WebView mein khulenge
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                // Error page ke baad bhi yeh aata hai; retries sirf tab reset karo jab sach mein load hua ho
                if (!pageFailed) retries = 0
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) onMainFrameFailed()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                openInExternalBrowser("WebView render process crash hua")
                finish()
                return true
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(buildKeyBar())
        root.addView(wv)
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Server chalta rehta hai; Mode 2 10 min idle ke baad khud hibernate ho jayega.
                finish()
            }
        })

        if (savedInstanceState != null) wv.restoreState(savedInstanceState)
        loadMainUrl()
    }

    @Volatile
    private var pageFailed = false

    private fun loadMainUrl() {
        pageFailed = false
        webView?.loadUrl(url)
    }

    private fun onMainFrameFailed() {
        pageFailed = true
        retries++
        if (retries <= MAX_RETRIES) {
            handler.postDelayed({ if (!fellBack) loadMainUrl() }, 1500L)
            return
        }
        // Port check network call hai => background thread (main thread par NetworkOnMainThreadException aata hai)
        Thread {
            val serverAlive = ProotProcessManager.isPortOpen(
                if (isMode2) ProotProcessManager.MODE2_PORT else ProotProcessManager.MODE1_PORT
            )
            runOnUiThread {
                if (isFinishing || fellBack) return@runOnUiThread
                if (isMode2 && !serverAlive) {
                    // Server hibernate ho chuka hai -> MainActivity se automatic relaunch + resume
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(MainActivity.EXTRA_AUTO_LAUNCH, MainActivity.MODE_DIRECT)
                    )
                } else {
                    openInExternalBrowser("In-app WebView load nahi hua")
                }
                finish()
            }
        }.start()
    }

    private fun openInExternalBrowser(reason: String) {
        if (fellBack) return
        fellBack = true
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "$reason — browser mein khol raha hoon", Toast.LENGTH_LONG).show()
        } catch (ignored: ActivityNotFoundException) {
            Toast.makeText(this, "$reason. Browser bhi nahi mila: $url", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Touch developer key bar
    // ------------------------------------------------------------------------------------------

    private fun buildKeyBar(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply { setBackgroundColor(0xFF252526.toInt()) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
        }
        combos.forEach { combo ->
            bar.addView(Button(this).apply {
                text = combo.label
                textSize = 11f
                isAllCaps = false
                setOnClickListener { sendCombo(combo) }
            })
        }
        bar.addView(Button(this).apply {
            text = "Keyboard"
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                webView?.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            }
        })
        bar.addView(Button(this).apply {
            text = "Setup / Logs"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { finish() }
        })
        scroll.addView(bar)
        return scroll
    }

    private fun sendCombo(c: Combo) {
        // Note: '$' ya string template JS mein use nahi hua, isliye Kotlin raw string safe hai.
        val js = """
            (function () {
              var t = document.activeElement || document.body;
              function fire(type) {
                var e = new KeyboardEvent(type, {
                  key: '__KEY__', code: '__CODE__', keyCode: __KC__, which: __KC__,
                  ctrlKey: __CTRL__, shiftKey: __SHIFT__, bubbles: true, cancelable: true, composed: true
                });
                try { Object.defineProperty(e, 'keyCode', { get: function () { return __KC__; } }); } catch (x) {}
                try { Object.defineProperty(e, 'which', { get: function () { return __KC__; } }); } catch (x) {}
                t.dispatchEvent(e);
              }
              fire('keydown'); fire('keyup');
            })();
        """.trimIndent()
            .replace("__KEY__", c.key)
            .replace("__CODE__", c.code)
            .replace("__KC__", c.keyCode.toString())
            .replace("__CTRL__", c.ctrl.toString())
            .replace("__SHIFT__", c.shift.toString())
        webView?.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle: Mode 2 battery optimisation (Mode 1 par koi throttling nahi)
    // ------------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        if (isMode2) {
            handler.removeCallbacks(heartbeat)
            handler.post(heartbeat)
        }
    }

    override fun onPause() {
        if (isMode2) {
            handler.removeCallbacks(heartbeat)
            try {
                // Background mein WebView timers/animations band => zero background timer drain
                webView?.evaluateJavascript(
                    "window.dispatchEvent(new Event('blur'));" +
                        "document.dispatchEvent(new Event('visibilitychange'));",
                    null
                )
                webView?.onPause()
            } catch (ignored: Exception) {
            }
        }
        CookieManager.getInstance().flush() // session / storage disk par save
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_MODE = "mode"
        private const val MAX_RETRIES = 20          // 20 x 1.5s = ~30s
        private const val HEARTBEAT_MS = 30_000L
    }
}
