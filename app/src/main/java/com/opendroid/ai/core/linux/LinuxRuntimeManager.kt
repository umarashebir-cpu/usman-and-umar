package com.opendroid.ai.core.linux

import android.content.Context
import android.util.Log
import com.opendroid.ai.core.pty.PtySession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app-private Alpine userspace and its PRoot launcher.
 *
 * The PRoot executable is deliberately not silently substituted with /system/bin/sh:
 * until the verified ARM32 binary is installed, [startShell] fails with an actionable
 * status. This prevents the UI from claiming that a host shell is an Alpine shell.
 */
@Singleton
class LinuxRuntimeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val rootDir: File get() = File(context.filesDir, RUNTIME_DIR)
    val rootfsDir: File get() = File(rootDir, ROOTFS_DIR)
    val prootFile: File get() = File(rootDir, PROOT_NAME)
    private val archiveFile: File get() = File(rootDir, ROOTFS_ARCHIVE)
    private val stateFile: File get() = File(rootDir, STATE_FILE)
    private val prootVerificationFile: File get() = File(rootDir, PROOT_VERIFIED_STATE)

    suspend fun install(progress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        val temporaryArchive = File(rootDir, "$ROOTFS_ARCHIVE.part")
        try {
            downloadVerified(ROOTFS_URL, ROOTFS_SHA256, temporaryArchive, progress)
            safeExtract(temporaryArchive, rootfsDir)
            temporaryArchive.delete()
            configureRepositories()
            extractAndVerifyProot()
            stateFile.writeText("$ROOTFS_VERSION\n$ROOTFS_SHA256\n", Charsets.UTF_8)
        } catch (failure: Throwable) {
            Log.e(TAG, "Alpine installation failed", failure)
            throw failure
        }
    }

    fun status(): RuntimeStatus = when {
        !rootfsDir.resolve("bin/sh").isFile -> RuntimeStatus.NOT_INSTALLED
        !prootFile.isFile || !prootFile.canExecute() ||
            prootVerificationFile.readTextOrNull() != PROOT_COMMIT -> RuntimeStatus.PROOT_MISSING
        else -> RuntimeStatus.READY
    }

    fun startShell(): PtySession {
        extractAndVerifyProot()
        check(status() == RuntimeStatus.READY) {
            "Integrated Linux is not ready. Install the verified ARM32 PRoot runtime and Alpine rootfs first."
        }
        val home = rootfsDir.resolve("home/usman").apply { mkdirs() }
        return PtySession.start(
            executable = prootFile.absolutePath,
            arguments = arrayOf(
                "--link2symlink",
                "-0",
                "-r", rootfsDir.absolutePath,
                "-b", "/dev:/dev",
                "-b", "/proc:/proc",
                "-b", "${context.filesDir.absolutePath}:/android-app",
                "/bin/sh", "-l"
            ),
            workingDirectory = home.absolutePath,
            environment = mapOf(
                "HOME" to "/home/usman",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM" to "xterm-256color"
            )
        )
    }

    /** Copies the packaged executable into app-private storage and verifies that it runs. */
    private fun extractAndVerifyProot(): File {
        rootDir.mkdirs()
        if (!prootFile.isFile || prootFile.length() == 0L) {
            val temporary = File(rootDir, "$PROOT_NAME.part")
            context.assets.open(PROOT_ASSET).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(prootFile)) { "Could not install packaged PRoot executable" }
        }
        check(prootFile.setExecutable(true, false) || prootFile.canExecute()) {
            "Failed to set executable permission on PRoot"
        }
        val process = ProcessBuilder(prootFile.absolutePath, PROOT_REQUIRED_OPTION, "--version")
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(PROOT_EXECUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(finished && process.exitValue() == 0) {
            prootVerificationFile.delete()
            "Packaged PRoot did not execute successfully: $output"
        }
        prootVerificationFile.writeText(PROOT_COMMIT, Charsets.UTF_8)
        return prootFile
    }

    private fun configureRepositories() {
        val etc = rootfsDir.resolve("etc").apply { mkdirs() }
        etc.resolve("repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/$ROOTFS_VERSION/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/$ROOTFS_VERSION/community\n",
            Charsets.UTF_8
        )
    }

    private fun downloadVerified(
        url: String,
        expectedSha256: String,
        destination: File,
        progress: (Long, Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            check(connection.responseCode in 200..299) { "Rootfs download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        count += read
                        progress(count, total)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(expectedSha256, ignoreCase = true)) {
                "Rootfs checksum mismatch: expected $expectedSha256, got $actual"
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeExtract(archive: File, destination: File) {
        // Extract into a sibling staging directory. A failed download or archive
        // validation must not destroy a previously working rootfs or user data.
        SafeArchiveExtractor.extract(
            archive = archive,
            destination = destination,
            allowTrustedGuestAbsoluteLinks = true
        )
    }

    companion object {
        const val ROOTFS_VERSION = "latest-stable"
        const val ROOTFS_URL = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/armv7/alpine-minirootfs-3.24.2-armv7.tar.gz"
        const val ROOTFS_SHA256 = "5552f1ef2398cee46ef2cf733f8f1258f39a77ad065dfd47282fc0df9fe1976c"
        const val PROOT_SOURCE = "https://github.com/termux/proot"
        const val PROOT_COMMIT = "b8c1f5c5c6730dc9d521d5bd5ce1cfeedecde087"
        const val PROOT_REQUIRED_OPTION = "--link2symlink"
        private const val PROOT_ASSET = "proot/proot"
        private const val PROOT_EXECUTION_TIMEOUT_SECONDS = 5L
        private const val RUNTIME_DIR = "linux-runtime"
        private const val ROOTFS_DIR = "alpine-armv7"
        private const val ROOTFS_ARCHIVE = "alpine-minirootfs-3.24.2-armv7.tar.gz"
        private const val PROOT_NAME = "proot-armv7"
        private const val STATE_FILE = "install.state"
        private const val PROOT_VERIFIED_STATE = "proot.verified"
        private const val TAG = "LinuxRuntimeManager"
    }
}

enum class RuntimeStatus { NOT_INSTALLED, PROOT_MISSING, READY }

private fun File.readTextOrNull(): String? =
    if (isFile) runCatching { readText(Charsets.UTF_8) }.getOrNull() else null
