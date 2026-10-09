package nl.tinnisoft.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.tinnisoft.BuildConfig
import nl.tinnisoft.R
import nl.tinnisoft.data.settings.FocusBehavior
import nl.tinnisoft.ui.SettingsViewModel
import nl.tinnisoft.ui.components.LabeledSlider
import nl.tinnisoft.ui.components.SectionCard
import nl.tinnisoft.ui.components.SwitchRow
import nl.tinnisoft.ui.components.TextInputDialog
import nl.tinnisoft.ui.components.percent
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(onBatteryHelp: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val cpu by vm.cpuLoad.collectAsStateWithLifecycle()
    val locked = s.pinSet && !unlocked
    var askPin by remember { mutableStateOf(false) }
    var setPin by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard(stringResource(R.string.protection)) {
            Text(stringResource(R.string.protection_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.pinSet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(if (locked) R.string.locked else R.string.unlocked),
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    if (locked) TextButton(onClick = { askPin = true }) { Text(stringResource(R.string.unlock)) }
                }
            }
            LabeledSlider(stringResource(R.string.max_headphones), s.maxLevelHeadphones, vm::setMaxHeadphones, percent(s.maxLevelHeadphones), enabled = !locked)
            LabeledSlider(stringResource(R.string.max_speaker), s.maxLevelSpeaker, vm::setMaxSpeaker, percent(s.maxLevelSpeaker), enabled = !locked)
            LabeledSlider(stringResource(R.string.min_volume), s.minLevel, vm::setMin, percent(s.minLevel), range = 0f..0.5f, enabled = !locked)
            LabeledSlider(stringResource(R.string.safe_level), s.safeLevel, vm::setSafe, percent(s.safeLevel), enabled = !locked)
            LabeledSlider(stringResource(R.string.warn_level), s.warnLevel, vm::setWarn, percent(s.warnLevel), enabled = !locked)
            LabeledSlider(
                stringResource(R.string.daily_limit),
                s.dailyLimitMinutes.toFloat(),
                { vm.setDailyLimit(((it / 15).roundToInt() * 15)) },
                if (s.dailyLimitMinutes == 0) stringResource(R.string.off) else stringResource(R.string.hours_minutes, s.dailyLimitMinutes / 60, s.dailyLimitMinutes % 60),
                range = 0f..(16 * 60f),
                enabled = !locked,
            )
            Row {
                if (!locked) {
                    OutlinedButton(onClick = { setPin = true }) {
                        Text(stringResource(if (s.pinSet) R.string.change_pin else R.string.set_pin))
                    }
                    if (s.pinSet) TextButton(onClick = { vm.setPin(null) }) { Text(stringResource(R.string.remove_pin)) }
                }
            }
        }

        SectionCard(stringResource(R.string.fades)) {
            LabeledSlider(stringResource(R.string.fade_in), s.fadeInSec, { vm.setFadeIn(it.roundToInt().toFloat()) }, stringResource(R.string.seconds_short, s.fadeInSec.roundToInt()), range = 0f..30f)
            LabeledSlider(stringResource(R.string.fade_out), s.fadeOutSec, { vm.setFadeOut((it / 5).roundToInt() * 5f) }, stringResource(R.string.seconds_short, s.fadeOutSec.roundToInt()), range = 0f..180f)
            Text(stringResource(R.string.fade_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(stringResource(R.string.bluetooth_audio)) {
            SwitchRow(stringResource(R.string.auto_resume), s.autoResumeOnReconnect, vm::setAutoResume, description = stringResource(R.string.auto_resume_hint))
            Text(stringResource(R.string.focus_title), style = MaterialTheme.typography.bodyLarge)
            FocusOption(stringResource(R.string.focus_duck), s.focusBehavior == FocusBehavior.DUCK) { vm.setFocus(FocusBehavior.DUCK) }
            FocusOption(stringResource(R.string.focus_pause), s.focusBehavior == FocusBehavior.PAUSE) { vm.setFocus(FocusBehavior.PAUSE) }
            SwitchRow(stringResource(R.string.resume_after_interruption), s.resumeAfterFocusLoss, vm::setResumeFocus)
            SwitchRow(stringResource(R.string.relaxed_latency), s.relaxedLatency, vm::setRelaxed, description = stringResource(R.string.relaxed_latency_hint))
        }

        SectionCard(stringResource(R.string.background)) {
            val ignoring = vm.isIgnoringBatteryOptimizations()
            Text(stringResource(if (ignoring) R.string.battery_ok else R.string.battery_not_ok))
            OutlinedButton(onClick = onBatteryHelp) { Text(stringResource(R.string.battery_help)) }
            SwitchRow(stringResource(R.string.resume_boot), s.resumeAfterBoot, vm::setResumeBoot, description = stringResource(R.string.resume_boot_hint))
        }

        SectionCard(stringResource(R.string.display)) {
            SwitchRow(stringResource(R.string.light_theme), s.lightTheme, vm::setLightTheme)
        }

        SectionCard(stringResource(R.string.about)) {
            Text(stringResource(R.string.disclaimer_text), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.privacy_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.audio_load, "%.1f".format(cpu * 100)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (Build.VERSION.SDK_INT >= 31) {
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.app_permissions)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (askPin) {
        TextInputDialog(
            title = stringResource(if (pinError) R.string.pin_wrong else R.string.enter_pin),
            numeric = true,
            secret = true,
            confirm = stringResource(R.string.unlock),
            onDismiss = {
                askPin = false
                pinError = false
            },
            onConfirm = {
                if (vm.unlock(it)) {
                    askPin = false
                    pinError = false
                } else {
                    pinError = true
                }
            },
        )
    }
    if (setPin) {
        TextInputDialog(
            title = stringResource(R.string.new_pin),
            label = stringResource(R.string.pin_hint),
            numeric = true,
            secret = true,
            onDismiss = { setPin = false },
            onConfirm = {
                if (it.length in 4..8 && it.all(Char::isDigit)) {
                    vm.setPin(it)
                    setPin = false
                }
            },
        )
    }
}

@Composable
private fun FocusOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.weight(1f))
    }
}
