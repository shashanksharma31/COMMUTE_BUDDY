package com.example.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DataLoadState
import com.example.lib.ChatMessage
import com.example.lib.ChatTripContext
import com.example.lib.CommuteFormatters
import com.example.lib.LocalTripContext
import com.example.lib.extractPlacesFromQuestion
import com.example.lib.streamChatMessage
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBackground
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary
import kotlinx.coroutines.launch

private val SUGGESTION_CHIPS = listOf(
    "Which modes are available here?",
    "Compare Local vs Ola/Uber fares",
    "What if I miss my connection?",
    "Leave by what time?"
)

// Fixed bottom ChatBar:
// - Collapsed: white 48px bar with sparkle icon and "Ask about your trip".
// - Expanded: 64% height panel with message list, suggestion chips, input and send button.
// - Linked with Firebase AI Logic (gemini-3.8-flash) streaming and live city mode availability.
@Composable
fun ChatBar(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val tripContext = LocalTripContext.current
    val scope = rememberCoroutineScope()

    var inputText by rememberSaveable { mutableStateOf("") }
    var isWaitingForReply by remember { mutableStateOf(false) }
    var chatState by remember { mutableStateOf(DataLoadState.READY) }
    var lastFailedPrompt by remember { mutableStateOf<String?>(null) }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                id = "welcome_msg",
                text = "Gemini AI Transit Assistant is active. Ask about available transport modes in your city, train platforms, live traffic, or Local vs Ola/Uber fares.",
                isUser = false
            )
        )
    }

    // Automatically keep the top assistant briefing synced with the user's selected origin, destination, and mode
    LaunchedEffect(
        tripContext.startPlace?.id,
        tripContext.endPlace?.id,
        tripContext.selectedMode,
        tripContext.selectedRoute?.id
    ) {
        val start = tripContext.startPlace
        val end = tripContext.endPlace
        if (start != null && end != null) {
            val avail = tripContext.modeAvailability
            val modesList = avail?.availableModes?.joinToString(", ") { it.label } ?: "Mix, Bus, Car, Bike, Walk"
            val metroNote = when {
                avail == null -> ""
                !avail.hasMetroAtStart && !avail.hasMetroAtEnd ->
                    " (No operational Metro in ${start.area} or ${end.area}; using ${avail.localBusOperator} & local transit)"
                avail.metroSingleModeAvailable ->
                    " (${avail.metroOperator ?: "Metro"} active)"
                else ->
                    " (Direct Metro unavailable; intercity Mix active)"
            }
            val route = tripContext.selectedRoute ?: tripContext.recommendedRoute
            val routeNote = if (route != null) {
                val dur = CommuteFormatters.formatDuration(route.durationSeconds)
                val olaPart = if (route.olaUberFareDisplay.isNotBlank()) " • ${route.olaUberFareDisplay}" else ""
                " Recommended ${tripContext.selectedMode.label} option: $dur (${route.distanceKm} km) at ${route.costDisplay}$olaPart."
            } else {
                ""
            }
            val summaryText = "Active trip: ${start.name} to ${end.name}. Verified modes: $modesList$metroNote.$routeNote Ask a question below or tap 'Routes' to view step-by-step options."
            val welcomeIdx = messages.indexOfFirst { it.id == "welcome_msg" }
            if (welcomeIdx >= 0) {
                messages[welcomeIdx] = ChatMessage(
                    id = "welcome_msg",
                    text = summaryText,
                    isUser = false
                )
            } else {
                messages.add(
                    0,
                    ChatMessage(
                        id = "welcome_msg",
                        text = summaryText,
                        isUser = false
                    )
                )
            }
        }
    }

    fun submitQuestion(questionText: String) {
        val trimmed = questionText.trim()
        if (trimmed.isEmpty() || isWaitingForReply) return

        inputText = ""
        chatState = DataLoadState.READY
        messages.add(
            ChatMessage(
                id = "user_${messages.size}_${System.currentTimeMillis()}",
                text = trimmed,
                isUser = true
            )
        )
        isWaitingForReply = true

        // If user asked about a specific origin -> destination in chat, sync with TripContext without closing chat
        val (extractedStart, extractedEnd) = extractPlacesFromQuestion(trimmed)
        val effStart = tripContext.startPlace ?: extractedStart
        val effEnd = tripContext.endPlace ?: extractedEnd

        val currentContext = ChatTripContext(
            start = effStart,
            end = effEnd,
            vehicle = tripContext.selectedMode,
            selectedRoute = tripContext.selectedRoute,
            reachByTime = tripContext.reachByTime
        )

        val assistantMsgId = "assistant_${messages.size}_${System.currentTimeMillis()}"
        var streamingBubbleIndex = -1

        scope.launch {
            try {
                val finalReply = streamChatMessage(
                    message = trimmed,
                    context = currentContext,
                    onPartialUpdate = { partialText ->
                        if (streamingBubbleIndex == -1) {
                            messages.add(
                                ChatMessage(
                                    id = assistantMsgId,
                                    text = partialText,
                                    isUser = false
                                )
                            )
                            streamingBubbleIndex = messages.lastIndex
                        } else if (streamingBubbleIndex in messages.indices) {
                            messages[streamingBubbleIndex] = ChatMessage(
                                id = assistantMsgId,
                                text = partialText,
                                isUser = false
                            )
                        }
                    }
                )
                if (streamingBubbleIndex == -1) {
                    messages.add(
                        ChatMessage(
                            id = assistantMsgId,
                            text = finalReply,
                            isUser = false
                        )
                    )
                } else if (streamingBubbleIndex in messages.indices) {
                    messages[streamingBubbleIndex] = ChatMessage(
                        id = assistantMsgId,
                        text = finalReply,
                        isUser = false
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { android.util.Log.e("ChatBar", "Chat message generation failed", e) }
                lastFailedPrompt = trimmed
                chatState = DataLoadState.ERROR
            } finally {
                isWaitingForReply = false
            }
        }
    }

    if (!isExpanded) {
        // Collapsed 48px white bar fixed at the bottom
        Surface(
            color = MapsSurface,
            border = BorderStroke(1.dp, MapsBorder),
            modifier = modifier
                .fillMaxWidth()
                .shadow(elevation = 2.dp)
                .testTag("chat_bar_collapsed")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onExpandedChange(true) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SparkleIcon(
                    tint = MapsAccentBlue,
                    iconSize = 24.dp,
                    contentDesc = "Ask about your trip"
                )
                Text(
                    text = "Ask about your trip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MapsTextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "Gemini AI",
                    style = MaterialTheme.typography.labelLarge,
                    color = MapsAccentBlue,
                    modifier = Modifier
                        .background(MapsSelectedTint, CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    } else {
        BackHandler { onExpandedChange(false) }

        val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
        Surface(
            shape = topShape,
            color = MapsSurface,
            border = BorderStroke(1.dp, MapsBorder),
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(0.54f)
                .shadow(elevation = 2.dp, shape = topShape, clip = false)
                .testTag("chat_panel_expanded")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                // Header bar with proper vertical padding so text never touches borders
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SparkleIcon(
                        tint = MapsAccentBlue,
                        iconSize = 24.dp,
                        contentDesc = "Trip assistant"
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Ask about your trip",
                            style = MaterialTheme.typography.titleMedium,
                            color = MapsTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Gemini AI • Live route & fare context",
                            style = MaterialTheme.typography.bodySmall,
                            color = MapsAccentBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (tripContext.hasBothPlaces) {
                        Surface(
                            onClick = {
                                tripContext.setRoutesSheetOpenState(true)
                                onExpandedChange(false)
                            },
                            shape = CircleShape,
                            color = MapsSelectedTint,
                            border = BorderStroke(1.dp, MapsAccentBlue),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("chat_view_routes_button")
                        ) {
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Routes",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MapsAccentBlue
                                )
                            }
                        }
                    }
                    IconButton(
                        onClick = { onExpandedChange(false) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_chat_panel_button")
                    ) {
                        LucideXIcon(
                            tint = MapsTextSecondary,
                            iconSize = 24.dp,
                            contentDesc = "Collapse chat panel"
                        )
                    }
                }

                HorizontalDivider(color = MapsBorder, thickness = 1.dp)

                // Message list area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    if (chatState == DataLoadState.ERROR) {
                        ListStateView(
                            state = DataLoadState.ERROR,
                            emptyMessage = "Unable to send message",
                            onRetry = {
                                val retryPrompt = lastFailedPrompt
                                chatState = DataLoadState.READY
                                if (retryPrompt != null) {
                                    submitQuestion(retryPrompt)
                                }
                            }
                        )
                    } else if (messages.isEmpty() && !isWaitingForReply) {
                        ListStateView(
                            state = DataLoadState.EMPTY,
                            emptyMessage = "No messages yet",
                            onRetry = {
                                messages.add(
                                    ChatMessage(
                                        id = "welcome_reset",
                                        text = "Ask anything about your India trip, train platforms, metro timings, traffic, or Ola/Uber vs local fares.",
                                        isUser = false
                                    )
                                )
                            }
                        )
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(messages.size, messages.lastOrNull()?.text, isWaitingForReply) {
                            val totalCount = messages.size + if (isWaitingForReply) 1 else 0
                            if (totalCount > 0) {
                                runCatching {
                                    listState.animateScrollToItem(totalCount - 1)
                                }
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("chat_messages_list"),
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(messages, key = { it.id }) { msg ->
                                ChatBubbleRow(message = msg)
                            }

                            if (isWaitingForReply) {
                                item(key = "typing_indicator") {
                                    TypingIndicatorBubble()
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MapsBorder, thickness = 1.dp)

                // Three suggestion chips above the input
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("chat_suggestion_chips"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SUGGESTION_CHIPS.forEachIndexed { idx, suggestion ->
                        OutlinedButton(
                            onClick = { submitQuestion(suggestion) },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, MapsBorder),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MapsSurface,
                                contentColor = MapsAccentBlue
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("chat_suggestion_$idx")
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.labelLarge,
                                color = MapsAccentBlue
                            )
                        }
                    }
                }

                // Input row and Send button (48dp min touch target)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .background(MapsBackground, CircleShape)
                            .border(1.dp, MapsBorder, CircleShape)
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = "Ask about your trip",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MapsTextSecondary
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MapsTextPrimary),
                            cursorBrush = SolidColor(MapsAccentBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat_input_field")
                        )
                    }

                    IconButton(
                        onClick = { submitQuestion(inputText) },
                        enabled = inputText.isNotBlank() && !isWaitingForReply,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = if (inputText.isNotBlank()) MapsAccentBlue else MapsSelectedTint,
                                shape = CircleShape
                            )
                            .testTag("chat_send_button")
                    ) {
                        LucideSendIcon(
                            tint = if (inputText.isNotBlank()) MapsSurface else MapsAccentBlue,
                            iconSize = 24.dp,
                            contentDesc = "Send message"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubbleRow(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = if (message.isUser) MapsAccentBlue else MapsBackground,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(if (message.isUser) "user_bubble" else "assistant_bubble")
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.isUser) MapsSurface else MapsTextPrimary
            )
        }
    }
}

// Typing indicator: three grey dots (#5f6368), fade only (no bounce or spring animations)
@Composable
private fun TypingIndicatorBubble() {
    val transition = rememberInfiniteTransition(label = "typing_fade")
    val alpha1 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val alpha2 by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val alpha3 by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("typing_indicator"),
        horizontalArrangement = Arrangement.Start
    ) {
        Row(
            modifier = Modifier
                .background(MapsBackground, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha1)
                    .background(MapsTextSecondary, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha2)
                    .background(MapsTextSecondary, CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha3)
                    .background(MapsTextSecondary, CircleShape)
            )
        }
    }
}
