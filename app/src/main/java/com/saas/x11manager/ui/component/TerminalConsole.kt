package com.saas.x11manager.ui.component

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.saas.x11manager.ui.theme.JetBrainsMono
import com.saas.x11manager.util.AnsiColorParser
import com.saas.x11manager.util.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ShimmerAnimation(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        TerminalFrame(modifier = modifier, content = content)
        return
    }

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "shimmerTranslate"
    )

    val shades = listOf(
        Color.Transparent,
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
        Color.Transparent
    )
    val brush = Brush.linearGradient(
        colors = shades,
        start = Offset(10f, 10f),
        end = Offset(translateAnim, translateAnim)
    )

    TerminalFrame(modifier = modifier, brush = brush, content = content)
}

@Composable
private fun TerminalFrame(
    modifier: Modifier,
    brush: Brush? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val framedModifier = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .let { base -> if (brush != null) base.background(brush) else base }
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
    maxHeight: Dp? = null
) {
    val orientation = LocalConfiguration.current.orientation
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(orientation) {
        verticalScrollState.scrollTo(verticalScrollState.maxValue)
    }

    var userScrolledUp by remember { mutableStateOf(false) }
    var isAutoScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(verticalScrollState) {
        snapshotFlow { verticalScrollState.value }
            .collect { value ->
                if (!isAutoScrolling) {
                    userScrolledUp = value < verticalScrollState.maxValue - 200
                }
            }
    }

    LaunchedEffect(Unit) {
        var scrollJob: Job? = null
        snapshotFlow {
            Pair(
                Triple(logs.size, verticalScrollState.maxValue, userScrolledUp),
                isProcessing
            )
        }.collect { (triple, processing) ->
            val (_, maxValue, scrolledUp) = triple
            if (!scrolledUp && verticalScrollState.value < maxValue) {
                scrollJob?.cancel()
                scrollJob = launch {
                    isAutoScrolling = true
                    try {
                        if (processing) {
                            verticalScrollState.animateScrollTo(
                                value = maxValue,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        } else {
                            verticalScrollState.scrollTo(maxValue)
                        }
                    } finally {
                        isAutoScrolling = false
                    }
                }
            }
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .verticalScroll(verticalScrollState)
                    .horizontalScroll(horizontalScrollState)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logs.forEach { (level, message) ->
                        val annotatedText = remember(level, message) {
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
                            softWrap = false,
                            modifier = Modifier.wrapContentWidth().heightIn(min = 16.dp)
                        )
                    }
                }
            }
        }
    }
}
