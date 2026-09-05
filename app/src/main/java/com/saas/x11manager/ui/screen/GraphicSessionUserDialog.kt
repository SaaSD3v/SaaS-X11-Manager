package com.saas.x11manager.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.saas.x11manager.util.GraphicSessionUser
import com.saas.x11manager.util.GraphicSessionUserManager
import com.saas.x11manager.util.GraphicSessionUserSelection

@Composable
internal fun GraphicSessionUserDialog(
    containerName: String,
    onDismiss: () -> Unit,
    onConfirm: (GraphicSessionUserSelection) -> Unit
) {
    var selection by remember(containerName) {
        mutableStateOf(GraphicSessionUserSelection.ROOT)
    }

    LaunchedEffect(containerName) {
        GraphicSessionUserManager.currentSelection(containerName)?.let { saved ->
            selection = saved
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Choose Linux user", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$containerName · graphical session owner",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                GraphicSessionUserPicker(
                    containerName = containerName,
                    selection = selection,
                    onSelectionChange = { selection = it }
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selection) },
                        enabled = isValidGraphicUserSelection(selection)
                    ) {
                        Text("Continue")
                    }
                }
            }
        }
    }
}

@Composable
internal fun GraphicSessionUserPicker(
    containerName: String,
    selection: GraphicSessionUserSelection,
    onSelectionChange: (GraphicSessionUserSelection) -> Unit
) {
    var users by remember(containerName) { mutableStateOf<List<GraphicSessionUser>>(emptyList()) }
    var loading by remember(containerName) { mutableStateOf(true) }
    var loadError by remember(containerName) { mutableStateOf<String?>(null) }
    var newUserName by remember(containerName) { mutableStateOf("") }

    LaunchedEffect(containerName) {
        loading = true
        loadError = null
        try {
            users = GraphicSessionUserManager.listUsers(containerName)
            if (users.none { it.name == "root" }) {
                users = users + GraphicSessionUser(
                    name = "root",
                    uid = 0,
                    gid = 0,
                    home = "/root",
                    shell = "/bin/sh"
                )
            }
        } catch (e: Exception) {
            loadError = e.message ?: "Could not read Linux users"
            users = listOf(
                GraphicSessionUser("root", 0, 0, "/root", "/bin/sh")
            )
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Run desktop as", style = MaterialTheme.typography.titleSmall)
        Text(
            "The selected account owns its home and the files created by the desktop session. System installation still runs as root.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                Text("Reading users...", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(users, key = { it.name }) { user ->
                    UserChoice(
                        title = user.name,
                        subtitle = if (user.isRoot) {
                            "root · /root"
                        } else {
                            "UID ${user.uid} · ${user.home}"
                        },
                        selected = !selection.createIfMissing && selection.userName == user.name,
                        onClick = {
                            onSelectionChange(
                                GraphicSessionUserSelection(
                                    userName = user.name,
                                    createIfMissing = false
                                )
                            )
                        }
                    )
                }
            }
        }

        UserChoice(
            title = "Create a user",
            subtitle = "Creates a basic account with its own home. No admin groups or password login are configured.",
            selected = selection.createIfMissing,
            onClick = {
                onSelectionChange(
                    GraphicSessionUserSelection(
                        userName = newUserName,
                        createIfMissing = true
                    )
                )
            }
        )

        if (selection.createIfMissing) {
            OutlinedTextField(
                value = newUserName,
                onValueChange = { value ->
                    if (value.length <= 32 && value.none { it.isWhitespace() }) {
                        newUserName = value
                        onSelectionChange(
                            GraphicSessionUserSelection(
                                userName = value,
                                createIfMissing = true
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("User name") },
                supportingText = {
                    Text("Case-sensitive Linux user name. Home and UID/GID are created automatically.")
                },
                singleLine = true,
                isError = newUserName.isNotEmpty() &&
                    !GraphicSessionUserManager.isValidUserName(newUserName)
            )
        }

        loadError?.let {
            Text(
                "$it · root remains available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun isValidGraphicUserSelection(selection: GraphicSessionUserSelection): Boolean =
    GraphicSessionUserManager.isValidUserName(selection.userName)

@Composable
private fun UserChoice(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
