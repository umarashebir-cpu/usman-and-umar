package com.opendroid.ai.core.linux

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxRuntimeManagerTest {
    @Test
    fun `archive paths cannot escape app-private rootfs`() {
        assertTrue(SafeArchivePath.isSafe("./bin/sh"))
        assertTrue(SafeArchivePath.isSafe("usr/lib/ld-musl-armhf.so.1"))
        assertFalse(SafeArchivePath.isSafe("../outside"))
        assertFalse(SafeArchivePath.isSafe("etc/../../outside"))
        assertFalse(SafeArchivePath.isSafe("/absolute/path"))
        assertFalse(SafeArchivePath.isSafe("etc/secret\u0000file"))
    }

    @Test
    fun `official Alpine cron symlink resolves inside rootfs`() {
        val resolved = SafeArchivePath.resolveLinkTarget(
            Path.of("var/spool/cron/crontabs"),
            "../../../etc/crontabs"
        )
        assertEquals(Path.of("etc/crontabs"), resolved)
    }

    @Test
    fun `trusted Alpine guest absolute link is remapped to a guest relative link`() {
        val link = Path.of("etc/ssl1.1/certs")
        val resolved = SafeArchivePath.resolveLinkTarget(link, "/etc/ssl/certs", true)
        assertEquals(Path.of("etc/ssl/certs"), resolved)
        assertEquals(
            Path.of("../ssl/certs"),
            SafeArchivePath.materializeLinkTarget(link, "/etc/ssl/certs", resolved)
        )
    }

    @Test
    fun `root directory entry resolves to staging root without parent escape`() {
        val parent = Files.createTempDirectory("alpine-root-entry-test").toFile()
        val archive = File(parent, "root-entry.tar.gz")
        val destination = File(parent, "rootfs")
        try {
            writeTar(archive) {
                directory("./")
                directory("bin/")
                file("bin/sh", "#!/bin/sh\n")
                file("etc-release", "Alpine\n")
            }
            val normalizedRoot = destination.toPath().toAbsolutePath().normalize()
            assertTrue(SafeArchivePath.isRootEntry(SafeArchivePath.normalizeEntry(".")))
            assertTrue(SafeArchivePath.isRootEntry(SafeArchivePath.normalizeEntry("./")))
            SafeArchivePath.requireContained(normalizedRoot, normalizedRoot, "archive entry")
            SafeArchiveExtractor.extract(archive, destination)
            assertTrue(destination.resolve("bin/sh").isFile)
            assertEquals("Alpine\n", destination.resolve("etc-release").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deep relative symlink escape is rejected`() {
        SafeArchivePath.resolveLinkTarget(Path.of("evil/link"), "../../../../../../etc/passwd")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `absolute symlink target is rejected`() {
        SafeArchivePath.resolveLinkTarget(Path.of("etc/link"), "/etc/passwd")
    }

    @Test
    fun `nested symlink traversal cannot write outside staging root`() {
        val parent = Files.createTempDirectory("alpine-extractor-test").toFile()
        val archive = File(parent, "escape.tar.gz")
        val destination = File(parent, "rootfs")
        try {
            writeTar(archive) {
                directory("etc/")
                symlink("etc/redirect", "../../outside")
                file("etc/redirect/pwned", "must not be written")
            }
            val failure = runCatching { SafeArchiveExtractor.extract(archive, destination) }.exceptionOrNull()
            assertTrue(failure is ArchiveExtractionException)
            val diagnostic = failure as ArchiveExtractionException
            assertEquals("etc/redirect", diagnostic.entryName)
            assertTrue(diagnostic.hostDestination!!.contains("rootfs.staging-"))
            assertTrue(diagnostic.hostRoot.contains("rootfs.staging-"))
            assertEquals("guest-symlink-validation", diagnostic.stage)
            assertFalse(File(parent, "outside/pwned").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `valid symlink and conflicting archive entry are handled safely`() {
        val parent = Files.createTempDirectory("alpine-extractor-test").toFile()
        val archive = File(parent, "valid.tar.gz")
        val destination = File(parent, "rootfs")
        try {
            writeTar(archive) {
                directory("bin/")
                file("bin/sh", "#!/bin/sh\n")
                directory("etc/")
                directory("var/")
                directory("var/spool/")
                directory("var/spool/cron/")
                symlink("var/spool/cron/crontabs", "../../../etc/crontabs")
                directory("etc/crontabs/")
                file("etc/crontabs/root", "# root\n")
            }
            SafeArchiveExtractor.extract(archive, destination)
            assertTrue(Files.isSymbolicLink(destination.toPath().resolve("var/spool/cron/crontabs")))
            assertEquals("# root\n", destination.resolve("etc/crontabs/root").readText())
            destination.resolve("user-project.txt").writeText("preserve me")

            val conflicting = File(parent, "conflict.tar.gz")
            writeTar(conflicting) {
                directory("etc/")
                symlink("etc/conflict", "../outside")
                file("etc/conflict/pwned", "must not be written")
            }
            runCatching { SafeArchiveExtractor.extract(conflicting, destination) }.getOrThrowFailure()
            assertFalse(File(parent, "outside/pwned").exists())
            assertEquals("preserve me", destination.resolve("user-project.txt").readText())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `host staging path with dot segments remains inside its own root`() {
        val parent = Files.createTempDirectory("alpine-extractor-test").toFile()
        val archive = File(parent, "normalized.tar.gz")
        val nested = File(parent, "nested").apply { mkdirs() }
        val destination = File(nested, "../rootfs")
        try {
            writeTar(archive) {
                directory("bin/")
                file("bin/sh", "#!/bin/sh\n")
            }
            SafeArchiveExtractor.extract(archive, destination)
            assertTrue(destination.resolve("bin/sh").isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `trusted guest absolute link is extracted without leaving host staging`() {
        val parent = Files.createTempDirectory("alpine-extractor-test").toFile()
        val archive = File(parent, "official-link.tar.gz")
        val destination = File(parent, "rootfs")
        try {
            writeTar(archive) {
                directory("bin/")
                file("bin/sh", "#!/bin/sh\n")
                directory("etc/")
                directory("etc/ssl1.1/")
                symlink("etc/ssl1.1/certs", "/etc/ssl/certs")
            }
            SafeArchiveExtractor.extract(archive, destination, allowTrustedGuestAbsoluteLinks = true)
            assertEquals(
                Path.of("../ssl/certs"),
                Files.readSymbolicLink(destination.toPath().resolve("etc/ssl1.1/certs"))
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `selected Alpine archive extracts with guest link semantics`() {
        val archivePath = System.getProperty("alpine.archive") ?: return
        val parent = Files.createTempDirectory("alpine-real-archive-test").toFile()
        try {
            val destination = File(parent, "rootfs")
            SafeArchiveExtractor.extract(File(archivePath), destination, allowTrustedGuestAbsoluteLinks = true)
            assertTrue(destination.resolve("bin/sh").isFile)
            assertEquals(
                Path.of("../../../etc/crontabs"),
                Files.readSymbolicLink(destination.toPath().resolve("var/spool/cron/crontabs"))
            )
            assertEquals(
                Path.of("../ssl/certs"),
                Files.readSymbolicLink(destination.toPath().resolve("etc/ssl1.1/certs"))
            )
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `runtime pins ARMv7 Alpine and link2symlink PRoot`() {
        assertTrue(LinuxRuntimeManager.ROOTFS_URL.contains("armv7"))
        assertTrue(LinuxRuntimeManager.ROOTFS_SHA256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(LinuxRuntimeManager.PROOT_COMMIT.matches(Regex("[0-9a-f]{40}")))
        assertTrue(LinuxRuntimeManager.PROOT_REQUIRED_OPTION == "--link2symlink")
    }

    private fun writeTar(file: File, block: TarWriter.() -> Unit) {
        GZIPOutputStream(FileOutputStream(file)).use { gzip ->
            TarWriter(gzip).apply(block).finish()
        }
    }

    private class TarWriter(private val output: java.io.OutputStream) {
        fun directory(name: String) = entry(name, '5', "", ByteArray(0))
        fun symlink(name: String, target: String) = entry(name, '2', target, ByteArray(0))
        fun file(name: String, content: String) = entry(name, '0', "", content.toByteArray())

        private fun entry(name: String, type: Char, link: String, data: ByteArray) {
            val header = ByteArray(512)
            put(header, 0, 100, name)
            putOctal(header, 100, 8, 0x1ff)
            putOctal(header, 108, 8, 0)
            putOctal(header, 116, 8, 0)
            putOctal(header, 124, 12, data.size.toLong())
            putOctal(header, 136, 12, 0)
            header[156] = type.code.toByte()
            put(header, 157, 100, link)
            put(header, 257, 6, "ustar\u0000")
            put(header, 263, 2, "00")
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            val checksum = header.sumOf { it.toInt() and 0xff }
            putOctal(header, 148, 8, checksum.toLong())
            output.write(header)
            output.write(data)
            repeat((512 - data.size % 512) % 512) { output.write(0) }
        }

        fun finish() {
            output.write(ByteArray(1024))
        }

        private fun put(buffer: ByteArray, offset: Int, length: Int, value: String) {
            val bytes = value.toByteArray()
            bytes.copyInto(buffer, offset, 0, minOf(bytes.size, length))
        }

        private fun putOctal(buffer: ByteArray, offset: Int, length: Int, value: Long) {
            val text = value.toString(8).padStart(length - 1, '0') + "\u0000"
            put(buffer, offset, length, text)
        }
    }
}

private fun Result<Unit>.getOrThrowFailure() {
    if (isSuccess) throw AssertionError("Expected safe extractor to reject the archive")
}
