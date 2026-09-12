package com.awesomephoto

import android.os.Bundle
import android.Manifest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.model.PhotoKind
import com.awesomephoto.model.Orientation
import com.awesomephoto.model.ScanSettings
import com.awesomephoto.model.ScoreBand
import com.awesomephoto.model.ScanStage

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { BatchScanScreen(viewModel) } } }
    }
}

@Composable
private fun BatchScanScreen(viewModel: ScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var activeKind by remember { mutableStateOf(PhotoKind.ALL) }
    val calendar = remember { Calendar.getInstance() }
    var startDateMs by remember { mutableStateOf(startOfDay(calendar.timeInMillis - 6 * 24 * 60 * 60 * 1000L)) }
    var endDateMs by remember { mutableStateOf(endOfDay(calendar.timeInMillis)) }
    val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.setPhotoAccess(granted) }
    var scoreBand by remember { mutableStateOf(ScoreBand.ABOVE_95) }
    var orientation by remember { mutableStateOf(Orientation.ALL) }
    var previewCandidate by remember { mutableStateOf<PhotoCandidate?>(null) }
    val visible = state.candidates.filter {
            (activeKind == PhotoKind.ALL || it.kind == activeKind) &&
            (scoreBand == ScoreBand.ALL || it.scoreBand == scoreBand) &&
            (orientation == Orientation.ALL || it.orientation == orientation) &&
            it.dateMs in startDateMs..endDateMs
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.export(uri, visible)
    }

    // One vertical scroll surface: controls and result cards share the same grid.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painter = painterResource(R.drawable.wallpaper_finder_logo), contentDescription = "壁纸照片分析器", modifier = Modifier.size(40.dp))
            Text("批量壁纸筛选", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
        Text("照片仅在本机按类型、尺寸、语义和构图处理，不上传。横图进入桌面候选，竖图进入 App 候选。", style = MaterialTheme.typography.bodySmall)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Button(onClick = { photoPermission.launch(Manifest.permission.READ_MEDIA_IMAGES) }, enabled = !state.isScanning && !state.hasPhotoAccess) { Text("允许访问系统相册") }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingFields(state.settings, enabled = !state.isScanning, onChange = viewModel::updateSettings)
            DateRangeFilter(
                startDateMs = startDateMs,
                endDateMs = endDateMs,
                hasFolder = state.hasPhotoAccess,
                dailyCounts = state.dailyPhotoCounts,
                isIndexing = state.isDateIndexing,
                enabled = !state.isScanning,
                onRangeConfirmed = { start, end ->
                    startDateMs = start
                    endDateMs = end
                    viewModel.setDateRange(start, end)
                },
                onMonthVisible = viewModel::loadCalendarMonth,
            )
        }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Button(onClick = { viewModel.scan(startDateMs, endDateMs) }, enabled = !state.isScanning && state.hasPhotoAccess) { Text("开始分析") }
        }
        state.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.isScanning) item(span = { GridItemSpan(maxLineSpan) }) { Progress(state.progress) }
        if (!state.isScanning && state.candidates.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("已完成 ${state.candidates.size} 张评分；当前显示 ${visible.size} 张", fontWeight = FontWeight.SemiBold)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterGroup(ScoreBand.entries, scoreBand, { it.label }, { band -> if (band == ScoreBand.ALL) state.candidates.size else state.candidates.count { it.scoreBand == band } }) { scoreBand = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterGroup(PhotoKind.entries, activeKind, { it.label }, { kind -> viewModel.count(kind) }) { activeKind = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterGroup(Orientation.entries, orientation, { it.label }, { value -> if (value == Orientation.ALL) state.candidates.size else state.candidates.count { it.orientation == value } }) { orientation = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = { exportPicker.launch(null) }, enabled = visible.isNotEmpty()) { Text("导出当前筛选结果 (${visible.size})") }
            }
            items(visible, key = { it.uri.toString() }) { candidate -> CandidateCard(candidate) { previewCandidate = candidate } }
        } else if (!state.isScanning && state.lastScan != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                val summary = requireNotNull(state.lastScan)
                Text(
                    "本次已完成：日期范围内 ${summary.dateMatchedCount} 张，尺寸合格 ${summary.sizeEligibleCount} 张，成功评分 ${summary.scoredCount} 张。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else if (!state.isScanning && state.hasPhotoAccess && state.error == null) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("选择日期范围后开始分析；结果会直接在这里显示。", style = MaterialTheme.typography.bodyMedium) }
        }
    }
    previewCandidate?.let { candidate -> PhotoPreviewDialog(candidate) { previewCandidate = null } }
}

@Composable
private fun SettingFields(settings: ScanSettings, enabled: Boolean, onChange: (ScanSettings) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("最多扫描", settings.scanLimit, enabled) { onChange(settings.copy(scanLimit = it)) }
    }
}

@Composable
private fun DateRangeFilter(
    startDateMs: Long,
    endDateMs: Long,
    hasFolder: Boolean,
    dailyCounts: List<com.awesomephoto.model.PhotoDateIndex.DayCount>,
    isIndexing: Boolean,
    enabled: Boolean,
    onRangeConfirmed: (Long, Long) -> Unit,
    onMonthVisible: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    var showCalendar by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = true,
            onClick = { showCalendar = true },
            enabled = enabled && hasFolder,
            label = { Text("日期 ${formatter.format(startDateMs)}–${formatter.format(endDateMs)}") },
        )
    }
    if (showCalendar) CalendarRangeDialog(
        startDateMs = startDateMs,
        endDateMs = endDateMs,
        dailyCounts = dailyCounts,
        isIndexing = isIndexing,
        onDismiss = { showCalendar = false },
        onConfirm = { start, end -> onRangeConfirmed(start, end); showCalendar = false },
        onMonthVisible = onMonthVisible,
    )
}

