package nl.tinnisoft.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import nl.tinnisoft.R
import nl.tinnisoft.audio.engine.OutputDevice
import nl.tinnisoft.playback.Notice
import nl.tinnisoft.playback.PlayStatus
import nl.tinnisoft.ui.PlayerViewModel
import nl.tinnisoft.ui.components.SectionCard
import nl.tinnisoft.ui.components.percent

@Composable
fun PlayerScreen(snackbar: SnackbarHostState, vm: PlayerViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val warningShown by vm.warningShown.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var showTimer by remember { mutableStateOf(false) }
    var showWarning by remember { mutableStateOf(false) }

    LaunchedEffect(st.notice) {
        val text = when (st.notice) {
            Notice.VOLUME_RESET_DEVICE_CHANGE -> ctx.getString(R.string.notice_volume_reset)
            Notice.DAILY_LIMIT_REACHED -> ctx.getString(R.string.notice_daily_limit)
            Notice.FOCUS_DENIED -> ctx.getString(R.string.notice_focus_denied)
            null -> null
        }
        if (text != null) {
            vm.consumeNotice()
            snackbar.showSnackbar(text)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutputCard(st.output, st.maxLevel)

        Text(
            st.activePreset?.let { if (st.modified) stringResource(R.string.preset_modified, it.name) else it.name }
                ?: stringResource(R.string.custom_mix),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )

        val playLabel = stringResource(if (st.isActive) R.string.pause else R.string.play)
        FilledIconButton(
            onClick = vm::togglePlay,
            modifier = Modifier.size(160.dp).semantics { contentDescription = playLabel },
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Icon(
                if (st.isActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(88.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Text(
            stringResource(
                when (st.status) {
                    PlayStatus.PLAYING -> R.string.status_playing
                    PlayStatus.FADING_OUT -> R.string.status_fading
                    PlayStatus.STOPPED -> R.string.status_paused
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showTimer = true }) {
                Icon(Icons.Filled.Timer, null)
                Spacer(Modifier.width(8.dp))
                TimerLabel(st.timerEndMillis)
            }
            if (st.isActive) {
                OutlinedButton(onClick = vm::stop) {
                    Icon(Icons.Filled.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.stop))
                }
            }
        }

        SectionCard(stringResource(R.string.volume)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = st.userLevel.coerceIn(st.minLevel, st.maxLevel),
                    onValueChange = { v ->
                        val clamped = v.coerceIn(st.minLevel, st.maxLevel)
                        if (clamped > settings.warnLevel && !warningShown) {
                            showWarning = true
                            vm.markWarningShown()
                        }
                        vm.setLevel(clamped)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(percent(st.effectiveLevel), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(R.string.volume_limits, percent(st.minLevel), percent(st.maxLevel)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.volume_advice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        if (st.presets.isNotEmpty()) {
            Text(stringResource(R.string.presets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(st.presets, key = { it.id }) { p ->
                    FilterChip(
                        selected = p.id == st.activePresetId,
                        onClick = { vm.selectPreset(p.id) },
                        label = { Text(p.name, modifier = Modifier.padding(vertical = 10.dp)) },
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showTimer) {
        TimerSheet(
            hasTimer = st.timerEndMillis != null,
            onDismiss = { showTimer = false },
            onMinutes = {
                vm.setTimer(it)
                showTimer = false
            },
            onUntil = { h, m ->
                vm.setTimerUntil(h, m)
                showTimer = false
            },
            onCancel = {
                vm.cancelTimer()
                showTimer = false
            },
        )
    }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text(stringResource(R.string.warning_title)) },
            text = { Text(stringResource(R.string.warning_text)) },
            confirmButton = { TextButton(onClick = { showWarning = false }) { Text(stringResource(R.string.understood)) } },
        )
    }
}

@Composable
private fun TimerLabel(end: Long?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) {
        while (end != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    if (end == null) {
        Text(stringResource(R.string.sleep_timer))
    } else {
        val left = ((end - now) / 1000).coerceAtLeast(0)
        Text("%d:%02d".format(left / 60, left % 60))
    }
}

@Composable
private fun OutputCard(device: OutputDevice, maxLevel: Float) {
    val icon = when (device.kind) {
        OutputDevice.Kind.BLUETOOTH -> Icons.Filled.Bluetooth
        OutputDevice.Kind.WIRED, OutputDevice.Kind.HEARING_AID -> Icons.Filled.Headphones
        OutputDevice.Kind.USB -> Icons.Filled.Usb
        else -> Icons.Filled.Speaker
    }
    val kind = stringResource(
        when (device.kind) {
            OutputDevice.Kind.SPEAKER -> R.string.out_speaker
            OutputDevice.Kind.WIRED -> R.string.out_wired
            OutputDevice.Kind.BLUETOOTH -> R.string.out_bluetooth
            OutputDevice.Kind.USB -> R.string.out_usb
            OutputDevice.Kind.HEARING_AID -> R.string.out_hearing_aid
            OutputDevice.Kind.OTHER -> R.string.out_other
        },
    )
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name ?: kind, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.output_cap, kind, percent(maxLevel)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerSheet(
    hasTimer: Boolean,
    onDismiss: () -> Unit,
    onMinutes: (Int) -> Unit,
    onUntil: (Int, Int) -> Unit,
    onCancel: () -> Unit,
) {
    var custom by remember { mutableStateOf(45f) }
    var pickTime by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sleep_timer_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 90).forEach { m ->
                    AssistChip(onClick = { onMinutes(m) }, label = { Text(stringResource(R.string.minutes_short, m)) })
                }
            }
            Text(stringResource(R.string.timer_custom, custom.toInt()))
            Slider(value = custom, onValueChange = { custom = it }, valueRange = 5f..480f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMinutes(custom.toInt()) }) { Text(stringResource(R.string.start_timer)) }
                OutlinedButton(onClick = { pickTime = true }) { Text(stringResource(R.string.timer_until)) }
            }
            if (hasTimer) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_timer)) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickTime) {
        val tp = rememberTimePickerState(initialHour = 7, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text(stringResource(R.string.timer_until)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = tp) } },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = false
                    onUntil(tp.hour, tp.minute)
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
