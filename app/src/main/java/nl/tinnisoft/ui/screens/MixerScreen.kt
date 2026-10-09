package nl.tinnisoft.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import nl.tinnisoft.data.model.LayerConfig
import nl.tinnisoft.data.model.MixConfig
import nl.tinnisoft.data.model.NatureSound
import nl.tinnisoft.data.model.NoiseColor
import nl.tinnisoft.data.model.SourceKind
import nl.tinnisoft.ui.MixerViewModel
import nl.tinnisoft.ui.components.FrequencySlider
import nl.tinnisoft.ui.components.LabeledSlider
import nl.tinnisoft.ui.components.SectionCard
import nl.tinnisoft.ui.components.SwitchRow
import nl.tinnisoft.ui.components.TextInputDialog
import nl.tinnisoft.ui.components.formatHz
import nl.tinnisoft.ui.components.percent
import kotlin.math.roundToInt

@StringRes
fun NoiseColor.label(): Int = when (this) {
    NoiseColor.WHITE -> R.string.color_white
    NoiseColor.PINK -> R.string.color_pink
    NoiseColor.BROWN -> R.string.color_brown
    NoiseColor.BLUE -> R.string.color_blue
    NoiseColor.VIOLET -> R.string.color_violet
    NoiseColor.GREY -> R.string.color_grey
}

@StringRes
fun NatureSound.label(): Int = when (this) {
    NatureSound.RAIN -> R.string.nature_rain
    NatureSound.WAVES -> R.string.nature_waves
    NatureSound.BROOK -> R.string.nature_brook
    NatureSound.WIND -> R.string.nature_wind
    NatureSound.CRICKETS -> R.string.nature_crickets
    NatureSound.FAN -> R.string.nature_fan
}

@StringRes
fun SourceKind.label(): Int = when (this) {
    SourceKind.NOISE -> R.string.kind_noise
    SourceKind.NOTCHED -> R.string.kind_notched
    SourceKind.NARROWBAND -> R.string.kind_narrowband
    SourceKind.NATURE -> R.string.kind_nature
}

