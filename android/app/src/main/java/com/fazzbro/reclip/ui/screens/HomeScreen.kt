package com.fazzbro.reclip.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fazzbro.reclip.data.model.DownloadItem
import com.fazzbro.reclip.data.model.FormatOption
import com.fazzbro.reclip.data.model.MediaFormatType
import com.fazzbro.reclip.data.model.MediaInfo
import com.fazzbro.reclip.ui.components.DownloadProgressCard
import com.fazzbro.reclip.ui.components.ReclipHeader
import com.fazzbro.reclip.ui.components.VideoInfoCard
import com.fazzbro.reclip.ui.theme.ReclipTerracotta
import com.fazzbro.reclip.ui.theme.StatusError
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    mediaInfo: MediaInfo?,
    isLoadingInfo: Boolean,
    infoError: String?,
    activeDownloads: List<DownloadItem>,
    hasCookies: Boolean,
    onFetchClick: (String) -> Unit,
    onDownloadStart: (MediaInfo, MediaFormatType, FormatOption?, String) -> Unit,
    onCancelDownload: (String) -> Unit,
    onOpenDownloadsClick: () -> Unit,
    onCookiesClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    var selectedFormat by remember { mutableStateOf(MediaFormatType.VIDEO) }
    var selectedQuality by remember(mediaInfo) {
        mutableStateOf(mediaInfo?.formats?.firstOrNull())
    }
    var selectedAudioLang by remember(mediaInfo) {
        mutableStateOf(mediaInfo?.audioLanguages?.firstOrNull()?.code ?: "default")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // Brand Header
        ReclipHeader(
            onOpenDownloadsClick = onOpenDownloadsClick,
            onCookiesClick = onCookiesClick,
            hasCookies = hasCookies
        )

        Spacer(modifier = Modifier.height(24.dp))

        // URL Input Box with PASTE button
        val inputShape = RoundedCornerShape(12.dp)
        OutlinedTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Paste video or audio URL...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            shape = inputShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ReclipTerracotta,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    focusManager.clearFocus()
                    if (urlInput.isNotBlank()) onFetchClick(urlInput)
                }
            ),
            trailingIcon = {
                Row(
                    modifier = Modifier.padding(end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (clipText.isNotBlank()) {
                                onUrlChange(clipText)
                                onFetchClick(clipText)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste",
                            tint = ReclipTerracotta,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "PASTE",
                            style = MaterialTheme.typography.labelSmall,
                            color = ReclipTerracotta
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // FETCH BUTTON
        Button(
            onClick = {
                focusManager.clearFocus()
                if (urlInput.isNotBlank()) onFetchClick(urlInput)
            },
            enabled = urlInput.isNotBlank() && !isLoadingInfo,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onBackground,
                contentColor = MaterialTheme.colorScheme.background
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
        ) {
            if (isLoadingInfo) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.background,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FETCHING DETAILS...",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Fetch",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "FETCH DETAILS",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        // Error message if fetch failed
        if (infoError != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, StatusError.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .background(StatusError.copy(alpha = 0.08f))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = "Error",
                        tint = StatusError,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = infoError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusError
                    )
                }
            }
        }

        // Active Downloads section (if any)
        if (activeDownloads.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "ACTIVE DOWNLOADS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            activeDownloads.forEach { downloadItem ->
                DownloadProgressCard(
                    item = downloadItem,
                    onCancelClick = { onCancelDownload(downloadItem.id) },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        // Media Info Card (when URL details fetched)
        AnimatedVisibility(
            visible = mediaInfo != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (mediaInfo != null) {
                Column {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "MEDIA DETAILS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    VideoInfoCard(
                        mediaInfo = mediaInfo,
                        selectedFormat = selectedFormat,
                        selectedQuality = selectedQuality,
                        selectedAudioLang = selectedAudioLang,
                        onFormatSelected = { selectedFormat = it },
                        onQualitySelected = { selectedQuality = it },
                        onAudioLangSelected = { selectedAudioLang = it },
                        onDownloadClick = {
                            onDownloadStart(
                                mediaInfo,
                                selectedFormat,
                                selectedQuality,
                                selectedAudioLang
                            )
                        },
                        isDownloading = false
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Supported platforms badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(14.dp)
        ) {
            Column {
                Text(
                    text = "SUPPORTED PLATFORMS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "YouTube • TikTok • Instagram • Twitter/X • Reddit • Facebook • Vimeo • Twitch • SoundCloud • Pinterest • Loom • Threads • 1000+ more",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
