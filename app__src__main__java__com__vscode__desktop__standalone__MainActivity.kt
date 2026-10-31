package com.vscode.desktop.standalone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Control panel:
 *  1. User Android project folder select karta hai
 *     -> bina koi aur button dabaye Debian rootfs + VS Code extraction + environment setup
 *        background thread mein apne aap shuru ho jata hai (0% -> 100% loading screen).
 *  2. Setup complete hone par Mode 1 / Mode 2 launch.
 *     Dono modes full-screen WorkbenchActivity (WebView) mein khulte hain.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prootManager: ProotProcessManager
    private lateinit var provisioner: StandaloneProvisioner

    private val prefs by lazy { getSharedPreferences("pocket_vscode", MODE_PRIVATE) }
    private val uiHandler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var folderText: TextView
    private lateinit var btnPick: Button
    private lateinit var loadingGroup: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var statusText: TextView
    private lateinit var btnRetry: Button
    private lateinit var readyGroup: LinearLayout
    private lateinit var modeGroup: RadioGroup
    private lateinit var launchStatus: TextView
    private lateinit var btnLaunch: Button
    private lateinit var logView: TextView

    private val provisioning = AtomicBoolean(false)
    private val launching = AtomicBoolean(false)

    @Volatile
    private var provisionFailed = false

    private val logLines = ArrayDeque<String>()
    private var logFlushPending = false

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onFolderSelected(resolveUriToPath(uri))
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted ya nahi — service dono tarah chalti hai */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prootManager = ProotProcessManager(this)
        provisioner = StandaloneProvisioner(this, prootManager)

        buildUi()
        restoreSavedState()
        askNotificationPermissionOnce()

        appendLog("libproot.so present: ${prootManager.prootBinary.exists()}")
        appendLog("Rootfs + VS Code ready: ${prootManager.isRootfsProvisioned()}")

        // App dobara khula aur folder pehle se selected hai par setup adhoora hai -> auto resume.
        if (hasFolder() && !prootManager.isRootfsProvisioned()) {
            startProvisioningIfNeeded()
        }
        refreshUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // WorkbenchActivity se: Mode 2 hibernate ho chuka tha -> automatic resume.
        intent.getStringExtra(EXTRA_AUTO_LAUNCH)?.let { mode ->
            if (prootManager.isRootfsProvisioned() && hasFolder()) launchMode(mode)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ------------------------------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // Header
        root.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        root.addView(TextView(this).apply {
            text = "Pocket VS Code"
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Genuine Microsoft VS Code (linux-arm64) — standalone, Termux ki zaroorat nahi"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(20))
        })

        // Step 1: Folder
        folderText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(folderText)
        btnPick = makeButton("1. Select Android Project Folder") {
            folderPicker.launch(null)
        }
        root.addView(btnPick)

        // Loading screen (provisioning)
        loadingGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(20), 0, dp(8))
        }
        loadingGroup.addView(TextView(this).apply {
            text = "Setting up Linux + VS Code (sirf ek baar)"
            textSize = 16f
        })
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        loadingGroup.addView(progressBar)
        percentText = TextView(this).apply {
            text = "0%"
            textSize = 28f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        loadingGroup.addView(percentText)
        statusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
        }
        loadingGroup.addView(statusText)
        btnRetry = makeButton("Retry Setup") {
            provisionFailed = false
            startProvisioningIfNeeded()
            refreshUi()
        }.apply { visibility = View.GONE }
        loadingGroup.addView(btnRetry)
        root.addView(loadingGroup)

        // Ready group (launch)
        readyGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(20), 0, 0)
        }
        readyGroup.addView(TextView(this).apply {
            text = "Launch mode"
            textSize = 16f
        })
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@MainActivity).apply {
                id = ID_MODE1
                text = "Mode 1: Full Electron Desktop (X11 via noVNC) — full performance"
            })
            addView(RadioButton(this@MainActivity).apply {
                id = ID_MODE2
                text = "Mode 2: High-FPS Native Web Host (serve-web) — auto-save + battery saver"
            })
        }
        readyGroup.addView(modeGroup)
        btnLaunch = makeButton("Launch VS Code") { launchSelectedMode() }
        readyGroup.addView(btnLaunch)
        launchStatus = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        readyGroup.addView(launchStatus)
        readyGroup.addView(makeButton("Verify (VS Code, Git, Node, Extensions)") { runVerification() })
        readyGroup.addView(makeButton("Stop Session") { stopSession() })
        root.addView(readyGroup)

        // Log
        logView = TextView(this).apply {
            textSize = 11f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFF141414.toInt())
            setTextColor(0xFF00E676.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        root.addView(logView)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun restoreSavedState() {
        val savedMode = prefs.getString(KEY_MODE, MODE_X11)
        modeGroup.check(if (savedMode == MODE_DIRECT) ID_MODE2 else ID_MODE1)
    }

    private fun hasFolder(): Boolean = !prefs.getString(KEY_FOLDER, null).isNullOrEmpty()

    private fun refreshUi() {
        val provisioned = prootManager.isRootfsProvisioned()
        val busy = provisioning.get()
        val folder = prefs.getString(KEY_FOLDER, null)

        folderText.text = if (folder.isNullOrEmpty()) {
            "Project folder: abhi select nahi hua"
        } else {
            "Project folder (→ /root/Workspace):\n$folder"
        }
        btnPick.isEnabled = !busy
        btnPick.text = if (folder.isNullOrEmpty()) "1. Select Android Project Folder" else "Change Project Folder"

        loadingGroup.visibility = if (busy || provisionFailed) View.VISIBLE else View.GONE
        btnRetry.visibility = if (provisionFailed && !busy) View.VISIBLE else View.GONE
        readyGroup.visibility = if (provisioned && !busy && hasFolder()) View.VISIBLE else View.GONE
    }

    private fun setProgress(percent: Int, message: String) {
        if (percent >= 0) {
            progressBar.progress = percent
            percentText.text = "$percent%"
        }
        statusText.text = message
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------------------------------
    // Step 1 + 2: folder select -> automatic background provisioning
    // ------------------------------------------------------------------------------------------

    private fun onFolderSelected(path: String) {
        prefs.edit().putString(KEY_FOLDER, path).apply()
        appendLog("Selected Android Project Folder: $path")
        ensureStorageAccess()
        startProvisioningIfNeeded()
        refreshUi()
    }

    private fun startProvisioningIfNeeded() {
        if (prootManager.isRootfsProvisioned()) {
            refreshUi()
            return
        }
        if (!provisioning.compareAndSet(false, true)) return
        provisionFailed = false

        startRuntimeService(wakeLock = true, title = "Pocket VS Code: setting up...")
        setProgress(0, "Setup shuru ho raha hai...")
        refreshUi()

        Thread {
            val ok = provisioner.provisionAll(
                onProgress = { pct, msg -> runOnUiThread { setProgress(pct, msg) } },
                onLog = { line -> appendLog(line) }
            )
            provisionFailed = !ok
            provisioning.set(false)
            stopService(Intent(this, LinuxRuntimeService::class.java))
            runOnUiThread {
                refreshUi()
                if (ok) toast("Setup 100% complete! Ab VS Code launch kar sakte ho.")
            }
        }.start()
    }

    // ------------------------------------------------------------------------------------------
    // Launch
    // ------------------------------------------------------------------------------------------

    private fun launchSelectedMode() {
        launchMode(if (modeGroup.checkedRadioButtonId == ID_MODE2) MODE_DIRECT else MODE_X11)
    }

    private fun launchMode(mode: String) {
        if (!prootManager.isRootfsProvisioned()) {
            toast("Pehle setup complete hone do.")
            return
        }
        if (!launching.compareAndSet(false, true)) return

        val isX11 = mode == MODE_X11
        prefs.edit().putString(KEY_MODE, mode).apply()
        modeGroup.check(if (isX11) ID_MODE1 else ID_MODE2)

        val workspace = prefs.getString(KEY_FOLDER, null) ?: prootManager.fallbackWorkspace.absolutePath
        val port = if (isX11) ProotProcessManager.MODE1_PORT else ProotProcessManager.MODE2_PORT
        val url = if (isX11) {
            "http://127.0.0.1:$port/vnc.html?autoconnect=true&resize=remote"
        } else {
            // Mode 2: server ready hote hi seedha workspace folder ke saath open hoga.
            "http://127.0.0.1:$port/?folder=/root/Workspace"
        }

        if (isX11) maybeAskIgnoreBatteryOptimizations()

        // Mode 1 = unthrottled (wakelock). Mode 2 = wakelock nahi (battery saver).
        startRuntimeService(
            wakeLock = isX11,
            title = if (isX11) "Pocket VS Code: Mode 1 (Desktop)" else "Pocket VS Code: Mode 2 (Web Host)"
        )
        btnLaunch.isEnabled = false
        launchStatus.text = "Starting ${if (isX11) "Mode 1" else "Mode 2"}..."

        Thread {
            try {
                // Mode 2 server pehle se live hai (hibernate nahi hua) -> turant open.
                if (!isX11 && ProotProcessManager.isPortOpen(port)) {
                    runOnUiThread { openWorkbench(url, mode) }
                    return@Thread
                }

                // Purana session band karo
                ProotProcessManager.activeSession?.let { old ->
                    old.destroyForcibly()
                    try {
                        old.waitFor(3, TimeUnit.SECONDS)
                    } catch (ignored: Exception) {
                    }
                }

                appendLog("Starting genuine VS Code Desktop in mode: $mode ...")
                val proc = prootManager.startProotCommand(
                    command = "bash /root/start-vscode-session.sh ${if (isX11) "x11" else "direct"}",
                    workspaceHostPath = workspace,
                    displayMode = mode,
                    onLogLine = { line -> appendLog(line) }
                )
                ProotProcessManager.activeSession = proc

                // Session khatam ho jaye (e.g. Mode 2 hibernate) to service bhi band => battery drain 0
                Thread {
                    try {
                        proc.waitFor()
                    } catch (ignored: Exception) {
                    }
                    if (ProotProcessManager.activeSession === proc) {
                        ProotProcessManager.activeSession = null
                        // Service ko startForeground() ka mauka dene ke liye thoda ruko
                        try {
                            Thread.sleep(1500)
                        } catch (ignored: Exception) {
                        }
                        stopService(Intent(this, LinuxRuntimeService::class.java))
                        runOnUiThread { launchStatus.text = "Session band ho gaya (ya hibernate hua)." }
                    }
                }.start()

                // Port live hone ka intezaar -> phir automatically WebView mein open
                val deadline = System.currentTimeMillis() + 10 * 60 * 1000L
                val started = System.currentTimeMillis()
                var ready = false
                var lastStatus = 0L
                while (proc.isAlive && System.currentTimeMillis() < deadline) {
                    if (ProotProcessManager.isPortOpen(port, 400)) {
                        ready = true
                        break
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastStatus > 2000) {
                        lastStatus = now
                        val secs = (now - started) / 1000
                        runOnUiThread { launchStatus.text = "Port $port ka intezaar... ${secs}s (pehli baar zyada time lag sakta hai)" }
                    }
                    Thread.sleep(500)
                }

                if (ready) {
                    if (isX11) Thread.sleep(1500) // VNC server ko settle hone do
                    runOnUiThread { openWorkbench(url, mode) }
                } else {
                    runOnUiThread {
                        launchStatus.text = "Server start nahi hua. Neeche logs dekho."
                        toast("VS Code server start nahi hua. Logs check karo.")
                    }
                }
            } catch (e: Exception) {
                appendLog("Launch error: ${e.message}")
                runOnUiThread { launchStatus.text = "Launch error: ${e.message}" }
            } finally {
                launching.set(false)
                runOnUiThread { btnLaunch.isEnabled = true }
            }
        }.start()
    }

    private fun openWorkbench(url: String, mode: String) {
        launchStatus.text = ""
        startActivity(
            Intent(this, WorkbenchActivity::class.java)
                .putExtra(WorkbenchActivity.EXTRA_URL, url)
                .putExtra(WorkbenchActivity.EXTRA_MODE, mode)
        )
    }

    private fun runVerification() {
        val workspace = prefs.getString(KEY_FOLDER, null) ?: prootManager.fallbackWorkspace.absolutePath
        Thread {
            appendLog("Running verification inside embedded Debian ARM64...")
            val p = prootManager.startProotCommand(
                command = "bash /root/verify-vscode.sh",
                workspaceHostPath = workspace,
                onLogLine = { line -> appendLog(line) }
            )
            val code = p.waitFor()
            appendLog(if (code == 0) "VERIFY PASSED" else "VERIFY FAILED (exit $code)")
        }.start()
    }

    private fun stopSession() {
        ProotProcessManager.activeSession?.destroyForcibly()
        ProotProcessManager.activeSession = null
        stopService(Intent(this, LinuxRuntimeService::class.java))
        launchStatus.text = "Session stopped."
    }

    // ------------------------------------------------------------------------------------------
    // Service / permissions
    // ------------------------------------------------------------------------------------------

    private fun startRuntimeService(wakeLock: Boolean, title: String) {
        val svc = Intent(this, LinuxRuntimeService::class.java)
            .putExtra(LinuxRuntimeService.EXTRA_WAKELOCK, wakeLock)
            .putExtra(LinuxRuntimeService.EXTRA_TITLE, title)
        ContextCompat.startForegroundService(this, svc)
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean(KEY_NOTIF_ASKED, false)
        ) {
            prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Mode 1 ko unthrottled rakhne ke liye (sirf ek baar poochta hai). Mode 2 par yeh nahi lagta. */
    private fun maybeAskIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean(KEY_BATT_ASKED, false)) {
            prefs.edit().putBoolean(KEY_BATT_ASKED, true).apply()
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (ignored: Exception) {
            }
        }
    }

    private fun ensureStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (ignored: Exception) {
            }
        }
    }

    private fun resolveUriToPath(treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri) // e.g. "primary:Documents/MyProject"
        val idx = docId.indexOf(':')
        val volume = if (idx >= 0) docId.substring(0, idx) else docId
        val sub = if (idx >= 0) docId.substring(idx + 1) else ""
        if (volume == "raw") return sub
        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        return if (sub.isEmpty()) base else File(base, sub).absolutePath
    }

    // ------------------------------------------------------------------------------------------
    // Log (throttled, last 150 lines)
    // ------------------------------------------------------------------------------------------

    private fun appendLog(msg: String) {
        synchronized(logLines) {
            logLines.addLast(msg)
            while (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
            if (logFlushPending) return
            logFlushPending = true
        }
        uiHandler.postDelayed({ flushLog() }, 250)
    }

    private fun flushLog() {
        val text = synchronized(logLines) {
            logFlushPending = false
            logLines.joinToString("\n")
        }
        logView.text = text
    }

    companion object {
        const val MODE_X11 = "x11"
        const val MODE_DIRECT = "direct"
        const val EXTRA_AUTO_LAUNCH = "auto_launch_mode"

        private const val ID_MODE1 = 1001
        private const val ID_MODE2 = 1002
        private const val KEY_FOLDER = "workspace_path"
        private const val KEY_MODE = "last_mode"
        private const val KEY_NOTIF_ASKED = "notif_asked"
        private const val KEY_BATT_ASKED = "battery_asked"
        private const val MAX_LOG_LINES = 150
    }
}
