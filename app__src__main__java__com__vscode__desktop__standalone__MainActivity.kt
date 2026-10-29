package com.vscode.desktop.standalone

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Self-contained Android Activity that:
 * 1. Provisions the embedded ARM64 Debian rootfs + Official Microsoft VS Code inside filesDir.
 * 2. Launches VS Code Desktop inside libproot.so (either Full Electron X11 Desktop via Xtigervnc+noVNC
 *    or Direct Official Workbench via `code serve-web`).
 * 3. Renders the live VS Code Desktop workbench directly inside the APK with a built-in developer
 *    shortcut keybar (Esc, Tab, Ctrl+P, Ctrl+Shift+P, Terminal toggle, Save) and hardware keyboard/mouse support.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prootManager: ProotProcessManager
    private lateinit var provisioner: StandaloneProvisioner

    private lateinit var setupContainer: ScrollView
    private lateinit var workbenchContainer: LinearLayout
    private lateinit var workbenchSurface: WebView
    private lateinit var logView: TextView
    private lateinit var workspaceInput: EditText
    private lateinit var modeGroup: RadioGroup

    private var activeLinuxProcess: Process? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val resolved = resolveUriToPath(it)
            workspaceInput.setText(resolved)
            appendLog("Selected Android Project Folder: $resolved")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prootManager = ProotProcessManager(this)
        provisioner = StandaloneProvisioner(this, prootManager)

        buildSelfContainedUi()
        checkInitialState()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildSelfContainedUi() {
        val rootFrame = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // --- PART A: SETUP & CONTROL PANEL ---
        setupContainer = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val setupLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val header = TextView(this).apply {
            text = "VS Code Desktop (Standalone APK — No Termux Required)"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        }
        setupLayout.addView(header)

        // Display Mode selector
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val rbX11 = RadioButton(this@MainActivity).apply {
                id = 1001
                text = "Mode 1: Full Electron Desktop X11 Window (Xtigervnc + Openbox + /opt/vscode-arm64/code)"
                isChecked = true
            }
            val rbDirect = RadioButton(this@MainActivity).apply {
                id = 1002
                text = "Mode 2: High-FPS Native Workbench Host (/opt/vscode-arm64/bin/code serve-web)"
            }
            addView(rbX11)
            addView(rbDirect)
        }
        setupLayout.addView(modeGroup)

        // Workspace folder input
        val wsLabel = TextView(this).apply {
            text = "Project Folder (Bound to /root/Workspace):"
            setPadding(0, 20, 0, 8)
        }
        setupLayout.addView(wsLabel)

        val defaultProjects = File(Environment.getExternalStorageDirectory(), "VSCodeProjects").absolutePath
        workspaceInput = EditText(this).apply {
            setText(defaultProjects)
        }
        setupLayout.addView(workspaceInput)

        val btnPickFolder = Button(this).apply {
            text = "1. Select Android Project Folder"
            setOnClickListener {
                requestStorageAccess()
                folderPicker.launch(null)
            }
        }
        setupLayout.addView(btnPickFolder)

        val btnInstall = Button(this).apply {
            text = "2. Download & Install Embedded Linux + Official VS Code"
            setOnClickListener { startStandaloneProvisioning() }
        }
        setupLayout.addView(btnInstall)

        val btnVerify = Button(this).apply {
            text = "3. Verify Embedded VS Code, Terminal (node-pty), Git & Extensions"
            setOnClickListener { runStandaloneVerification() }
        }
        setupLayout.addView(btnVerify)

        val btnLaunch = Button(this).apply {
            text = "4. Launch Genuine VS Code Desktop Inside This App"
            setOnClickListener { launchEmbeddedVSCode() }
        }
        setupLayout.addView(btnLaunch)

        logView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF141414.toInt())
            setTextColor(0xFF00E676.toInt())
            text = "Ready. Zero external dependencies (libproot.so bundled in APK)."
        }
        setupLayout.addView(logView)
        setupContainer.addView(setupLayout)

        // --- PART B: EMBEDDED WORKBENCH SURFACE + TOUCH KEYBAR ---
        workbenchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Touch-friendly Developer Bar for Phones/Tablets without hardware keyboards
        val keyBarScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(0xFF252526.toInt())
        }
        val keyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
        }

        val keys = listOf(
            "ESC" to "Escape",
            "TAB" to "Tab",
            "Cmd Palette (F1)" to "F1",
            "Quick Open" to "Control+P",
            "Terminal" to "Control+Backquote",
            "Save" to "Control+S",
            "Find" to "Control+F",
            "Setup / Logs" to "SWITCH_PANEL"
        )
        keys.forEach { (label, keyAction) ->
            val btn = Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener {
                    if (keyAction == "SWITCH_PANEL") {
                        workbenchContainer.visibility = View.GONE
                        setupContainer.visibility = View.VISIBLE
                    } else {
                        injectKeyIntoWorkbench(keyAction)
                    }
                }
            }
            keyBar.addView(btn)
        }
        keyBarScroll.addView(keyBar)
        workbenchContainer.addView(keyBarScroll)

        workbenchSurface = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }
        }
        workbenchContainer.addView(workbenchSurface)

        rootFrame.addView(setupContainer)
        rootFrame.addView(workbenchContainer)
        setContentView(rootFrame)
    }

    private fun checkInitialState() {
        appendLog("Checking bundled native binaries in ${prootManager.nativeLibDir.absolutePath}...")
        appendLog("libproot.so present: ${prootManager.prootBinary.exists()}")
        appendLog("Embedded Rootfs + VS Code installed: ${prootManager.isRootfsProvisioned()}")
    }

    private fun startStandaloneProvisioning() {
        Thread {
            appendLog("Starting self-contained installation inside APK data storage...")
            val ok = provisioner.provisionAll { msg -> appendLog(msg) }
            if (ok) {
                appendLog("SUCCESS! Genuine Microsoft VS Code (linux-arm64) is ready.")
            } else {
                appendLog("Provisioning encountered an error. Check logs above.")
            }
        }.start()
    }

    private fun runStandaloneVerification() {
        Thread {
            appendLog("Running 7-point verification inside embedded Debian ARM64...")
            prootManager.startProotCommand(
                command = "bash /root/verify-vscode.sh",
                workspaceHostPath = workspaceInput.text.toString(),
                onLogLine = { line -> appendLog(line) }
            ).waitFor()
        }.start()
    }

    private fun launchEmbeddedVSCode() {
        val svcIntent = Intent(this, LinuxRuntimeService::class.java)
        ContextCompat.startForegroundService(this, svcIntent)

        val isX11Mode = modeGroup.checkedRadioButtonId == 1001
        val mode = if (isX11Mode) "x11" else "direct"
        val targetUrl = if (isX11Mode) {
            "http://127.0.0.1:6080/vnc.html?autoconnect=true&resize=remote"
        } else {
            "http://127.0.0.1:8000"
        }

        appendLog("Starting genuine VS Code Desktop in mode: $mode ...")
        activeLinuxProcess?.destroy()
        activeLinuxProcess = prootManager.startProotCommand(
            command = "bash /root/start-vscode-session.sh $mode",
            workspaceHostPath = workspaceInput.text.toString(),
            displayMode = mode,
            onLogLine = { line ->
                appendLog(line)
                if (line.contains("VSCODE_DISPLAY_READY")) {
                    runOnUiThread {
                        setupContainer.visibility = View.GONE
                        workbenchContainer.visibility = View.VISIBLE
                        workbenchSurface.loadUrl(targetUrl)
                    }
                }
            }
        )
    }

    private fun injectKeyIntoWorkbench(keySpec: String) {
        val js = "window.dispatchEvent(new KeyboardEvent('keydown', {key: '$keySpec', bubbles: true}));"
        workbenchSurface.evaluateJavascript(js, null)
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun resolveUriToPath(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = docId.split(":")
        return if (parts.size >= 2 && parts[0].equals("primary", ignoreCase = true)) {
            "/storage/emulated/0/${parts[1]}"
        } else {
            File(Environment.getExternalStorageDirectory(), "VSCodeProjects").absolutePath
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logView.append("\n$msg")
        }
    }

    override fun onBackPressed() {
        if (workbenchContainer.visibility == View.VISIBLE) {
            workbenchContainer.visibility = View.GONE
            setupContainer.visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }
}
