package com.fazzbro.reclip.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fazzbro.reclip.data.model.MediaFormatType
import com.fazzbro.reclip.ui.theme.DarkPillActive
import com.fazzbro.reclip.ui.theme.DarkPillActiveText
import com.fazzbro.reclip.ui.theme.LightPillActive
import com.fazzbro.reclip.ui.theme.LightPillActiveText

@Composable
fun SegmentedFormatPicker(
    selectedFormat: MediaFormatType,
    onFormatSelected: (MediaFormatType) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val formats = listOf(
            MediaFormatType.VIDEO to "VIDEO (MP4)",
            MediaFormatType.AUDIO to "AUDIO (MP3)"
        )

        formats.forEach { (format, label) ->
            val isSelected = selectedFormat == format
            val isDark = MaterialTheme.colorScheme.background.red < 0.5f

            val targetBg = if (isSelected) {
                if (isDark) DarkPillActive else LightPillActive
            } else {
                MaterialTheme.colorScheme.surface
            }

            val targetText = if (isSelected) {
                if (isDark) DarkPillActiveText else LightPillActiveText
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }

            val bgColor by animateColorAsState(targetValue = targetBg, label = "bg")
            val textColor by animateColorAsState(targetValue = targetText, label = "text")

            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(bgColor)
                    .clickable { onFormatSelected(format) }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    color = textColor
                )
            }
        }
    }
}
