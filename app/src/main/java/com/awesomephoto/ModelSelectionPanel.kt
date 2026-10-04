package com.awesomephoto

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun ModelSelectionPanel(state: ScanUiState, onSelect: (String) -> Unit, onDownload: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = state.models.firstOrNull { it.id == state.settings.aestheticModelId }
    val busy = state.isScanning || state.preparingModels
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("分析模型", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                Text("审美：${selected?.name ?: "未选择"} ▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.models.filter { it.role == "aesthetic" }.forEach { model ->
                    DropdownMenuItem(text = {
                        Text(String.format(Locale.ROOT, "%s · %.1f MB · %s", model.name, model.sizeMiB,
                            if (model.id in state.downloadedModels) "已下载" else "未下载"))
                    }, onClick = { expanded = false; onSelect(model.id) })
                }
            }
        }
        if (selected?.id == "topiq-res50") {
            Text("TOPIQ 为较大模型；上游采用非商业许可。", style = MaterialTheme.typography.bodySmall)
        }
        val segmentation = state.models.firstOrNull { it.role == "segmentation" }
        segmentation?.let {
            Text(String.format(Locale.ROOT, "物体识别：%s · %.1f MB · %s", it.name, it.sizeMiB,
                if (it.id in state.downloadedModels) "已下载" else "未下载"), style = MaterialTheme.typography.bodySmall)
        }
        Text("记住上次选择。模型仅首次下载，切换不删除已下载模型；下载后可离线分析，照片不上传。",
            style = MaterialTheme.typography.bodySmall)
        if (state.preparingModels) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (!state.modelsReady) {
            Button(onClick = onDownload, enabled = !state.isScanning) { Text("下载所需模型 / 重试") }
        }
        state.modelProgress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
