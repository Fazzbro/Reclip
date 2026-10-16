package com.fazzbro.reclip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fazzbro.reclip.data.model.DownloadItem
import com.fazzbro.reclip.data.model.MediaFormatType
import com.fazzbro.reclip.ui.components.HistoryItemCard
import com.fazzbro.reclip.ui.theme.ReclipTerracotta
import com.fazzbro.reclip.ui.theme.StatusError

enum class HistoryFilter {
    ALL,
    VIDEOS,
    AUDIO
}

@Composable
fun HistoryScreen(
    historyItems: List<DownloadItem>,
    onPlayClick: (DownloadItem) -> Unit,
    onShareClick: (DownloadItem) -> Unit,
    onDeleteClick: (DownloadItem, Boolean) -> Unit,
    onClearAllClick: () -> Unit,
    onOpenFolderClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var itemToDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    val filteredItems = historyItems.filter { item ->
        when (selectedFilter) {
            HistoryFilter.ALL -> true
            HistoryFilter.VIDEOS -> item.formatType == MediaFormatType.VIDEO
            HistoryFilter.AUDIO -> item.formatType == MediaFormatType.AUDIO
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Downloads",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 30.sp,
                        fontFamily = FontFamily.Serif
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${historyItems.size} TOTAL ITEMS SAVED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = onOpenFolderClick) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = "Open Folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (historyItems.isNotEmpty()) {
                    IconButton(onClick = { showClearAllDialog = true }) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear All",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Filter Pills
        val filterShape = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, filterShape)
                .clip(filterShape)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            listOf(
                HistoryFilter.ALL to "ALL",
                HistoryFilter.VIDEOS to "VIDEOS",
                HistoryFilter.AUDIO to "AUDIO"
            ).forEach { (filter, label) ->
                val isSelected = selectedFilter == filter
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surface
                        )
                        .clickable { selectedFilter = filter }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownloadOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Downloads Found",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Downloaded media will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredItems, key = { it.id }) { item ->
                    HistoryItemCard(
                        item = item,
                        onPlayClick = { onPlayClick(item) },
                        onShareClick = { onShareClick(item) },
                        onDeleteClick = { itemToDelete = item }
                    )
                }
            }
        }
    }

    // Delete single item dialog
    if (itemToDelete != null) {
        val target = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = {
                Text(
                    text = "Delete Media",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif)
                )
            },
            text = {
                Text(
                    text = "Do you want to delete \"${target.title}\" from your download history, or also remove the file from your device?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteClick(target, true)
                        itemToDelete = null
                    }
                ) {
                    Text("Delete File", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDeleteClick(target, false)
                        itemToDelete = null
                    }
                ) {
                    Text("History Only")
                }
            }
        )
    }

    // Clear all history dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = {
                Text(
                    text = "Clear All History",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif)
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to clear your download history? Your downloaded files on disk will not be deleted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearAllClick()
                        showClearAllDialog = false
                    }
                ) {
                    Text("Clear All", color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
