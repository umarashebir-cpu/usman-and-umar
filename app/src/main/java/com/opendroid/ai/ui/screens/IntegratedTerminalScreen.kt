package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.opendroid.ai.core.linux.RuntimeStatus
import com.opendroid.ai.ui.theme.AppTheme
import com.opendroid.ai.ui.viewmodel.LinuxTerminalViewModel

@Composable
fun IntegratedTerminalScreen(viewModel: LinuxTerminalViewModel, onBack: () -> Unit) {
    val status by viewModel.status.collectAsState()
    val output by viewModel.output.collectAsState()
    val message by viewModel.message.collectAsState()
    var command by remember { mutableStateOf("echo USMAN_AND_UMAR_TERMINAL_OK") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("INTEGRATED LINUX TERMINAL", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        },
        containerColor = AppTheme.colors.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Backend: Alpine ARMv7 under Termux-maintained PRoot", color = AppTheme.colors.textSecondary) }
            item {
                Text(
                    when (status) {
                        RuntimeStatus.READY -> "READY: verified runtime available"
                        RuntimeStatus.PROOT_MISSING -> "Alpine is installed, but the verified ARM32 PRoot executable is missing."
                        RuntimeStatus.NOT_INSTALLED -> "NOT INSTALLED: download Alpine on first use."
                    },
                    color = if (status == RuntimeStatus.READY) AppTheme.colors.textPrimary else AppTheme.colors.accentRed
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.install() }, modifier = Modifier.weight(1f), enabled = status != RuntimeStatus.READY) { Text("INSTALL / REPAIR") }
                    OutlinedButton(onClick = { viewModel.start() }, modifier = Modifier.weight(1f), enabled = status == RuntimeStatus.READY) { Text("START SHELL") }
                }
            }
            item {
                OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), label = { Text("Command") }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { viewModel.send(command) }, modifier = Modifier.weight(1f), enabled = status == RuntimeStatus.READY) { Text("RUN") }
                    OutlinedButton(onClick = viewModel::clearOutput, modifier = Modifier.weight(1f)) { Text("CLEAR") }
                }
            }
            if (!message.isNullOrBlank()) item { Text(message!!, color = AppTheme.colors.textSecondary) }
            item { Text(output.ifBlank { "No command output yet." }, color = AppTheme.colors.textPrimary) }
        }
    }
}
