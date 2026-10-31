package com.vscode.desktop.standalone

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Self-contained Linux userspace engine.
 * Embedded Debian ARM64 rootfs ke andar commands chalata hai, bundled libproot.so (nativeLibraryDir)
 * ke through — Termux ki zaroorat nahi.
 */
class ProotProcessManager(private val context: Context) {

    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)
    val filesDir: File = context.filesDir
    val rootfsDir: File = File(filesDir, "rootfs")
    val tmpDir: File = File(filesDir, "tmp")
    val homeDir: File = File(rootfsDir, "root")

    /** Jab koi user project folder available na ho (setup / verify) tab yeh bind hota hai. */
    val fallbackWorkspace: File = File(filesDir, "workspace")

    val prootBinary: File = File(nativeLibDir, "libproot.so")
    val prootLoader: File = File(nativeLibDir, "libproot-loader.so")

    init {
        rootfsDir.mkdirs()
        tmpDir.mkdirs()
        homeDir.mkdirs()
        fallbackWorkspace.mkdirs()
    }

    /** Extraction complete hone ke markers (beech mein ruka hua extract "done" nahi maana jata). */
    val rootfsMarker: File = File(filesDir, ".rootfs-extracted")
    val vscodeMarker: File = File(filesDir, ".vscode-extracted")

    /** Rootfs + VS Code extract ho chuke hain? (init-rootfs.sh abhi chala ya nahi, yeh alag hai) */
    fun isExtracted(): Boolean = rootfsMarker.exists() && vscodeMarker.exists()

    /** Poora setup (extract + init-rootfs.sh) complete hai? */
    fun isRootfsProvisioned(): Boolean = isExtracted() && File(homeDir, INIT_MARKER).exists()

    /** Assets ke 3 shell scripts ko rootfs ke /root mein copy karta hai (app update ke baad bhi fresh rehte hain). */
    fun syncAssetScripts() {
        homeDir.mkdirs()
        SCRIPTS.forEach { name ->
            try {
                val out = File(homeDir, name)
                context.assets.open(name).use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                out.setExecutable(true, false)
                out.setReadable(true, false)
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Embedded Linux ke andar command spawn karta hai.
     * [onLogLine] background thread par call hota hai.
     */
    fun startProotCommand(
        command: String,
        workspaceHostPath: String,
        displayMode: String = "x11",
        onLogLine: (String) -> Unit
    ): Process {
        syncAssetScripts()

        var workspaceFile = File(workspaceHostPath)
        if (!workspaceFile.exists() && !workspaceFile.mkdirs()) {
            onLogLine("WARNING: Project folder available nahi hai ($workspaceHostPath). App-private workspace use ho raha hai.")
            workspaceFile = fallbackWorkspace
        }

        // /etc/resolv.conf aur /etc/hosts
        val etcDir = File(rootfsDir, "etc").apply { mkdirs() }
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(etcDir, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")

        val cmdList = mutableListOf(
            prootBinary.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${tmpDir.absolutePath}:/tmp",
            "-b", "${tmpDir.absolutePath}:/dev/shm",
            "-b", "${workspaceFile.absolutePath}:/root/Workspace",
            "-w", "/root/Workspace",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/opt/vscode-arm64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "XDG_RUNTIME_DIR=/tmp",
            "ELECTRON_DISABLE_SANDBOX=1",
            "DISPLAY=:1",
            "VSCODE_MODE=$displayMode",
            "/bin/bash", "-c", command
        )

        val pb = ProcessBuilder(cmdList)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = prootLoader.absolutePath
        env["LD_LIBRARY_PATH"] = nativeLibDir.absolutePath
        pb.redirectErrorStream(true)

        val process = pb.start()
        Thread {
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        onLogLine(line)
                        line = reader.readLine()
                    }
                }
            } catch (ignored: Exception) {
            }
        }.start()

        return process
    }

    /** Mode 2 heartbeat: app visible hai => server ko idle mat samjho. */
    fun touchMode2Heartbeat() {
        try {
            val f = File(tmpDir, MODE2_HEARTBEAT)
            if (!f.exists()) f.createNewFile()
            f.setLastModified(System.currentTimeMillis())
        } catch (ignored: Exception) {
        }
    }

    companion object {
        const val INIT_MARKER = ".pocket-init-done"
        const val MODE2_HEARTBEAT = "mode2.heartbeat"
        const val MODE2_PORT = 8080
        const val MODE1_PORT = 6080

        val SCRIPTS = listOf("init-rootfs.sh", "start-vscode-session.sh", "verify-vscode.sh")

        /** Abhi chal raha Mode 1 / Mode 2 session (Activities ke beech share hota hai). */
        @Volatile
        var activeSession: Process? = null

        fun isPortOpen(port: Int, timeoutMs: Int = 300): Boolean {
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
                    true
                }
            } catch (ignored: Exception) {
                false
            }
        }
    }
}
