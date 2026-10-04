package com.awesomephoto

import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.Manifest
import android.location.Geocoder
import android.media.ExifInterface
import android.widget.Toast
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.model.PhotoKind
import com.awesomephoto.model.Orientation
import com.awesomephoto.model.ScanSettings
import com.awesomephoto.model.ScoreBand
import com.awesomephoto.model.ScanStage

class MainActivity : ComponentActivity() {
    private val viewModel: ScanViewModel by viewModels()
    private val photoPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPhotoAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPhotoAccess()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { BatchScanScreen(viewModel) } } }
        // Ask once when opening the app; rotation and permission callbacks must not ask again.
        if (savedInstanceState == null && !hasPhotoAccess()) {
            photoPermissions.launch(
                when {
                    Build.VERSION.SDK_INT >= 34 -> arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    )
                    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in system settings while the app is in the background.
        refreshPhotoAccess()
    }

    private fun hasPhotoAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= 34 ->
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= 33 ->
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        else -> checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPhotoAccess() {
        val granted = hasPhotoAccess()
        if (viewModel.state.value.hasPhotoAccess != granted) viewModel.setPhotoAccess(granted)
    }
}

@Composable
private fun BatchScanScreen(viewModel: ScanViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var activeKind by remember { mutableStateOf(PhotoKind.ALL) }
    val calendar = remember { Calendar.getInstance() }
    var startDateMs by remember { mutableStateOf(startOfDay(calendar.timeInMillis - 6 * 24 * 60 * 60 * 1000L)) }
    var endDateMs by remember { mutableStateOf(endOfDay(calendar.timeInMillis)) }
    var scoreBand by remember { mutableStateOf(ScoreBand.ALL) }
    var orientation by remember { mutableStateOf(Orientation.ALL) }
    var previewCandidate by remember { mutableStateOf<PhotoCandidate?>(null) }
    val context = LocalContext.current
    fun matchesFilters(
        photo: PhotoCandidate,
        kind: PhotoKind = activeKind,
        band: ScoreBand = scoreBand,
        direction: Orientation = orientation,
    ): Boolean = photo.dateMs in startDateMs..endDateMs &&
        (kind == PhotoKind.ALL || photo.kind == kind) &&
        (band == ScoreBand.ALL || photo.scoreBand == band) &&
        (direction == Orientation.ALL || photo.orientation == direction)

    val visible = state.candidates.filter { matchesFilters(it) }
    // Each option replaces only its own group's selection, keeping the other filters.
    val scoreCounts = ScoreBand.entries.associateWith { band -> state.candidates.count { matchesFilters(it, band = band) } }
    val kindCounts = PhotoKind.entries.associateWith { kind -> state.candidates.count { matchesFilters(it, kind = kind) } }
    val orientationCounts = Orientation.entries.associateWith { direction -> state.candidates.count { matchesFilters(it, direction = direction) } }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.export(uri, visible)
    }

    // One vertical scroll surface: controls and result cards share the same grid.
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        // enableEdgeToEdge draws behind system bars; consume their safe area here
        // so a status-bar icon or display cutout cannot cover the first controls.
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
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
        Text("照片仅在本机评价美感、主体明确度和清晰度，不上传。横图进入桌面候选，竖图进入 App 候选。", style = MaterialTheme.typography.bodySmall)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            ModelSelectionPanel(state, viewModel::selectModel, viewModel::downloadModels)
        }
        if (!state.hasPhotoAccess) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("需要相册访问权限才能分析照片；若未授权，可在系统设置中开启本应用的照片访问权限。", style = MaterialTheme.typography.bodySmall)
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
            Button(onClick = { viewModel.scan(startDateMs, endDateMs) }, enabled = !state.isScanning && !state.preparingModels && state.modelsReady && state.hasPhotoAccess) { Text("开始分析") }
        }
        state.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.isScanning) item(span = { GridItemSpan(maxLineSpan) }) { Progress(state.progress) }
        if (!state.isScanning && state.candidates.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("已完成 ${state.candidates.size} 张壁纸评分；当前显示 ${visible.size} 张", fontWeight = FontWeight.SemiBold)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterGroup(ScoreBand.entries, scoreBand, ScoreBand.ALL, { it.label }, { scoreCounts.getValue(it) }) { scoreBand = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterGroup(PhotoKind.entries, activeKind, PhotoKind.ALL, { it.label }, { kindCounts.getValue(it) }) { activeKind = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                FilterGroup(Orientation.entries, orientation, Orientation.ALL, { it.label }, { orientationCounts.getValue(it) }) { orientation = it }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = { exportPicker.launch(null) }, enabled = visible.isNotEmpty()) { Text("导出当前筛选结果 (${visible.size})") }
            }
            if (visible.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("当前条件下没有照片，请选择“全部”放宽筛选条件，或调整日期范围。", style = MaterialTheme.typography.bodyMedium)
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
    AppUpdatePanel()
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
private fun <T> FilterGroup(values: Iterable<T>, selected: T, allValue: T, label: (T) -> String, count: (T) -> Int, select: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        lazyRowItems(values.toList()) { value ->
            val optionCount = count(value)
            FilterChip(
                selected = selected == value,
                onClick = { select(value) },
                // Keep a way to clear filters even when a new scan/date range has no matches.
                enabled = value == allValue || optionCount > 0,
                label = { Text("${label(value)} ($optionCount)") },
            )
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CandidateCard(candidate: PhotoCandidate, onPreview: () -> Unit) {
    val context = LocalContext.current
    var showActions by remember(candidate.uri) { mutableStateOf(false) }
    Box {
        Card(modifier = Modifier.combinedClickable(
            onClick = onPreview,
            onClickLabel = "预览照片",
            onLongClick = { showActions = true },
            onLongClickLabel = "分享或用其他应用打开",
        )) {
            Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AsyncImage(model = candidate.uri, contentDescription = candidate.displayName, modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Text("模型参考分 ${candidate.score} · ${candidate.target.label}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(candidate.semanticLabels.joinToString(" · ").ifBlank { candidate.kind.label }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        DropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
            DropdownMenuItem(text = { Text("分享") }, onClick = {
                showActions = false
                launchPhotoAction(context, candidate, Intent.ACTION_SEND)
            })
            DropdownMenuItem(text = { Text("用其他应用打开") }, onClick = {
                showActions = false
                launchPhotoAction(context, candidate, Intent.ACTION_VIEW)
            })
        }
    }
}

private fun launchPhotoAction(context: android.content.Context, candidate: PhotoCandidate, action: String) {
    try {
        val mimeType = context.contentResolver.getType(candidate.uri) ?: "image/*"
        val intent = Intent(action).apply {
            if (action == Intent.ACTION_SEND) {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, candidate.uri)
                putExtra(Intent.EXTRA_TITLE, candidate.displayName)
            } else {
                setDataAndType(candidate.uri, mimeType)
            }
            // Grant temporary access to this photo, including when access is limited to selected photos.
            clipData = ClipData.newRawUri(candidate.displayName, candidate.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, if (action == Intent.ACTION_SEND) "分享照片" else "用其他应用打开"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可处理此照片的应用", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, "无法访问此照片，请检查相册访问权限", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun PhotoPreviewDialog(candidate: PhotoCandidate, onDismiss: () -> Unit) {
    var details by remember(candidate.uri) { mutableStateOf<PhotoDetails?>(null) }
    val context = LocalContext.current
    var hasLocationAccess by remember { mutableStateOf(hasPhotoLocationAccess(context)) }
    var reload by remember { mutableStateOf(0) }
    var permissionDenied by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasLocationAccess = hasPhotoLocationAccess(context)
        permissionDenied = !granted
        reload++
    }
    LifecycleResumeEffect(Unit) {
        hasLocationAccess = hasPhotoLocationAccess(context)
        reload++
        onPauseOrDispose { }
    }
    LaunchedEffect(candidate.uri, hasLocationAccess, reload) {
        details = null
        details = loadPhotoDetails(context, candidate.uri)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text("×", modifier = Modifier.size(32.dp).clickable(onClick = onDismiss), style = MaterialTheme.typography.headlineSmall)
                }
                AsyncImage(
                    model = candidate.uri,
                    contentDescription = candidate.displayName,
                    modifier = Modifier.fillMaxWidth().height(560.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
                Text("模型参考分 ${candidate.score} · ${candidate.displayName}", maxLines = 1, style = MaterialTheme.typography.bodySmall)
                ScoreExplanation(candidate)
                details?.takenAt?.let { Text("拍摄于 $it", style = MaterialTheme.typography.bodySmall) }
                Text("地点：${details?.placeName ?: "正在读取…"}", style = MaterialTheme.typography.bodySmall)
                if (!hasLocationAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    TextButton(onClick = {
                        if (permissionDenied) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        } else {
                            locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                        }
                    }) { Text(if (permissionDenied) "前往设置开启照片位置权限" else "允许读取照片位置") }
                }
            }
        }
    }
}

@Composable
private fun ScoreExplanation(candidate: PhotoCandidate) {
    var expanded by remember(candidate.uri) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "收起评分依据" else "查看评分口径与原因")
    }
    if (expanded) {
        Text("模型参考分 ${candidate.score}/100 · 向下取整", fontWeight = FontWeight.SemiBold)
        Text("本地审美模型占 70%，主体区域连贯性占 20%，清晰度占 10%。尚未按你的喜好校准，先作为排序参考，不代表及格率或个人回忆价值；不评价屏幕适配。", style = MaterialTheme.typography.bodySmall)
        if (candidate.scoreDetails.isEmpty()) {
            Text("此记录尚无评分明细，请重新分析照片。")
        }
        candidate.scoreDetails.forEach { detail ->
            Text(String.format(Locale.getDefault(), "%s：%.2f / %.2f 分", detail.name, detail.points, detail.weight), fontWeight = FontWeight.SemiBold)
            Text("口径：${detail.policy}", style = MaterialTheme.typography.bodySmall)
            Text("依据：${detail.reason}", style = MaterialTheme.typography.bodySmall)
        }
        Text("明细显示值经过四舍五入；总分使用未舍入值向下取整。", style = MaterialTheme.typography.bodySmall)
    }
}

private data class PhotoDetails(val takenAt: String?, val placeName: String)

private fun hasPhotoLocationAccess(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

private suspend fun loadPhotoDetails(context: android.content.Context, uri: android.net.Uri): PhotoDetails = withContext(Dispatchers.IO) {
    val canReadLocation = hasPhotoLocationAccess(context)
    // Android 10+ redacts GPS from ordinary MediaStore reads, even with photo access.
    val originalUri = if (canReadLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.setRequireOriginal(uri)
    } else uri
    fun readExif(source: Uri): ExifInterface? = runCatching {
        context.contentResolver.openFileDescriptor(source, "r")?.use { descriptor -> ExifInterface(descriptor.fileDescriptor) }
    }.getOrNull()
    val originalExif = readExif(originalUri)
    // Preserve the capture date if the provider cannot supply the original file.
    val exif = originalExif ?: if (originalUri != uri) readExif(uri) else null
    val failure = if (!canReadLocation) "需要允许读取照片位置信息" else "无法读取原图位置信息，请重试"
    if (exif == null) return@withContext PhotoDetails(null, failure)
    val takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
    val coordinates = FloatArray(2)
    if (!canReadLocation || originalExif == null) return@withContext PhotoDetails(takenAt, failure)
    if (!exif.getLatLong(coordinates)) return@withContext PhotoDetails(takenAt, "照片文件未记录位置信息")
    val coordinateLabel = String.format(Locale.getDefault(), "纬度 %.5f，经度 %.5f", coordinates[0], coordinates[1])
    val placeName = if (Geocoder.isPresent()) runCatching {
        val address = Geocoder(context, Locale.getDefault())
            .getFromLocation(coordinates[0].toDouble(), coordinates[1].toDouble(), 1)
            ?.firstOrNull()
        address?.getAddressLine(0)?.takeIf { it.isNotBlank() } ?: listOfNotNull(address?.countryName, address?.adminArea, address?.locality, address?.subLocality, address?.featureName)
            .distinct().joinToString("·").ifBlank { null }
    }.getOrNull() else null
    PhotoDetails(takenAt, placeName ?: "$coordinateLabel（暂未解析出地址）")
}
