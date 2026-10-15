package com.opendroid.ai.core.service

import com.opendroid.ai.core.linux.LinuxRuntimeManager
import com.opendroid.ai.core.pty.PtySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class TerminalSessionInfo(
    val id: String,
    val backend: CommandBackend
)

@Singleton
class PersistentTerminalManager @Inject constructor(
    private val linuxRuntime: LinuxRuntimeManager
) {
    private val sessions = ConcurrentHashMap<String, PtySession>()

    suspend fun create(): TerminalSessionInfo = withContext(Dispatchers.IO) {
        val process = linuxRuntime.startShell()
        val id = UUID.randomUUID().toString()
        sessions[id] = process
        TerminalSessionInfo(id, CommandBackend.INTEGRATED_LINUX)
    }

    suspend fun write(id: String, command: String) = withContext(Dispatchers.IO) {
        require(command.length <= MAX_COMMAND_LENGTH) { "Command is too long" }
        val process = requireSession(id)
        process.write(command + "\n")
    }

    suspend fun read(id: String): String = withContext(Dispatchers.IO) {
        val process = requireSession(id)
        process.readAvailable(MAX_OUTPUT_BYTES, READ_TIMEOUT_MS.toInt())
    }

    fun list(): List<TerminalSessionInfo> = sessions.keys().asSequence()
        .map { TerminalSessionInfo(it, CommandBackend.INTEGRATED_LINUX) }
        .toList()

    fun close(id: String) {
        sessions.remove(id)?.let { process ->
            process.close()
        }
    }

    fun closeAll() = sessions.keys().toList().forEach(::close)

    private fun requireSession(id: String): PtySession = sessions[id]
        ?: throw IllegalArgumentException("Unknown terminal session: $id")

    private companion object {
        const val MAX_COMMAND_LENGTH = 4096
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val READ_TIMEOUT_MS = 1000L
    }
}
