package com.vscode.desktop.standalone

import android.content.Context
import android.content.res.AssetManager
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * APK ke andar pre-bundled Debian ARM64 rootfs + official Microsoft VS Code (linux-arm64) ko
 * app ke private storage mein extract karta hai, phir init-rootfs.sh chalata hai.
 *
 * Progress plan (0..100):
 *    0 –  2   tayyari / scripts export
 *    2 – 30   Debian rootfs extract   (compressed bytes ke hisaab se real progress)
 *   30 – 62   VS Code extract
 *   62 – 100  init-rootfs.sh (apt packages; "Unpacking"/"Setting up" lines gine jate hain)
 */
class StandaloneProvisioner(
    private val context: Context,
    private val prootManager: ProotProcessManager
) {

    private enum class StripMode { AUTO_ROOTFS, STRIP_ONE }

    fun provisionAll(
        onProgress: (percent: Int, message: String) -> Unit,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            onProgress(0, "Setup shuru ho raha hai...")
            prootManager.syncAssetScripts()

            val assetNames = context.assets.list("")?.toList() ?: emptyList()

            // ---- 1) Debian rootfs ----
            if (!prootManager.rootfsMarker.exists()) {
                requireFreeSpace()
                val rootfsAsset = assetNames.firstOrNull { it.startsWith("debian-rootfs.tar") }
                    ?: throw IOException("Pre-bundled debian-rootfs asset APK mein nahi mila!")
                onProgress(2, "Debian ARM64 Rootfs extract ho raha hai...")
                extractAsset(
                    rootfsAsset, prootManager.rootfsDir, StripMode.AUTO_ROOTFS,
                    2, 30, "Debian Rootfs", onProgress, onLog
                )
                prootManager.rootfsMarker.writeText("ok")
            }
            onProgress(30, "Debian Rootfs ready.")

            // ---- 2) Official VS Code ----
            if (!prootManager.vscodeMarker.exists()) {
                val vscodeAsset = assetNames.firstOrNull { it.startsWith("vscode-linux-arm64") }
                    ?: throw IOException("Pre-bundled vscode-linux-arm64 asset APK mein nahi mila!")
                val vscodeDir = File(prootManager.rootfsDir, "opt/vscode-arm64").apply { mkdirs() }
                onProgress(30, "Microsoft VS Code extract ho raha hai...")
                extractAsset(
                    vscodeAsset, vscodeDir, StripMode.STRIP_ONE,
                    30, 62, "VS Code", onProgress, onLog
                )
                File(vscodeDir, "bin/code").setExecutable(true, false)
                File(vscodeDir, "code").setExecutable(true, false)
                prootManager.vscodeMarker.writeText("ok")
            }
            onProgress(62, "VS Code ready.")

            // ---- 3) Environment (init-rootfs.sh) ----
            if (File(prootManager.homeDir, ProotProcessManager.INIT_MARKER).exists()) {
                onProgress(100, "Setup complete!")
                return true
            }
            runInitScript(onProgress, onLog)
        } catch (e: Exception) {
            onLog("ERROR during provisioning: ${e.message}")
            onProgress(-1, "Error: ${e.message}")
            false
        }
    }

    private fun requireFreeSpace() {
        val freeGb = context.filesDir.usableSpace / (1024.0 * 1024.0 * 1024.0)
        if (freeGb < 2.5) {
            throw IOException("Storage kam hai (%.1f GB free). Kam se kam 2.5 GB chahiye.".format(freeGb))
        }
    }

    private fun runInitScript(
        onProgress: (Int, String) -> Unit,
        onLog: (String) -> Unit
    ): Boolean {
        onProgress(63, "Environment configure ho raha hai (pehli baar internet chahiye)...")
        var totalPkgs = -1
        var unpacked = 0
        var configured = 0
        val newlyInstalled = Regex("""(\d+) newly installed""")

        val proc = prootManager.startProotCommand(
            command = "bash /root/init-rootfs.sh",
            workspaceHostPath = prootManager.fallbackWorkspace.absolutePath
        ) { line ->
            onLog(line)
            newlyInstalled.find(line)?.let { totalPkgs = it.groupValues[1].toIntOrNull() ?: -1 }
            when {
                line.startsWith("Unpacking ") -> unpacked++
                line.startsWith("Setting up ") -> configured++
                line.contains("STEP 1/3") -> onProgress(64, "apt configure ho raha hai...")
                line.contains("STEP 2/3") -> onProgress(66, "Packages download / install ho rahe hain...")
                line.contains("STEP 3/3") -> onProgress(98, "VS Code config likh raha hoon...")
            }
            if (totalPkgs > 0 && (line.startsWith("Unpacking ") || line.startsWith("Setting up "))) {
                val frac = ((unpacked + configured).toDouble() / (2.0 * totalPkgs)).coerceIn(0.0, 1.0)
                onProgress(66 + (31 * frac).toInt(), "Packages: ${minOf(configured, totalPkgs)}/$totalPkgs configured")
            }
        }
        val exit = proc.waitFor()
        val done = exit == 0 && File(prootManager.homeDir, ProotProcessManager.INIT_MARKER).exists()
        return if (done) {
            onProgress(100, "Setup complete!")
            true
        } else {
            onLog("init-rootfs.sh exit code: $exit")
            onProgress(-1, "Setup fail hua (code $exit). Internet check karke Retry dabao.")
            false
        }
    }

    // ------------------------------------------------------------------------------------------
    // Archive extraction
    // ------------------------------------------------------------------------------------------

    private fun extractAsset(
        assetName: String,
        destDir: File,
        mode: StripMode,
        startPct: Int,
        endPct: Int,
        label: String,
        onProgress: (Int, String) -> Unit,
        onLog: (String) -> Unit
    ) {
        val totalBytes = assetLength(assetName)
        context.assets.open(assetName, AssetManager.ACCESS_STREAMING).use { raw ->
            val counting = CountingInputStream(raw)
            extractTarFromStream(counting, destDir, mode, onLog) { fileCount ->
                val frac = if (totalBytes > 0) {
                    (counting.count.toDouble() / totalBytes).coerceIn(0.0, 1.0)
                } else {
                    fileCount / (fileCount + 20000.0)
                }
                val pct = startPct + ((endPct - startPct) * frac).toInt()
                onProgress(minOf(pct, endPct), "$label extract ho raha hai... ($fileCount files)")
            }
        }
    }

    private fun assetLength(name: String): Long = try {
        context.assets.openFd(name).use { it.length }
    } catch (ignored: Exception) {
        -1L
    }

    private fun extractTarFromStream(
        inputStream: InputStream,
        destDir: File,
        mode: StripMode,
        onLog: (String) -> Unit,
        onCount: (Int) -> Unit
    ) {
        // Magic bytes se format detect: GZ (1F 8B), XZ (FD '7zXZ' 00), warna raw TAR.
        val pushback = PushbackInputStream(BufferedInputStream(inputStream, 256 * 1024), 16)
        val header = ByteArray(6)
        var n = 0
        while (n < header.size) {
            val r = pushback.read(header, n, header.size - n)
            if (r < 0) break
            n += r
        }
        if (n > 0) pushback.unread(header, 0, n)

        val isGz = n >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()
        val isXz = n >= 6 && header[0] == 0xFD.toByte() && header[1] == '7'.code.toByte() &&
                header[2] == 'z'.code.toByte() && header[3] == 'X'.code.toByte() &&
                header[4] == 'Z'.code.toByte() && header[5] == 0x00.toByte()

        val compStream: InputStream = when {
            isXz -> XZCompressorInputStream(pushback)
            isGz -> GzipCompressorInputStream(pushback)
            else -> pushback
        }

        val destRoot = destDir.toPath().toAbsolutePath().normalize()
        var count = 0
        var failures = 0

        TarArchiveInputStream(compStream).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                try {
                    extractEntry(tar, entry, destDir, destRoot, mode)
                } catch (e: Exception) {
                    failures++
                    if (failures <= 5) onLog("WARN: ${entry.name} skip hua: ${e.message}")
                }
                count++
                if (count % 250 == 0) onCount(count)
                entry = tar.nextTarEntry
            }
        }
        onCount(count)
        if (failures > 0) onLog("Extraction mein $failures entries skip hui (zyada tar special files hote hain).")
    }

    private fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        destDir: File,
        destRoot: java.nio.file.Path,
        mode: StripMode
    ) {
        val rel = mapPath(entry.name, mode, entry.isDirectory) ?: return
        val outFile = File(destDir, rel)
        // Path traversal guard
        if (!outFile.toPath().toAbsolutePath().normalize().startsWith(destRoot)) return

        when {
            entry.isDirectory -> {
                outFile.mkdirs()
                outFile.setReadable(true, false)
                outFile.setWritable(true, true)
                outFile.setExecutable(true, false)
            }
            entry.isSymbolicLink -> {
                outFile.parentFile?.mkdirs()
                outFile.delete()
                Files.createSymbolicLink(outFile.toPath(), Paths.get(entry.linkName))
            }
            entry.isLink -> {
                // Hard link: target pehle extract ho chuka hota hai — uski copy bana do.
                val targetRel = mapPath(entry.linkName, mode, false)
                val target = if (targetRel != null) File(destDir, targetRel) else null
                if (target != null && target.isFile) {
                    outFile.parentFile?.mkdirs()
                    outFile.delete()
                    target.copyTo(outFile, overwrite = true)
                    applyMode(outFile, entry.mode)
                }
            }
            entry.isFile -> {
                outFile.parentFile?.mkdirs()
                outFile.delete()
                FileOutputStream(outFile).use { out -> tar.copyTo(out) }
                applyMode(outFile, entry.mode)
            }
            else -> {
                // char/block devices, FIFOs: Android par nahi bana sakte (proot /dev bind karta hai).
            }
        }
    }

    private fun applyMode(f: File, mode: Int) {
        f.setReadable(true, false)
        f.setWritable(true, true)
        if ((mode and 0b001_001_001) != 0) f.setExecutable(true, false)
    }

    /** Tar entry ke naam ko rootfs ke andar ke relative path mein badalta hai (null = skip). */
    private fun mapPath(name: String, mode: StripMode, isDir: Boolean): String? {
        val parts = name.split("/").filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null

        val rel: List<String> = when (mode) {
            StripMode.STRIP_ONE -> if (parts.size > 1) parts.drop(1) else return null
            StripMode.AUTO_ROOTFS -> when {
                parts[0] in STANDARD_DIRS -> parts
                parts.size > 1 -> parts.drop(1)          // e.g. "debian-bookworm-aarch64/usr/..."
                isDir -> return null                      // wrapper top-level directory khud
                else -> parts
            }
        }
        if (rel.any { it == ".." }) return null
        return rel.joinToString("/")
    }

    /** Wrapped stream: compressed bytes gin-ta hai taaki real 0–100% progress mile. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile
        var count: Long = 0L

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }

        override fun skip(n: Long): Long {
            val r = super.skip(n)
            if (r > 0) count += r
            return r
        }
    }

    companion object {
        private val STANDARD_DIRS = setOf(
            "bin", "sbin", "etc", "usr", "lib", "lib32", "lib64", "libx32", "var", "dev", "proc",
            "sys", "root", "home", "tmp", "opt", "run", "mnt", "media", "srv", "boot"
        )
    }
}
