package com.opendroid.ai.core.pty

import java.io.Closeable

internal object PtyNative {
    init {
        System.loadLibrary("opendroid_pty")
    }

    external fun nativeStart(
        executable: String,
        arguments: Array<String>,
        workingDirectory: String,
        environment: Array<String>
    ): Long

    external fun nativeRead(handle: Long, destination: ByteArray, timeoutMs: Int): Int
    external fun nativeWrite(handle: Long, source: ByteArray): Int
    external fun nativeResize(handle: Long, rows: Int, columns: Int): Boolean
    external fun nativeExitCode(handle: Long): Int
    external fun nativeClose(handle: Long)
}

class PtySession private constructor(private val handle: Long) : Closeable {
    fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        check(PtyNative.nativeWrite(handle, bytes) == bytes.size) { "PTY write failed" }
    }

    fun readAvailable(maxBytes: Int = 64 * 1024, timeoutMs: Int = 50): String {
        val result = StringBuilder()
        val buffer = ByteArray(8192)
        while (result.toString().toByteArray(Charsets.UTF_8).size < maxBytes) {
            val count = PtyNative.nativeRead(handle, buffer, timeoutMs)
            if (count <= 0) break
            result.append(String(buffer, 0, count, Charsets.UTF_8))
            if (count < buffer.size) break
        }
        return result.toString()
    }

    fun resize(rows: Int, columns: Int) {
        check(PtyNative.nativeResize(handle, rows, columns)) { "PTY resize failed" }
    }

    fun isAlive(): Boolean = PtyNative.nativeExitCode(handle) == -1

    fun exitCode(): Int = PtyNative.nativeExitCode(handle)

    override fun close() {
        PtyNative.nativeClose(handle)
    }

    companion object {
        fun start(
            executable: String,
            arguments: Array<String>,
            workingDirectory: String,
            environment: Map<String, String>
        ): PtySession {
            val handle = PtyNative.nativeStart(
                executable,
                arguments,
                workingDirectory,
                environment.map { (key, value) -> "$key=$value" }.toTypedArray()
            )
            check(handle != 0L) { "Could not start PTY process" }
            return PtySession(handle)
        }
    }
}
