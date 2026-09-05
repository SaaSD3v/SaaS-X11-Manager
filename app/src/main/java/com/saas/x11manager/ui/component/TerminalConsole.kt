package com.saas.x11manager.ui.component

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.theme.JetBrainsMono
import com.saas.x11manager.util.AnsiColorParser
import com.saas.x11manager.util.Constants

/**
 * Compatibility wrapper kept for callers that already use the old shimmer API.
 *
 * A continuously animated gradient is intentionally avoided here. Package-manager
 * output can update the terminal many times per second, so animating the entire
 * console at the same time adds GPU/composition work without conveying additional
 * progress information. The streamed output itself is the activity indicator.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ShimmerAnimation(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    TerminalFrame(modifier = modifier, content = content)
}

@Composable
private fun TerminalFrame(
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val framedModifier = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            shape = shape
        )

    Surface(
        modifier = framedModifier,
        color = Color.Transparent,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        content()
    }
}

@Composable
fun TerminalConsole(
    logs: List<Pair<Int, String>>,
    isProcessing: Boolean = true,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    showDetails: Boolean = false
) {
    val orientation = LocalConfiguration.current.orientation
    val listState = rememberLazyListState()
    val horizontalScrollState = rememberScrollState()
    val displayLogs by remember(logs, showDetails) {
        derivedStateOf { presentTerminalLogs(logs, showDetails) }
    }

    var userScrolledUp by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (isScrolling, canScrollForward) ->
                if (isScrolling && !isAutoScrolling) {
                    userScrolledUp = canScrollForward
                } else if (!canScrollForward) {
                    userScrolledUp = false
                }
            }
    }

    LaunchedEffect(orientation, showDetails) {
        userScrolledUp = false
        if (displayLogs.isNotEmpty()) {
            listState.scrollToItem(displayLogs.lastIndex)
        }
    }

    LaunchedEffect(displayLogs.size, userScrolledUp, showDetails) {
        if (displayLogs.isEmpty() || userScrolledUp) return@LaunchedEffect

        // Streaming package-manager output can append hundreds of lines quickly.
        // An animated scroll per line queues unnecessary animation work and makes
        // the terminal lag behind the producer. Jumping to the newest item keeps
        // the same auto-follow behavior while making the cost effectively constant.
        isAutoScrolling = true
        try {
            listState.scrollToItem(displayLogs.lastIndex)
        } finally {
            isAutoScrolling = false
        }
    }

    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            userScrolledUp = false
            isAutoScrolling = false
        }
    }

    val defaultTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    val errorColor = MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
    val warnColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.9f)

    ShimmerAnimation(
        modifier = if (maxHeight != null) modifier.heightIn(max = maxHeight) else modifier,
        enabled = isProcessing
    ) {
        ProvideTextStyle(
            MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono)
        ) {
            val contentModifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .let { base ->
                    if (showDetails) base.horizontalScroll(horizontalScrollState) else base
                }

            Box(modifier = contentModifier) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(
                        count = displayLogs.size,
                        key = { index -> index }
                    ) { index ->
                        val (level, message) = displayLogs[index]
                        val annotatedText = remember(
                            level,
                            message,
                            defaultTextColor,
                            errorColor,
                            warnColor
                        ) {
                            val processedMessage = message.replace(
                                Regex(Regex.escape(Constants.DS_BINARY_PATH)),
                                "droidspaces"
                            )
                            val displayMessage = if (processedMessage.isEmpty()) {
                                "\u00A0"
                            } else {
                                processedMessage.replace(Regex("""^( +)""")) { match ->
                                    match.value.replace(" ", "\u00A0")
                                }
                            }
                            if (displayMessage.contains("\u001B[")) {
                                val defaultColor = when (level) {
                                    Log.ERROR -> errorColor
                                    Log.WARN -> warnColor
                                    else -> defaultTextColor
                                }
                                AnsiColorParser.parseAnsi(displayMessage, defaultColor)
                            } else {
                                androidx.compose.ui.text.AnnotatedString(
                                    text = displayMessage,
                                    spanStyle = androidx.compose.ui.text.SpanStyle(
                                        color = when (level) {
                                            Log.ERROR -> errorColor
                                            Log.WARN -> warnColor
                                            else -> defaultTextColor
                                        }
                                    )
                                )
                            }
                        }

                        Text(
                            text = annotatedText,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                            softWrap = !showDetails,
                            modifier = if (showDetails) {
                                Modifier.wrapContentWidth().heightIn(min = 16.dp)
                            } else {
                                Modifier.fillMaxWidth().heightIn(min = 16.dp)
                            }
                        )
                    }
                }
            }
        }
    }
}
