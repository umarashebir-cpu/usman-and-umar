package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.linux.LinuxRuntimeManager
import com.opendroid.ai.core.linux.RuntimeStatus
import com.opendroid.ai.core.service.PersistentTerminalManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinuxTerminalViewModel @Inject constructor(
    private val runtime: LinuxRuntimeManager,
    private val sessions: PersistentTerminalManager
) : ViewModel() {
    private val _status = MutableStateFlow(runtime.status())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()
    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private var sessionId: String? = null

    fun refresh() { _status.value = runtime.status() }

    fun install() = viewModelScope.launch {
        _message.value = "Downloading and verifying Alpine ARMv7 rootfs…"
        runCatching { runtime.install() }
            .onSuccess { _status.value = runtime.status(); _message.value = "Alpine rootfs installed. PRoot is still required for shell startup." }
            .onFailure { _message.value = it.message ?: "Linux installation failed" }
    }

    fun start() = viewModelScope.launch {
        runCatching { sessions.create() }
            .onSuccess { info -> sessionId = info.id; _message.value = "Integrated Linux session started (${info.backend})."; read() }
            .onFailure { _message.value = it.message ?: "Could not start integrated Linux" }
    }

    fun send(command: String) = viewModelScope.launch {
        val id = sessionId ?: run { _message.value = "Start a Linux session first."; return@launch }
        runCatching { sessions.write(id, command); sessions.read(id) }
            .onSuccess { _output.value += it }
            .onFailure { _message.value = it.message ?: "Command failed" }
    }

    fun read() = viewModelScope.launch {
        sessionId?.let { id -> runCatching { sessions.read(id) }.onSuccess { _output.value += it } }
    }

    fun clearOutput() { _output.value = "" }

    override fun onCleared() { sessions.closeAll() }
}