@Composable
fun MixerScreen(vm: MixerViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val tinnitusHz by vm.tinnitusHz.collectAsStateWithLifecycle()
    val mix = st.mix
    var saveDialog by remember { mutableStateOf(false) }

    fun setLayer(i: Int, l: LayerConfig) = vm.update(mix.copy(layers = mix.layers.toMutableList().also { it[i] = l }))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.mixer_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)

        mix.layers.forEachIndexed { i, layer ->
            LayerCard(
                index = i,
                layer = layer,
                tinnitusHz = tinnitusHz,
                onChange = { setLayer(i, it) },
                onRemove = { vm.update(mix.copy(layers = mix.layers.filterIndexed { j, _ -> j != i })) },
            )
        }
        if (mix.layers.size < MixConfig.MAX_LAYERS) {
            OutlinedButton(onClick = {
                val center = if (tinnitusHz > 0) tinnitusHz else 4000f
                vm.update(mix.copy(layers = mix.layers + LayerConfig(volume = 0.6f, centerHz = center)))
            }) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_layer, mix.layers.size, MixConfig.MAX_LAYERS))
            }
        }

        SectionCard(stringResource(R.string.equalizer)) {
            MixConfig.EQ_BANDS_HZ.forEachIndexed { i, hz ->
                val g = mix.eqGainsDb.getOrElse(i) { 0f }
                LabeledSlider(
                    label = formatHz(hz),
                    value = g,
                    range = -12f..12f,
                    onChange = { v ->
                        val snapped = (v * 2).roundToInt() / 2f
                        vm.update(mix.copy(eqGainsDb = mix.eqGainsDb.toMutableList().also { it[i] = snapped }))
                    },
                    valueText = "%+.1f dB".format(g),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.update(mix.copy(eqGainsDb = List(MixConfig.EQ_BANDS_HZ.size) { 0f })) }) {
                    Text(stringResource(R.string.reset))
                }
                if (tinnitusHz > 0) {
                    TextButton(onClick = { vm.eqForTinnitus(tinnitusHz) }) { Text(stringResource(R.string.eq_for_tinnitus)) }
                }
            }
        }

        SectionCard(stringResource(R.string.modulation)) {
            SwitchRow(
                stringResource(R.string.modulation_enable),
                mix.modulationEnabled,
                { vm.update(mix.copy(modulationEnabled = it)) },
                description = stringResource(R.string.modulation_hint),
            )
            LabeledSlider(
                stringResource(R.string.modulation_depth),
                mix.modulationDepth,
                { vm.update(mix.copy(modulationDepth = it)) },
                percent(mix.modulationDepth),
                enabled = mix.modulationEnabled,
            )
            LabeledSlider(
                stringResource(R.string.modulation_period),
                mix.modulationPeriodSec,
                { vm.update(mix.copy(modulationPeriodSec = it)) },
                stringResource(R.string.seconds_short, mix.modulationPeriodSec.roundToInt()),
                range = 2f..60f,
                enabled = mix.modulationEnabled,
            )
        }

        SectionCard(stringResource(R.string.balance)) {
            Text(stringResource(R.string.balance_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            LabeledSlider(
                stringResource(R.string.balance_lr),
                mix.balance,
                { vm.update(mix.copy(balance = if (kotlin.math.abs(it) < 0.04f) 0f else it)) },
                when {
                    mix.balance < 0 -> stringResource(R.string.balance_left, percent(-mix.balance))
                    mix.balance > 0 -> stringResource(R.string.balance_right, percent(mix.balance))
                    else -> stringResource(R.string.balance_center)
                },
                range = -1f..1f,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { saveDialog = true }) {
                Icon(Icons.Filled.Save, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.save_as_preset))
            }
            if (st.modified && st.activePreset != null) {
                OutlinedButton(onClick = vm::overwriteActive) {
                    Text(stringResource(R.string.update_preset, st.activePreset!!.name))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (saveDialog) {
        TextInputDialog(
            title = stringResource(R.string.save_as_preset),
            label = stringResource(R.string.preset_name),
            onDismiss = { saveDialog = false },
            onConfirm = {
                vm.saveAsNew(it)
                saveDialog = false
            },
        )
    }
}

@Composable
private fun LayerCard(
    index: Int,
    layer: LayerConfig,
    tinnitusHz: Float,
    onChange: (LayerConfig) -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.layer_n, index + 1, stringResource(layer.kind.label())),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, stringResource(R.string.remove_layer)) }
        }
        ChipRow(SourceKind.entries, layer.kind, { stringResource(it.label()) }) { k ->
            val center = if (tinnitusHz > 0 && k != layer.kind && (k == SourceKind.NOTCHED || k == SourceKind.NARROWBAND)) tinnitusHz else layer.centerHz
            onChange(layer.copy(kind = k, centerHz = center))
        }
        when (layer.kind) {
            SourceKind.NOISE, SourceKind.NOTCHED -> ChipRow(NoiseColor.entries, layer.color, { stringResource(it.label()) }) {
                onChange(layer.copy(color = it))
            }
            SourceKind.NATURE -> ChipRow(NatureSound.entries, layer.nature, { stringResource(it.label()) }) {
                onChange(layer.copy(nature = it))
            }
            SourceKind.NARROWBAND -> {}
        }
        if (layer.kind == SourceKind.NOTCHED) {
            Text(stringResource(R.string.notched_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FrequencySlider(stringResource(R.string.notch_frequency), layer.centerHz, { onChange(layer.copy(centerHz = it)) })
            LabeledSlider(
                stringResource(R.string.notch_width),
                layer.notchWidthOct,
                { onChange(layer.copy(notchWidthOct = (it * 4).roundToInt() / 4f)) },
                stringResource(R.string.octaves, "%.2f".format(layer.notchWidthOct)),
                range = 0.25f..2f,
            )
        }
        if (layer.kind == SourceKind.NARROWBAND) {
            Text(stringResource(R.string.narrowband_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FrequencySlider(stringResource(R.string.center_frequency), layer.centerHz, { onChange(layer.copy(centerHz = it)) })
            LabeledSlider(
                stringResource(R.string.band_width),
                layer.bandWidthOct,
                { onChange(layer.copy(bandWidthOct = (it * 8).roundToInt() / 8f)) },
                stringResource(R.string.octaves, "%.2f".format(layer.bandWidthOct)),
                range = 0.125f..2f,
            )
        }
        if ((layer.kind == SourceKind.NOTCHED || layer.kind == SourceKind.NARROWBAND) && tinnitusHz > 0 && layer.centerHz != tinnitusHz) {
            TextButton(onClick = { onChange(layer.copy(centerHz = tinnitusHz)) }) {
                Text(stringResource(R.string.use_my_frequency, formatHz(tinnitusHz)))
            }
        }
        LabeledSlider(stringResource(R.string.layer_volume), layer.volume, { onChange(layer.copy(volume = it)) }, percent(layer.volume))
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}
