package nl.tinnisoft.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.tinnisoft.R
import nl.tinnisoft.data.model.FrequencyRange
import nl.tinnisoft.ui.MatcherViewModel
import nl.tinnisoft.ui.components.FrequencySlider
import nl.tinnisoft.ui.components.LabeledSlider
import nl.tinnisoft.ui.components.SectionCard
import nl.tinnisoft.ui.components.SwitchRow
import nl.tinnisoft.ui.components.formatHz
import nl.tinnisoft.ui.components.percent
import kotlin.math.pow

/** Wizard to find the pitch of one's own tinnitus. */
@Composable
fun MatcherScreen(onDone: () -> Unit, vm: MatcherViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(progress = { (ui.step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
        when (ui.step) {
            0 -> IntroStep(ui.narrowBand, vm::setNarrowBand) { vm.setStep(1) }
            1 -> SearchStep(ui, vm)
            2 -> OctaveStep(ui, vm)
            else -> ResultStep(ui, vm, onDone)
        }
    }
}

@Composable
private fun IntroStep(narrowBand: Boolean, onType: (Boolean) -> Unit, next: () -> Unit) {
    Text(stringResource(R.string.matcher_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.matcher_intro))
    SectionCard(stringResource(R.string.matcher_tone_type)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !narrowBand, onClick = { onType(false) }, label = { Text(stringResource(R.string.tone_sine)) })
            FilterChip(selected = narrowBand, onClick = { onType(true) }, label = { Text(stringResource(R.string.tone_narrowband)) })
        }
        Text(stringResource(R.string.matcher_tone_type_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.next)) }
}

@Composable
private fun SearchStep(ui: MatcherViewModel.UiState, vm: MatcherViewModel) {
    Text(stringResource(R.string.matcher_search_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.matcher_search_text))
    ToneControls(ui, vm)
    SectionCard(stringResource(R.string.frequency)) {
        Text(formatHz(ui.frequency), style = MaterialTheme.typography.displaySmall)
        FrequencySlider(stringResource(R.string.coarse), ui.frequency, vm::setFrequency)
        Text(stringResource(R.string.fine_tune), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            // semitone (1/12 octave) and ~1 % steps
            listOf(-1f / 12f to "−½", -1f / 72f to "−", 1f / 72f to "+", 1f / 12f to "+½").forEach { (oct, label) ->
                FilledTonalButton(onClick = { vm.setFrequency(ui.frequency * 2f.pow(oct)) }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
        Text(stringResource(R.string.fine_tune_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.setStep(0) }) { Text(stringResource(R.string.back)) }
        Button(onClick = { vm.setStep(2) }) { Text(stringResource(R.string.next)) }
    }
}

@Composable
private fun ToneControls(ui: MatcherViewModel.UiState, vm: MatcherViewModel) {
    SectionCard {
        SwitchRow(stringResource(R.string.play_tone), ui.playing, { if (it) vm.play() else vm.stop() }, description = stringResource(R.string.tone_starts_soft))
        LabeledSlider(stringResource(R.string.tone_volume), ui.level, vm::setLevel, percent(ui.level))
    }
}

@Composable
private fun OctaveStep(ui: MatcherViewModel.UiState, vm: MatcherViewModel) {
    var picked by remember { mutableStateOf(1) }
    val candidates = listOf(ui.frequency / 2f, ui.frequency, ui.frequency * 2f)
    Text(stringResource(R.string.octave_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.octave_text))
    ToneControls(ui, vm)
    candidates.forEachIndexed { i, f ->
        val valid = f in FrequencyRange.MIN_HZ..FrequencyRange.MAX_HZ
        val label = stringResource(listOf(R.string.octave_lower, R.string.octave_same, R.string.octave_higher)[i], formatHz(f))
        if (picked == i) {
            Button(onClick = { vm.preview(f) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(label) }
        } else {
            OutlinedButton(onClick = {
                picked = i
                vm.preview(f)
            }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.setStep(1) }) { Text(stringResource(R.string.back)) }
        Button(onClick = {
            vm.setFrequency(candidates[picked])
            vm.setStep(3)
        }) { Text(stringResource(R.string.octave_confirm)) }
    }
}

@Composable
private fun ResultStep(ui: MatcherViewModel.UiState, vm: MatcherViewModel, onDone: () -> Unit) {
    var notch by remember { mutableStateOf(true) }
    var eq by remember { mutableStateOf(false) }
    Text(stringResource(R.string.result_title), style = MaterialTheme.typography.titleLarge)
    Text(formatHz(ui.frequency), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
    Text(stringResource(R.string.result_text))
    SectionCard {
        SwitchRow(stringResource(R.string.apply_notch), notch, { notch = it })
        SwitchRow(stringResource(R.string.apply_eq), eq, { eq = it }, description = stringResource(R.string.apply_eq_hint))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.setStep(1) }) { Text(stringResource(R.string.back)) }
        Button(onClick = {
            vm.save(notch, eq)
            onDone()
        }) { Text(stringResource(R.string.save)) }
    }
    Spacer(Modifier.height(24.dp))
}
