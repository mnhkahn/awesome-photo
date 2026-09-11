package com.awesomephoto

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.awesomephoto.model.PhotoCandidate
import com.awesomephoto.model.PhotoKind
import com.awesomephoto.model.Orientation
import com.awesomephoto.model.ScanSettings
import com.awesomephoto.model.ScoreBand

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
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.setFolder(uri)
    }
    var scoreBand by remember { mutableStateOf(ScoreBand.ABOVE_95) }
    var orientation by remember { mutableStateOf(Orientation.ALL) }
    val visible = state.candidates.filter {
        (activeKind == PhotoKind.ALL || it.kind == activeKind) &&
            (scoreBand == ScoreBand.ALL || it.scoreBand == scoreBand) &&
            (orientation == Orientation.ALL || it.orientation == orientation)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.export(uri, visible)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painter = painterResource(R.drawable.wallpaper_finder_logo), contentDescription = "壁纸照片分析器", modifier = Modifier.size(48.dp))
            Text("批量壁纸筛选", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("照片仅在本机按类型、尺寸、语义和构图处理，不上传。横图进入桌面候选，竖图进入 App 候选。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { folderPicker.launch(null) }, enabled = !state.isScanning) { Text("选择照片目录") }
            Text(state.folder?.lastPathSegment ?: "尚未选择")
        }
        SettingFields(state.settings, enabled = !state.isScanning, onChange = viewModel::updateSettings)
        Button(onClick = viewModel::scan, enabled = !state.isScanning && state.folder != null) { Text("开始分析") }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.isScanning) Progress(state.progress.done, state.progress.total, state.progress.currentName)
        if (!state.isScanning && state.candidates.isNotEmpty()) {
            Text("已完成 ${state.candidates.size} 张评分；当前显示 ${visible.size} 张", fontWeight = FontWeight.SemiBold)
            Text("评分段", style = MaterialTheme.typography.labelMedium)
            FilterGroup(ScoreBand.entries, scoreBand, { it.label }, { band -> if (band == ScoreBand.ALL) state.candidates.size else state.candidates.count { it.scoreBand == band } }) { scoreBand = it }
            Text("图片类别", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PhotoKind.entries.forEach { kind ->
                    FilterChip(selected = activeKind == kind, onClick = { activeKind = kind }, label = { Text("${kind.label} (${viewModel.count(kind)})") })
                }
            }
            Text("画面方向", style = MaterialTheme.typography.labelMedium)
            FilterGroup(Orientation.entries, orientation, { it.label }, { value -> if (value == Orientation.ALL) state.candidates.size else state.candidates.count { it.orientation == value } }) { orientation = it }
            Button(onClick = { exportPicker.launch(null) }, enabled = visible.isNotEmpty()) { Text("导出当前筛选结果 (${visible.size})") }
            CandidateGrid(visible)
        } else if (!state.isScanning && state.folder != null && state.error == null) {
            Text("选择目录后开始分析；结果会直接在这里显示。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingFields(settings: ScanSettings, enabled: Boolean, onChange: (ScanSettings) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("最多扫描", settings.scanLimit, enabled) { onChange(settings.copy(scanLimit = it)) }
    }
}

@Composable
private fun <T> FilterGroup(values: Iterable<T>, selected: T, label: (T) -> String, count: (T) -> Int, select: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value, onClick = { select(value) }, label = { Text("${label(value)} (${count(value)})") }) }
    }
}

@Composable
private fun NumberField(label: String, value: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = text, onValueChange = { input -> text = input.filter(Char::isDigit); text.toIntOrNull()?.let(onValue) }, enabled = enabled, label = { Text(label) }, modifier = Modifier.size(width = 150.dp, height = 64.dp), singleLine = true)
}

@Composable
private fun Progress(done: Int, total: Int, name: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (total == 0) "正在准备图片…" else "正在分析 $done / $total")
        LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CandidateGrid(candidates: List<PhotoCandidate>) {
    LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(candidates, key = { it.uri.toString() }) { candidate ->
            Card {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AsyncImage(model = candidate.uri, contentDescription = candidate.displayName, modifier = Modifier.fillMaxWidth().size(height = 96.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Text("${candidate.score} 分 · ${candidate.target.label}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(candidate.semanticLabels.joinToString(" · ").ifBlank { candidate.kind.label }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
