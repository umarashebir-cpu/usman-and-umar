package com.opendroid.ai.core.linux

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Extracts an Alpine minirootfs while keeping guest archive paths separate from
 * Android host filesystem paths.
 */
internal object SafeArchiveExtractor {
    /**
     * [allowTrustedGuestAbsoluteLinks] is enabled only after LinuxRuntimeManager
     * has verified the pinned Alpine archive checksum. Such links are converted
     * to root-relative links; they are never created as host-absolute links.
     */
    fun extract(
        archive: File,
        destination: File,
        allowTrustedGuestAbsoluteLinks: Boolean = false
    ) {
        require(archive.isFile && archive.length() > 0) {
            "Downloaded rootfs archive is missing or empty"
        }
        val parent = destination.parentFile ?: throw IOException("Rootfs destination has no parent")
        val staging = Files.createTempDirectory(
            parent.toPath(),
            "${destination.name}.staging-"
        ).toFile()
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
                TarReader(input).forEachEntry { entry ->
                    extractEntry(entry, staging.toPath(), allowTrustedGuestAbsoluteLinks)
                }
            }
            check(staging.resolve("bin/sh").isFile) {
                "Extracted Alpine rootfs is missing bin/sh"
            }
            activate(staging, destination)
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun activate(staging: File, destination: File) {
        val backup = File(
            destination.parentFile,
            "${destination.name}.backup-${System.nanoTime()}"
        )
        var backedUp = false
        try {
            if (destination.exists()) {
                if (Files.isSymbolicLink(destination.toPath())) {
                    throw IOException("Rootfs destination is a symbolic link")
                }
                check(destination.renameTo(backup)) {
                    "Could not stage the existing Alpine rootfs for replacement"
                }
                backedUp = true
            }
            check(staging.renameTo(destination)) {
                "Could not activate extracted Alpine rootfs"
            }
            if (backedUp) backup.deleteRecursively()
        } catch (failure: Throwable) {
            if (destination.exists() && !Files.isSymbolicLink(destination.toPath())) {
                destination.deleteRecursively()
            }
            if (backedUp && !destination.exists()) {
                check(backup.renameTo(destination)) {
                    "Could not restore the previous Alpine rootfs after activation failure"
                }
            }
            throw failure
        } finally {
            if (backup.exists() && destination.exists()) backup.deleteRecursively()
        }
    }

    private fun extractEntry(
        entry: TarEntry,
        hostRoot: Path,
        allowTrustedGuestAbsoluteLinks: Boolean
    ) {
        var stage = "guest-path-validation"
        var guestPath: Path? = null
        var hostDestination: Path? = null
        try {
            guestPath = SafeArchivePath.normalizeEntry(entry.name)
            hostDestination = hostRoot.resolve(guestPath).normalize()
            SafeArchivePath.requireContained(hostRoot, hostDestination, "archive entry")
            if (entry.type == '5' && SafeArchivePath.isRootEntry(guestPath)) {
                // `.` and `./` normalize to an empty relative Path on Android/JVM.
                // The intended destination is the staging root itself; it has no
                // parent inside the root that should be checked.
                Files.createDirectories(hostRoot)
                return
            }

            stage = "host-parent-validation"
            requireNoSymlinkParents(hostRoot, hostDestination.parent)
            if (Files.exists(hostDestination, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Conflicting archive entry: ${entry.name}")
            }

            when (entry.type) {
                '5' -> Files.createDirectories(hostDestination)
                '2' -> {
                    stage = "guest-symlink-validation"
                    val linkTarget = SafeArchivePath.resolveLinkTarget(
                        guestPath,
                        entry.linkName,
                        allowTrustedGuestAbsoluteLinks
                    )
                    val hostTarget = hostRoot.resolve(linkTarget).normalize()
                    SafeArchivePath.requireContained(hostRoot, hostTarget, "symbolic-link target")
                    val safeLinkText = SafeArchivePath.materializeLinkTarget(
                        guestPath,
                        entry.linkName,
                        linkTarget
                    )
                    Files.createSymbolicLink(hostDestination, safeLinkText)
                }
                '0', '\u0000' -> {
                    stage = "host-file-write"
                    hostDestination.parent?.let { Files.createDirectories(it) }
                    FileOutputStream(hostDestination.toFile()).use { out -> entry.copyDataTo(out) }
                    hostDestination.toFile().setExecutable(entry.mode and 0x49 != 0, false)
                }
                else -> throw IOException("Unsupported Alpine archive entry type: ${entry.type}")
            }
        } catch (failure: ArchiveExtractionException) {
            throw failure
        } catch (failure: Throwable) {
            throw ArchiveExtractionException(
                entryName = entry.name,
                hostDestination = hostDestination?.toString(),
                hostRoot = hostRoot.toAbsolutePath().normalize().toString(),
                stage = stage,
                cause = failure
            )
        }
    }

    private fun requireNoSymlinkParents(root: Path, parent: Path?) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        var current = parent?.toAbsolutePath()?.normalize() ?: return
        val missing = java.util.ArrayDeque<Path>()
        while (current != normalizedRoot) {
            SafeArchivePath.requireContained(normalizedRoot, current, "archive parent")
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw IOException("Archive write would follow symbolic link: $current")
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Archive parent is not a directory: $current")
                }
                break
            }
            missing.addFirst(current)
            current = current.parent ?: throw IOException("Archive parent escaped rootfs")
        }
        missing.forEach { Files.createDirectory(it) }
    }

    private data class TarEntry(
        val name: String,
        val type: Char,
        val linkName: String,
        val size: Long,
        val mode: Int,
        private val input: TarReader
    ) {
        fun copyDataTo(output: FileOutputStream) = input.copy(size, output)
    }

    private class TarReader(private val input: InputStream) {
        fun forEachEntry(consumer: (TarEntry) -> Unit) {
            while (true) {
                val header = ByteArray(BLOCK_SIZE)
                val count = readFully(header)
                if (count == 0 || header.all { it == 0.toByte() }) return
                check(count == BLOCK_SIZE) { "Truncated Alpine tar header" }
                val name = readString(header, 0, 100)
                val prefix = readString(header, 345, 155)
                val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
                val size = readOctal(header, 124, 12)
                val mode = readOctal(header, 100, 8).toInt()
                val type = header[156].toInt().toChar().let { if (it == '\u0000') '\u0000' else it }
                val linkName = readString(header, 157, 100)
                val entry = TarEntry(fullName, type, linkName, size, mode, this)
                consumer(entry)
                if (type != '0' && type != '\u0000') skip(size)
                skip((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE)
            }
        }

        fun copy(size: Long, output: FileOutputStream) {
            var remaining = size
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (remaining > 0) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, wanted)
                check(read > 0) { "Truncated Alpine tar entry" }
                output.write(buffer, 0, read)
                remaining -= read
            }
        }

        private fun skip(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped
                else check(input.read() >= 0) { "Truncated Alpine tar archive" }.also { remaining-- }
            }
        }

        private fun readFully(buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            return offset
        }

        private fun readString(bytes: ByteArray, offset: Int, length: Int): String =
            bytes.copyOfRange(offset, offset + length)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.US_ASCII)
                .trim()

        private fun readOctal(bytes: ByteArray, offset: Int, length: Int): Long =
            readString(bytes, offset, length).trimStart('0').ifEmpty { "0" }.toLong(8)

        companion object { private const val BLOCK_SIZE = 512 }
    }
}

