package com.saas.x11manager.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saas.x11manager.operations.LogOperation

/** The owning screen always offers a way back, including when notifications are disabled. */
@Composable
fun OperationResultCard(operation: LogOperation, onShowLogs: () -> Unit) {
    if (!operation.available) return
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(operation.title, style = MaterialTheme.typography.titleSmall)
            if (operation.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("In progress", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(operation.result.ifBlank { "Logs saved" }, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onShowLogs) { Text("View logs") }
        }
    }
}
