package com.s2s.demo.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s2s.demo.ui.components.*
import com.s2s.demo.ui.theme.*
import com.s2s.demo.viewmodel.S2SUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun S2SScreen(
    uiState: S2SUiState,
    onToggleSession: () -> Unit,
    onBargeIn: () -> Unit,
    onShowModels: () -> Unit,
    onHideModels: () -> Unit,
    onDownloadModel: (com.s2s.demo.downloader.ModelType) -> Unit,
    onSendText: (String) -> Unit
) {
    var textInput by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top App Bar ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "SpeechToSpeech",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "100% On-Device AI Voice Engine",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Model status indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isLlmReady) AccentEmerald else AccentRose
                            )
                            .align(Alignment.CenterVertically)
                    )

                    // Settings / Model download button
                    IconButton(
                        onClick = onShowModels,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(DarkCard)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Models",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── Metrics HUD ─────────────────────────────────────────
            AnimatedVisibility(
                visible = uiState.voiceState != VoiceState.IDLE,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                MetricsHUD(
                    metrics = uiState.metrics,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ── Transcript Messages ─────────────────────────────────
            TranscriptView(
                messages = uiState.messages,
                currentLiveUserTranscript = uiState.liveTranscript,
                modifier = Modifier.weight(1f)
            )

            // ── Voice Orb ───────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                PulsingVoiceOrb(
                    state = uiState.voiceState,
                    audioEnergy = uiState.audioEnergy,
                    onClick = {
                        if (uiState.voiceState == VoiceState.SPEAKING) {
                            onBargeIn()
                        } else {
                            onToggleSession()
                        }
                    }
                )
            }

            // ── Status Text ─────────────────────────────────────────
            Text(
                text = uiState.statusText,
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .wrapContentWidth(Alignment.CenterHorizontally)
            )

            // ── Text Input (for testing without mic) ────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder, RoundedCornerShape(24.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(PrimaryCyan),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box {
                            if (textInput.isEmpty()) {
                                Text(
                                    text = "Type a message to test LLM...",
                                    color = TextTertiary,
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            onSendText(textInput.trim())
                            textInput = ""
                        }
                    },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(PrimaryBlue, PrimaryCyan))
                        ),
                    enabled = textInput.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── Model Download Bottom Sheet ─────────────────────────────
        if (uiState.showModelSheet) {
            ModelDownloadSheet(
                models = uiState.modelStates,
                onDownloadClick = onDownloadModel,
                onDismiss = onHideModels
            )
        }
    }
}