private data class CalendarMonth(val year: Int, val month: Int)

@Composable
private fun CalendarRangeDialog(
    startDateMs: Long,
    endDateMs: Long,
    dailyCounts: List<com.awesomephoto.model.PhotoDateIndex.DayCount>,
    isIndexing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Long, Long) -> Unit,
    onMonthVisible: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val formatter = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    val initialCalendar = remember(startDateMs) { Calendar.getInstance().apply { timeInMillis = startDateMs } }
    var shownMonth by remember { mutableStateOf(CalendarMonth(initialCalendar.get(Calendar.YEAR), initialCalendar.get(Calendar.MONTH))) }
    var selectedStart by remember { mutableStateOf(startDateMs) }
    var selectedEnd by remember { mutableStateOf(endDateMs) }
    var awaitingEnd by remember { mutableStateOf(false) }
    val countByDay = remember(dailyCounts) { dailyCounts.associate { it.dayStartMs to it.count } }
    LaunchedEffect(shownMonth) { onMonthVisible(shownMonth.year, shownMonth.month) }
    val monthCalendar = remember(shownMonth) {
        Calendar.getInstance().apply { clear(); set(shownMonth.year, shownMonth.month, 1, 0, 0, 0) }
    }
    val offset = (monthCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val days = monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        val previous = Calendar.getInstance().apply { clear(); set(shownMonth.year, shownMonth.month - 1, 1) }
                        shownMonth = CalendarMonth(previous.get(Calendar.YEAR), previous.get(Calendar.MONTH))
                    }) { Text("‹") }
                    Text("${shownMonth.year}年${shownMonth.month + 1}月", fontWeight = FontWeight.Bold, modifier = Modifier.width(176.dp))
                    if (isIndexing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    TextButton(onClick = {
                        val next = Calendar.getInstance().apply { clear(); set(shownMonth.year, shownMonth.month + 1, 1) }
                        shownMonth = CalendarMonth(next.get(Calendar.YEAR), next.get(Calendar.MONTH))
                    }) { Text("›") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { weekday ->
                        Text(weekday, modifier = Modifier.width(42.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                (0 until 6).forEach { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (0 until 7).forEach { dayOfWeek ->
                            val index = week * 7 + dayOfWeek
                            val day = index - offset + 1
                            if (day !in 1..days) {
                                Box(Modifier.width(42.dp).height(50.dp))
                            } else {
                                val dayMs = Calendar.getInstance().apply { clear(); set(shownMonth.year, shownMonth.month, day, 0, 0, 0) }.timeInMillis
                                val inRange = dayMs in selectedStart..selectedEnd
                                Surface(
                                    color = if (inRange) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.width(42.dp).height(50.dp).clickable {
                                        if (!awaitingEnd) {
                                            selectedStart = dayMs
                                            selectedEnd = dayMs
                                            awaitingEnd = true
                                        } else {
                                            val firstSelectedDay = selectedStart
                                            selectedStart = minOf(firstSelectedDay, dayMs)
                                            selectedEnd = maxOf(firstSelectedDay, dayMs)
                                            awaitingEnd = false
                                        }
                                    },
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        Text(day.toString(), style = MaterialTheme.typography.labelMedium)
                                        Text(if (isIndexing) "·" else (countByDay[dayMs] ?: 0).toString(), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                Text("${formatter.format(selectedStart)} — ${formatter.format(selectedEnd)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { onConfirm(selectedStart, selectedEnd) }) { Text("确定") }
                }
            }
        }
    }
}

private fun startOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timeMs; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timeMs; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

@Composable
private fun <T> FilterGroup(values: Iterable<T>, selected: T, label: (T) -> String, count: (T) -> Int, select: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        lazyRowItems(values.toList()) { value -> FilterChip(selected = selected == value, onClick = { select(value) }, label = { Text("${label(value)} (${count(value)})") }) }
    }
}

@Composable
private fun NumberField(label: String, value: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { input -> text = input.filter(Char::isDigit); text.toIntOrNull()?.let(onValue) }, enabled = enabled, label = { Text(label) }, modifier = Modifier.size(width = 150.dp, height = 64.dp), singleLine = true)
}

@Composable
private fun Progress(progress: com.awesomephoto.model.ScanProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when (progress.stage) {
                ScanStage.DISCOVERING -> "正在发现图片 ${progress.done} 张"
                ScanStage.CHECKING_SIZE -> "正在检查尺寸 ${progress.done} / ${progress.total}"
                ScanStage.ANALYSING -> "正在分析 ${progress.done} / ${progress.total}"
                ScanStage.PREPARING -> "正在准备图片…"
            }
        )
        LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
        if (progress.currentName.isNotBlank()) Text(progress.currentName, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CandidateCard(candidate: PhotoCandidate, onPreview: () -> Unit) {
    Card(modifier = Modifier.clickable(onClick = onPreview)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AsyncImage(model = candidate.uri, contentDescription = candidate.displayName, modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            Text("${candidate.score} 分 · ${candidate.target.label}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(candidate.semanticLabels.joinToString(" · ").ifBlank { candidate.kind.label }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun PhotoPreviewDialog(candidate: PhotoCandidate, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(
                    model = candidate.uri,
                    contentDescription = candidate.displayName,
                    modifier = Modifier.fillMaxWidth().height(560.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${candidate.score} 分 · ${candidate.displayName}", modifier = Modifier.width(250.dp), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
