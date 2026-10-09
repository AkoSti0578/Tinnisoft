package nl.tinnisoft.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import nl.tinnisoft.R
import nl.tinnisoft.ui.SettingsViewModel
import nl.tinnisoft.ui.components.SectionCard
import nl.tinnisoft.ui.components.SwitchRow

private fun hhmm(minuteOfDay: Int) = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val presets by vm.presetList.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Int?>(null) } // 0 = start, 1 = stop
    val ctx = LocalContext.current

    fun save(enabled: Boolean = s.scheduleEnabled, start: Int = s.scheduleStartMinute, stop: Int = s.scheduleStopMinute, preset: Long = s.schedulePresetId) =
        vm.setSchedule(enabled, start, stop, preset)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.schedule_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard {
            SwitchRow(stringResource(R.string.schedule_enable), s.scheduleEnabled, { save(enabled = it) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.schedule_start), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { editing = 0 }) { Text(hhmm(s.scheduleStartMinute)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.schedule_stop), modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { editing = 1 }) { Text(hhmm(s.scheduleStopMinute)) }
            }
            Text(stringResource(R.string.schedule_preset))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.schedulePresetId < 0, onClick = { save(preset = -1) }, label = { Text(stringResource(R.string.last_used)) })
                presets.forEach { p ->
                    FilterChip(selected = s.schedulePresetId == p.id, onClick = { save(preset = p.id) }, label = { Text(p.name) })
                }
            }
        }
        if (s.scheduleEnabled && !vm.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
            SectionCard {
                Text(stringResource(R.string.exact_alarm_hint))
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.allow_exact_alarms)) }
            }
        }
    }

    editing?.let { which ->
        val initial = if (which == 0) s.scheduleStartMinute else s.scheduleStopMinute
        val tp = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = tp) } },
            confirmButton = {
                TextButton(onClick = {
                    val m = tp.hour * 60 + tp.minute
                    if (which == 0) save(start = m) else save(stop = m)
                    editing = null
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
