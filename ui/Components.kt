package com.jof.ringtones.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jof.ringtones.R
import com.jof.ringtones.data.PlayerState
import com.jof.ringtones.data.Ringtone
import com.jof.ringtones.data.RingtoneKind
import java.util.Locale

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

@Composable
fun RingtoneRow(
    item: Ringtone,
    isCurrent: Boolean,
    isFavorite: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    isPreparing: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onMore: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        colors = if (isActive) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        ) else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    when {
                        isPreparing -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                        )
                        isActive && isPlaying -> Icon(Icons.Rounded.Pause, stringResource(R.string.pause))
                        else -> Icon(Icons.Rounded.PlayArrow, stringResource(R.string.play))
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${item.artist} • ${formatDuration(item.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isCurrent) {
                    Text(
                        stringResource(R.string.current_ringtone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.remove_favorite else R.string.add_favorite
                    ),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, stringResource(R.string.more_actions))
            }
        }
    }
}

@Composable
fun PlayerBar(
    state: PlayerState,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    onClose: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(
                    state.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onToggle, enabled = !state.isPreparing) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.isPlaying) R.string.pause else R.string.play)
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.close_player))
                }
            }
            var dragging by remember { mutableStateOf<Float?>(null) }
            val max = state.durationMs.coerceAtLeast(1).toFloat()
            Slider(
                value = (dragging ?: state.positionMs.toFloat()).coerceIn(0f, max),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { onSeek(it.toInt()) }
                    dragging = null
                },
                valueRange = 0f..max,
                enabled = state.durationMs > 0,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsSheet(
    item: Ringtone,
    onDismiss: () -> Unit,
    onApply: (RingtoneKind) -> Unit,
    onContact: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = 28.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
            )
            SheetAction(Icons.Rounded.RingVolume, R.string.set_ringtone) { onDismiss(); onApply(RingtoneKind.RINGTONE) }
            SheetAction(Icons.Rounded.Notifications, R.string.set_notification) { onDismiss(); onApply(RingtoneKind.NOTIFICATION) }
            SheetAction(Icons.Rounded.Alarm, R.string.set_alarm) { onDismiss(); onApply(RingtoneKind.ALARM) }
            SheetAction(Icons.Rounded.Person, R.string.set_contact) { onDismiss(); onContact() }
            SheetAction(Icons.Rounded.Share, R.string.share) { onDismiss(); onShare() }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            SheetAction(Icons.Rounded.Delete, R.string.delete, destructive = true) { onDismiss(); onDelete() }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    @StringRes title: Int,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = MaterialTheme.shapes.small
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(title), color = color, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun InfoPane(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}
