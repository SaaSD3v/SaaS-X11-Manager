package com.saas.x11manager.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saas.x11manager.operations.LogOperation

/**
 * Inline operation affordance shown only while work is active.
 *
 * Completed logs remain owned by OperationLogStore and are reopened from the
 * screen's normal logs affordance or from a minimized notification. Keeping
 * completion/history out of this card prevents finished operations from
 * permanently occupying screen content.
 */
@Composable
fun OperationResultCard(operation: LogOperation, onShowLogs: () -> Unit) {
    if (!operation.running) return

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(operation.title, style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("In progress", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onShowLogs) { Text("View logs") }
        }
    }
}
