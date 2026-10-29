package com.vscode.desktop.standalone

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Self-contained Linux Userspace Execution Engine.
 * Executes commands inside an embedded Debian ARM64 rootfs using bundled libproot.so
 * from nativeLibraryDir — requiring ZERO Termux installation.
 */
class ProotProcessManager(private val context: Context) {

    val nativeLibDir: File = File(context.applicationInfo.nativeLibraryDir)
    val filesDir: File = context.filesDir
    val rootfsDir: File = File(filesDir, "rootfs")
    val tmpDir: File = File(filesDir, "tmp")
    val homeDir: File = File(rootfsDir, "root")

    val prootBinary: File = File(nativeLibDir, "libproot.so")
    val prootLoader: File = File(nativeLibDir, "libproot-loader.so")

    init {
        if (!rootfsDir.exists()) rootfsDir.mkdirs()
        if (!tmpDir.exists()) tmpDir.mkdirs()
        if (!homeDir.exists()) homeDir.mkdirs()
    }

    fun isRootfsProvisioned(): Boolean {
        val codeBin = File(rootfsDir, "opt/vscode-arm64/bin/code")
        val bashBin = File(rootfsDir, "bin/bash")
        return codeBin.exists() && bashBin.exists()
    }

    /**
     * Spawns a command inside the self-contained ARM64 Linux environment via libproot.so.
     */
    fun startProotCommand(
        command: String,
        workspaceHostPath: String,
        displayMode: String = "x11",
        onLogLine: (String) -> Unit
    ): Process {
        val workspaceFile = File(workspaceHostPath)
        if (!workspaceFile.exists()) workspaceFile.mkdirs()

        // Ensure basic /etc/resolv.conf and /etc/hosts exist inside rootfs
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
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let(onLogLine)
                }
            }
        }.start()

        return process
    }
}
