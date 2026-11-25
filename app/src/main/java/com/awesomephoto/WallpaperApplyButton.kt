package com.awesomephoto

import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.wallpaper.PhotoWallpaperService
import com.awesomephoto.wallpaper.SlideshowConfig
import com.awesomephoto.wallpaper.WallpaperStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WallpaperApplyButton(photos: List<PhotoCandidate>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { WallpaperStore(context.applicationContext) }
    val component = remember { ComponentName(context, PhotoWallpaperService::class.java) }
    var selection by remember { mutableStateOf<List<PhotoCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var wasActive by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val active = WallpaperManager.getInstance(context).wallpaperInfo?.component == component
        scope.launch {
            try {
                // Some system pickers omit RESULT_OK; a newly active component also proves confirmation.
                val confirmed = result.resultCode == Activity.RESULT_OK || (!wasActive && active)
                withContext(Dispatchers.IO) { if (confirmed) store.confirm() else store.cancel() }
                message = if (confirmed) context.getString(R.string.wallpaper_applied) else context.getString(R.string.wallpaper_cancelled)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.wallpaper_config_failed, error.localizedMessage.orEmpty())
            } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { selection = photos.toList(); message = null }, enabled = photos.isNotEmpty() && !busy) {
            Text(if (busy) stringResource(R.string.wallpaper_applying) else stringResource(R.string.wallpaper_apply))
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    selection?.let { snapshot ->
        WallpaperConfigurationDialog(
            photos = snapshot,
            initial = store.read(false)?.config ?: SlideshowConfig(),
            busy = busy,
            onDismiss = { if (!busy) selection = null },
            onApply = { selected, config ->
                val manager = WallpaperManager.getInstance(context)
                if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER) ||
                    !manager.isWallpaperSupported || !manager.isSetWallpaperAllowed) {
                    message = context.getString(R.string.wallpaper_unsupported)
                    selection = null
                } else {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.prepare(selected, config) }
                            wasActive = manager.wallpaperInfo?.component == component
                            launcher.launch(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
                            selection = null
                        } catch (error: Exception) {
                            withContext(Dispatchers.IO) { store.cancel() }
                            busy = false
                            if (error is CancellationException) throw error
                            message = context.getString(R.string.wallpaper_apply_failed, error.localizedMessage.orEmpty())
                            selection = null
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun WallpaperConfigurationDialog(
    photos: List<PhotoCandidate>,
    initial: SlideshowConfig,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApply: (List<PhotoCandidate>, SlideshowConfig) -> Unit,
) {
    var selected by remember { mutableStateOf(photos.map { it.uri }.toSet()) }
    var config by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallpaper_slideshow)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(stringResource(R.string.wallpaper_selection, selected.size, photos.size))
                    Text(stringResource(R.string.wallpaper_interval), style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Column {
                        SlideshowConfig.INTERVALS.chunked(4).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                group.forEach { minutes ->
                                    FilterChip(selected = config.intervalMinutes == minutes, enabled = !busy,
                                        onClick = { config = config.copy(intervalMinutes = minutes) },
                                        label = { Text(when { minutes == 1440 -> stringResource(R.string.wallpaper_daily); minutes >= 60 -> stringResource(R.string.wallpaper_hours, minutes / 60); else -> stringResource(R.string.wallpaper_minutes, minutes) }) })
                                }
                            }
                        }
                    }
                }
                item {
                    Text(stringResource(R.string.wallpaper_order), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = config.shuffle, enabled = !busy, onClick = { config = config.copy(shuffle = true) }, label = { Text(stringResource(R.string.wallpaper_shuffle)) })
                        FilterChip(selected = !config.shuffle, enabled = !busy, onClick = { config = config.copy(shuffle = false) }, label = { Text(stringResource(R.string.wallpaper_rank_order)) })
                    }
                    Text(stringResource(R.string.wallpaper_fit_mode), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = config.fillScreen, enabled = !busy, onClick = { config = config.copy(fillScreen = true) }, label = { Text(stringResource(R.string.wallpaper_fill)) })
                        FilterChip(selected = !config.fillScreen, enabled = !busy, onClick = { config = config.copy(fillScreen = false) }, label = { Text(stringResource(R.string.wallpaper_fit)) })
                    }
                    Text(if (config.fillScreen) stringResource(R.string.wallpaper_fill_hint) else stringResource(R.string.wallpaper_fit_hint), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.wallpaper_behavior), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.wallpaper_storage), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !busy, onClick = { selected = photos.map { it.uri }.toSet() }) { Text(stringResource(R.string.select_all)) }
                        TextButton(enabled = !busy, onClick = { selected = emptySet() }) { Text(stringResource(R.string.clear_selection)) }
                    }
                }
                items(photos, key = { it.uri.toString() }) { photo ->
                    val checked = photo.uri in selected
                    fun toggle() { selected = if (checked) selected - photo.uri else selected + photo.uri }
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { toggle() }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = checked, enabled = !busy, onCheckedChange = { toggle() })
                        AsyncImage(model = photo.uri, contentDescription = null, modifier = Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(photo.displayName, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.model_score, photo.score), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.wallpaper_preparing)) }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty() && !busy,
                onClick = { onApply(photos.filter { it.uri in selected }, config) }) { Text(stringResource(R.string.apply_preview, selected.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
