package com.s2s.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s2s.demo.ui.theme.*

data class LatencyMetrics(
    val vadTimeMs: Long = 0,
    val sttTimeMs: Long = 0,
    val ttftMs: Long = 0, // Time to First Token
    val ttsTimeMs: Long = 0,
    val bargeInCount: Int = 0
)

@Composable
fun MetricsHUD(
    metrics: LatencyMetrics,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface.copy(alpha = 0.85f))
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricItem(label = "VAD", value = "${metrics.vadTimeMs}ms", color = PrimaryCyan)
        MetricItem(label = "STT", value = "${metrics.sttTimeMs}ms", color = PrimaryBlue)
        MetricItem(label = "TTFT", value = "${metrics.ttftMs}ms", color = AccentPurple)
        MetricItem(label = "TTS", value = "${metrics.ttsTimeMs}ms", color = AccentEmerald)
        MetricItem(label = "Barge-Ins", value = "${metrics.bargeInCount}", color = AccentAmber)
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
