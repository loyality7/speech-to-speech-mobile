package com.s2s.demo.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s2s.demo.ui.theme.*

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

@Composable
fun PulsingVoiceOrb(
    state: VoiceState,
    audioEnergy: Float, // 0.0 to 1.0 from VAD
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )

    val (brush, stateText, icon) = when (state) {
        VoiceState.IDLE -> Triple(
            Brush.linearGradient(listOf(DarkCard, DarkBorder)),
            "Tap to Start Session",
            Icons.Default.Mic
        )
        VoiceState.LISTENING -> Triple(
            Brush.sweepGradient(listOf(OrbListeningStart, OrbListeningEnd, PrimaryBlue, OrbListeningStart)),
            "Listening...",
            Icons.Default.Mic
        )
        VoiceState.THINKING -> Triple(
            Brush.sweepGradient(listOf(OrbThinkingStart, OrbThinkingEnd, AccentPurple, OrbThinkingStart)),
            "Thinking...",
            Icons.Default.Psychology
        )
        VoiceState.SPEAKING -> Triple(
            Brush.sweepGradient(listOf(OrbSpeakingStart, OrbSpeakingEnd, AccentEmerald, OrbSpeakingStart)),
            "Speaking (Tap to interrupt)",
            Icons.Default.VolumeUp
        )
    }

    val dynamicEnergyScale = if (state == VoiceState.LISTENING || state == VoiceState.SPEAKING) {
        1.0f + (audioEnergy * 0.35f)
    } else {
        1.0f
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(190.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        ) {
            // Background ambient glow blur
            if (state != VoiceState.IDLE) {
                Box(
                    modifier = Modifier
                        .size(170.dp)
                        .scale(pulseScale * dynamicEnergyScale)
                        .blur(32.dp)
                        .background(brush, shape = CircleShape)
                )
            }

            // Outer ring
            Box(
                modifier = Modifier
                    .size(145.dp)
                    .scale(if (state != VoiceState.IDLE) pulseScale * dynamicEnergyScale else 1f)
                    .background(brush, shape = CircleShape)
            )

            // Inner core
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(115.dp)
                    .background(DarkSurface, shape = CircleShape)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = when (state) {
                        VoiceState.IDLE -> TextSecondary
                        VoiceState.LISTENING -> PrimaryCyan
                        VoiceState.THINKING -> AccentPurple
                        VoiceState.SPEAKING -> AccentEmerald
                    },
                    modifier = Modifier.size(44.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stateText,
            color = if (state == VoiceState.IDLE) TextSecondary else TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
