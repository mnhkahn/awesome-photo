package com.awesomephoto

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
                message = if (confirmed) "已应用照片轮播壁纸" else "已取消应用，原壁纸设置保留"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "保存壁纸配置失败：${error.message}"
            } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = { selection = photos.toList(); message = null }, enabled = photos.isNotEmpty() && !busy) {
            Text(if (busy) "正在应用壁纸…" else "应用成当前设备壁纸")
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
                    message = "当前设备不支持动态壁纸，或系统限制了更换壁纸"
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
                            message = "应用失败：${error.message}。请检查存储空间和系统壁纸支持。"
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
        title = { Text("照片壁纸轮播") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("从当前筛选结果选择照片，已选 ${selected.size}/${photos.size} 张。模型分数仅供参考。")
                    Text("切换间隔", style = MaterialTheme.typography.titleSmall)
                }
                item {
                    Column {
                        SlideshowConfig.INTERVALS.chunked(4).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                group.forEach { minutes ->
                                    FilterChip(selected = config.intervalMinutes == minutes, enabled = !busy,
                                        onClick = { config = config.copy(intervalMinutes = minutes) },
                                        label = { Text(when { minutes == 1440 -> "每天"; minutes >= 60 -> "${minutes / 60}小时"; else -> "${minutes}分" }) })
                                }
                            }
                        }
                    }
                }
                item {
                    Text("播放顺序", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = config.shuffle, enabled = !busy, onClick = { config = config.copy(shuffle = true) }, label = { Text("随机") })
                        FilterChip(selected = !config.shuffle, enabled = !busy, onClick = { config = config.copy(shuffle = false) }, label = { Text("按结果顺序") })
                    }
                    Text("显示方式", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = config.fillScreen, enabled = !busy, onClick = { config = config.copy(fillScreen = true) }, label = { Text("居中铺满") })
                        FilterChip(selected = !config.fillScreen, enabled = !busy, onClick = { config = config.copy(fillScreen = false) }, label = { Text("完整显示") })
                    }
                    Text(if (config.fillScreen) "铺满屏幕会裁去超出部分，不修改原图。" else "保留整张照片，空白处使用黑色背景。", style = MaterialTheme.typography.bodySmall)
                    Text("仅壁纸可见时运行；熄屏时暂停绘制，返回桌面时按间隔更新。单张照片固定显示。下一步在系统预览页确认，桌面/锁屏选项以系统提供为准。更换其他系统壁纸即可停止轮播。", style = MaterialTheme.typography.bodySmall)
                    Text("选中照片将复制到应用私有空间，退出应用、重启设备后仍可使用；清除应用数据或卸载会移除这些副本。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = !busy, onClick = { selected = photos.map { it.uri }.toSet() }) { Text("全选") }
                        TextButton(enabled = !busy, onClick = { selected = emptySet() }) { Text("清空") }
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
                            Text("模型参考分 ${photo.score}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在准备本机壁纸副本…") }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty() && !busy,
                onClick = { onApply(photos.filter { it.uri in selected }, config) }) { Text("应用并预览 (${selected.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