internal class ArchiveExtractionException(
    val entryName: String,
    val hostDestination: String?,
    val hostRoot: String,
    val stage: String,
    cause: Throwable
) : IOException(
    buildString {
        append("Archive entry rejected: ").append(stage)
        append("; entry: ").append(entryName)
        if (hostDestination != null) append("; host: ").append(hostDestination)
        append("; root: ").append(hostRoot)
        append("; cause: ").append(cause.message ?: cause::class.java.simpleName)
    },
    cause
)

internal object SafeArchivePath {
    fun isSafe(name: String): Boolean = runCatching { normalizeEntry(name); true }.getOrDefault(false)

    fun normalizeEntry(name: String): Path {
        require(name.isNotEmpty() && !name.contains('\u0000')) { "Archive path is empty or contains NUL" }
        val normalized = name.replace('\\', '/')
        require(!normalized.startsWith('/')) { "Absolute archive path: $name" }
        val path = normalized.toPathString().normalize()
        require(!path.isAbsolute && path.nameCount > 0 && path.first().toString() != "..") {
            "Archive path escapes rootfs: $name"
        }
        return path
    }

    fun isRootEntry(path: Path): Boolean =
        path.nameCount == 1 && (path.toString().isEmpty() || path.toString() == ".")

    fun resolveLinkTarget(
        link: Path,
        target: String,
        allowTrustedGuestAbsoluteLinks: Boolean = false
    ): Path {
        require(target.isNotEmpty() && !target.contains('\u0000')) { "Invalid symbolic-link target" }
        val normalizedTarget = target.replace('\\', '/')
        val targetPath = normalizedTarget.toPathString()
        val guestAbsolute = targetPath.isAbsolute
        require(!guestAbsolute || allowTrustedGuestAbsoluteLinks) {
            "Absolute symbolic-link target: $target"
        }
        val guestTarget = if (guestAbsolute) {
            Paths.get(normalizedTarget.removePrefix("/"))
        } else {
            targetPath
        }
        val parent = link.parent ?: Paths.get("")
        val resolved = if (guestAbsolute) guestTarget else parent.resolve(guestTarget).normalize()
        require(!resolved.isAbsolute && resolved.nameCount > 0 && resolved.first().toString() != "..") {
            "Symbolic-link target escapes rootfs: $target"
        }
        return resolved
    }

    fun materializeLinkTarget(link: Path, original: String, resolved: Path): Path =
        if (original.replace('\\', '/').startsWith('/')) {
            (link.parent ?: Paths.get("")).relativize(resolved)
        } else {
            original.toPathString()
        }

    fun requireContained(root: Path, candidate: Path, what: String) {
        val rootPath = root.toAbsolutePath().normalize()
        val candidatePath = candidate.toAbsolutePath().normalize()
        require(candidatePath == rootPath || candidatePath.startsWith(rootPath)) {
            "$what escapes rootfs: $candidate"
        }
    }
}

private fun String.toPathString(): Path = Paths.get(this)
