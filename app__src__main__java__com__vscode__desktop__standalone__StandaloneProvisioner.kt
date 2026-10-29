package com.vscode.desktop.standalone

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Downloads and unpacks the ARM64 Debian glibc rootfs and the genuine Microsoft VS Code
 * linux-arm64 application directly inside the APK's private filesDir.
 */
class StandaloneProvisioner(
    private val context: Context,
    private val prootManager: ProotProcessManager
) {
    companion object {
        // Official Debian Bookworm ARM64 rootfs container tarball
        const val DEBIAN_ROOTFS_URL =
            "https://github.com/termux/proot-distro/releases/download/v4.15.0/debian-bookworm-aarch64-pd-v4.15.0.tar.xz"

        // Official Microsoft VS Code Desktop (linux-arm64) stable CDN
        const val VSCODE_ARM64_URL =
            "https://update.code.visualstudio.com/latest/linux-arm64/stable"
    }

    fun provisionAll(onProgress: (String) -> Unit): Boolean {
        return try {
            exportAssetScripts()

            val rootfsTar = File(prootManager.tmpDir, "debian-arm64.tar.xz")
            if (!File(prootManager.rootfsDir, "bin/bash").exists()) {
                onProgress("1/4 Downloading ARM64 Debian Linux Userspace Rootfs...")
                downloadFile(DEBIAN_ROOTFS_URL, rootfsTar, onProgress)

                onProgress("2/4 Extracting ARM64 Debian Rootfs into APK Sandbox...")
                extractTarArchive(rootfsTar, prootManager.rootfsDir, isXz = true, stripComponents = 1)
                rootfsTar.delete()
            }

            val vscodeDir = File(prootManager.rootfsDir, "opt/vscode-arm64")
            if (!File(vscodeDir, "bin/code").exists()) {
                vscodeDir.mkdirs()
                val vscodeTar = File(prootManager.tmpDir, "vscode-linux-arm64.tar.gz")
                onProgress("3/4 Downloading Official Microsoft VS Code Desktop (linux-arm64)...")
                downloadFile(VSCODE_ARM64_URL, vscodeTar, onProgress)

                onProgress("4/4 Extracting Genuine VS Code Desktop Application...")
                extractTarArchive(vscodeTar, vscodeDir, isXz = false, stripComponents = 1)
                vscodeTar.delete()
            }

            onProgress("Configuring X11 Display Server, Openbox, Git, Python & Compilers inside Rootfs...")
            val initProcess = prootManager.startProotCommand(
                command = "bash /root/init-rootfs.sh",
                workspaceHostPath = prootManager.homeDir.absolutePath,
                onLogLine = onProgress
            )
            val exitCode = initProcess.waitFor()
            onProgress("Provisioning finished with exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            onProgress("ERROR during provisioning: ${e.message}")
            false
        }
    }

    private fun exportAssetScripts() {
        val targetDir = File(prootManager.rootfsDir, "root").apply { mkdirs() }
        listOf(
            "init-rootfs.sh",
            "start-vscode-session.sh",
            "verify-vscode.sh"
        ).forEach { name ->
            val outFile = File(targetDir, name)
            context.assets.open(name).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.setExecutable(true, false)
        }
    }

    private fun downloadFile(urlStr: String, destination: File, onProgress: (String) -> Unit) {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            val status = conn.responseCode
            if (status in 301..308) {
                currentUrl = conn.getHeaderField("Location")
                redirects++
                continue
            }
            val totalBytes = conn.contentLengthLong
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read: Int
                    var lastReportMb = -1L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val mb = downloaded / (1024 * 1024)
                        if (mb != lastReportMb && mb % 5L == 0L) {
                            lastReportMb = mb
                            val totalMb = if (totalBytes > 0) "${totalBytes / (1024 * 1024)} MB" else "unknown"
                            onProgress("Downloaded $mb MB / $totalMb...")
                        }
                    }
                }
            }
            return
        }
    }

    private fun extractTarArchive(
        archiveFile: File,
        destDir: File,
        isXz: Boolean,
        stripComponents: Int
    ) {
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                val compressorStream = if (isXz) {
                    XZCompressorInputStream(bis)
                } else {
                    GzipCompressorInputStream(bis)
                }
                TarArchiveInputStream(compressorStream).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val parts = entry.name.split("/").filter { it.isNotEmpty() }
                        if (parts.size > stripComponents) {
                            val relPath = parts.drop(stripComponents).joinToString("/")
                            val outFile = File(destDir, relPath)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                outFile.parentFile?.mkdirs()
                                outFile.delete()
                                try {
                                    Files.createSymbolicLink(
                                        outFile.toPath(),
                                        Paths.get(entry.linkName)
                                    )
                                } catch (_: Exception) {
                                }
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                                if ((entry.mode and 0b001_000_000) != 0) {
                                    outFile.setExecutable(true, false)
                                }
                            }
                        }
                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
    }
}
