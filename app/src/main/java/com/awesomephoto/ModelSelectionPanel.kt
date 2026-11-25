package com.awesomephoto

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelSelectionPanel(state: ScanUiState, onSelect: (String) -> Unit) {
    val resources = LocalContext.current.resources
    var expanded by remember { mutableStateOf(false) }
    val selected = state.models.firstOrNull { it.id == state.settings.aestheticModelId }
    val busy = state.isScanning || state.preparingModels
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.models_heading), style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy) {
                Text(stringResource(R.string.model_aesthetic_selection, selected?.displayName(resources) ?: stringResource(R.string.model_not_selected)))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.models.filter { it.role == "aesthetic" }.forEach { model ->
                    DropdownMenuItem(text = {
                        Text(stringResource(R.string.model_option, model.displayName(resources), model.sizeMiB,
                            stringResource(if (model.id in state.downloadedModels) R.string.model_downloaded else R.string.model_not_downloaded)))
                    }, onClick = { expanded = false; onSelect(model.id) })
                }
            }
        }
        if (selected?.id == "topiq-res50") {
            Text(stringResource(R.string.model_topiq_notice), style = MaterialTheme.typography.bodySmall)
        }
        val segmentation = state.models.firstOrNull { it.role == "segmentation" }
        segmentation?.let {
            Text(stringResource(R.string.model_segmentation, it.displayName(resources), it.sizeMiB,
                stringResource(if (it.id in state.downloadedModels) R.string.model_downloaded else R.string.model_not_downloaded)), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.model_download_note),
            style = MaterialTheme.typography.bodySmall)
        if (state.preparingModels) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (state.preparingModels || state.modelsReady) {
            val progress = state.modelProgress
            val message = when {
                progress != null -> stringResource(R.string.model_progress, progress.model.displayName(resources), progress.percent)
                state.preparingModels -> stringResource(R.string.checking_models)
                else -> stringResource(R.string.models_ready)
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
