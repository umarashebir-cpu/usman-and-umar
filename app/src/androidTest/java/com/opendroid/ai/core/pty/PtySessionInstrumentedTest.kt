package com.opendroid.ai.core.pty

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PtySessionInstrumentedTest {
    @Test(timeout = 10_000)
    fun shellSpawnsAndExitsWithinTimeout() {
        val session = PtySession.start(
            executable = "/system/bin/sh",
            arguments = arrayOf("-c", "echo hello; exit 0"),
            workingDirectory = "/data/local/tmp",
            environment = shellEnvironment()
        )
        try {
            val output = readUntil(session, "hello", timeoutMs = 5_000)
            assertTrue("PTY output was: $output", output.contains("hello"))
            assertEquals("PTY exit code mismatch", 0, waitForExit(session, 5_000))
        } finally {
            session.close()
        }
    }

    @Test(timeout = 10_000)
    fun interactiveShellEchoesAndExitsWithinTimeout() {
        val session = PtySession.start(
            executable = "/system/bin/sh",
            arguments = emptyArray(),
            workingDirectory = "/data/local/tmp",
            environment = shellEnvironment()
        )
        try {
            session.resize(rows = 24, columns = 80)
            session.write("echo USMAN_AND_UMAR_TEST_OK\n")
            val output = readUntil(session, "USMAN_AND_UMAR_TEST_OK", timeoutMs = 5_000)
            assertTrue("PTY did not echo expected output: $output", output.contains("USMAN_AND_UMAR_TEST_OK"))
            session.write("exit\n")
            waitForExit(session, 5_000)
        } finally {
            session.close()
        }
    }

    private fun shellEnvironment() = mapOf(
        "TERM" to "xterm-256color",
        "PATH" to "/system/bin:/system/xbin"
    )

    private fun readUntil(session: PtySession, marker: String, timeoutMs: Long): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val output = StringBuilder()
        while (System.nanoTime() < deadline && !output.contains(marker)) {
            output.append(session.readAvailable(timeoutMs = 100))
        }
        return output.toString()
    }

    private fun waitForExit(session: PtySession, timeoutMs: Long): Int {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && session.isAlive()) {
            session.readAvailable(timeoutMs = 100)
        }
        assertTrue("PTY process did not exit within ${timeoutMs}ms", !session.isAlive())
        return session.exitCode()
    }
}
