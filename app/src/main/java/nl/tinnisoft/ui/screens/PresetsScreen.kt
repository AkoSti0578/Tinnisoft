package nl.tinnisoft.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.tinnisoft.R
import nl.tinnisoft.data.db.PresetEntity
import nl.tinnisoft.data.db.mix
import nl.tinnisoft.data.model.SourceKind
import nl.tinnisoft.ui.PresetsViewModel
import nl.tinnisoft.ui.components.TextInputDialog

@Composable
fun PresetsScreen(vm: PresetsViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<PresetEntity?>(null) }
    var deleting by remember { mutableStateOf<PresetEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.presets_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(st.presets, key = { _, p -> p.id }) { i, p ->
            val active = p.id == st.activePresetId
            Card(
                Modifier.fillMaxWidth().clickable { vm.select(p.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(describe(p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.move(p.id, -1) }, enabled = i > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.move_up))
                    }
                    IconButton(onClick = { vm.move(p.id, 1) }, enabled = i < st.presets.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.move_down))
                    }
                    IconButton(onClick = { renaming = p }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
                    IconButton(onClick = { deleting = p }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                }
            }
        }
        item {
            OutlinedButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_current_as_preset))
            }
        }
    }

    renaming?.let { p ->
        TextInputDialog(
            title = stringResource(R.string.rename),
            initial = p.name,
            onDismiss = { renaming = null },
            onConfirm = {
                vm.rename(p.id, it)
                renaming = null
            },
        )
    }
    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.save_as_preset),
            label = stringResource(R.string.preset_name),
            onDismiss = { creating = false },
            onConfirm = {
                vm.saveCurrent(it)
                creating = false
            },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_preset_q, p.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(p.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun describe(p: PresetEntity): String = p.mix().layers.map { l ->
    when (l.kind) {
        SourceKind.NOISE -> stringResource(l.color.label())
        SourceKind.NOTCHED -> stringResource(R.string.kind_notched) + " (" + stringResource(l.color.label()) + ")"
        SourceKind.NARROWBAND -> stringResource(R.string.kind_narrowband)
        SourceKind.NATURE -> stringResource(l.nature.label())
    }
}.joinToString(" + ").ifEmpty { "–" }
