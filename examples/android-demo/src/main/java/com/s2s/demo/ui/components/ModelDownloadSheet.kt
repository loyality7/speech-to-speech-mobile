package com.s2s.demo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s2s.demo.downloader.ModelType
import com.s2s.demo.ui.theme.*

data class ModelItemUiState(
    val modelType: ModelType,
    val isDownloaded: Boolean,
    val isDownloading: Boolean = false,
    val downloadProgress: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadSheet(
    models: List<ModelItemUiState>,
    onDownloadClick: (ModelType) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DarkSurface,
        scrimColor = DarkBg.copy(alpha = 0.7f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "On-Device AI Models",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Download local weights to run 100% offline speech & reasoning",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(models) { item ->
                    ModelRow(
                        item = item,
                        onDownload = { onDownloadClick(item.modelType) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModelRow(
    item: ModelItemUiState,
    onDownload: () -> Unit
) {
    val sizeMb = item.modelType.approximateSizeBytes / (1024 * 1024)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkCard)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.modelType.displayName,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$sizeMb MB • ${item.modelType.fileName}",
                color = TextTertiary,
                fontSize = 11.sp
            )

            if (item.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { item.downloadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = PrimaryCyan,
                    trackColor = DarkBorder
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (item.isDownloaded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = AccentEmerald,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Ready",
                    color = AccentEmerald,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else if (item.isDownloading) {
            Text(
                text = "${item.downloadProgress}%",
                color = PrimaryCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Button(
                onClick = onDownload,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = TextPrimary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = "Get", fontSize = 12.sp, color = TextPrimary)
            }
        }
    }
}
