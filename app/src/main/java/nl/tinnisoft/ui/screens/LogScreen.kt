package nl.tinnisoft.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.tinnisoft.R
import nl.tinnisoft.data.db.LogEntryEntity
import nl.tinnisoft.ui.LogViewModel
import nl.tinnisoft.ui.components.LabeledSlider
import nl.tinnisoft.ui.components.SectionCard
import nl.tinnisoft.ui.components.SwitchRow
import kotlin.math.roundToInt

@Composable
fun LogScreen(vm: LogViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val todayEntry = entries.firstOrNull { it.epochDay == vm.today }
    var loud by remember { mutableFloatStateOf(5f) }
    var relief by remember { mutableFloatStateOf(5f) }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(todayEntry) {
        todayEntry?.let {
            loud = it.loudness.toFloat()
            relief = it.relief.toFloat()
            note = it.note
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SwitchRow(stringResource(R.string.log_enable), enabled, vm::setEnabled, description = stringResource(R.string.log_enable_hint))
        if (!enabled) return@Column

        SectionCard(stringResource(R.string.log_today)) {
            LabeledSlider(stringResource(R.string.log_loudness), loud, {
                loud = it
                saved = false
            }, "${loud.roundToInt()} / 10", range = 0f..10f, steps = 9)
            LabeledSlider(stringResource(R.string.log_relief), relief, {
                relief = it
                saved = false
            }, "${relief.roundToInt()} / 10", range = 0f..10f, steps = 9)
            OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.log_note)) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                vm.save(loud.roundToInt(), relief.roundToInt(), note)
                saved = true
            }) { Text(stringResource(if (saved) R.string.saved else R.string.save)) }
        }

        SectionCard(stringResource(R.string.log_chart_title)) {
            val loudColor = MaterialTheme.colorScheme.tertiary
            val reliefColor = MaterialTheme.colorScheme.primary
            Legend(loudColor, stringResource(R.string.log_loudness_short))
            Legend(reliefColor, stringResource(R.string.log_relief_short))
            if (entries.size < 2) {
                Text(stringResource(R.string.log_chart_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                WeekChart(entries, vm.today, loudColor, reliefColor, stringResource(R.string.log_chart_desc))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).padding(0.dp)) {
            Canvas(Modifier.fillMaxSize()) { drawCircle(color) }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Two lines (loudness and relief) over the last 8 weeks, with week grid lines. */
@Composable
private fun WeekChart(entries: List<LogEntryEntity>, today: Long, loud: Color, relief: Color, desc: String) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val days = 7 * 8
    val start = today - days + 1
    Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = desc }) {
        val w = size.width
        val h = size.height
        fun x(day: Long) = ((day - start).toFloat() / (days - 1)) * w
        fun y(v: Int) = h - (v / 10f) * h
        for (k in 0..8) {
            val gx = w * k / 8f
            drawLine(grid, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
        }
        for (v in listOf(0, 5, 10)) drawLine(grid, Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 1f)
        fun series(color: Color, value: (LogEntryEntity) -> Int) {
            val pts = entries.filter { it.epochDay >= start }.sortedBy { it.epochDay }
            val path = Path()
            pts.forEachIndexed { i, e ->
                val p = Offset(x(e.epochDay), y(value(e)))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                drawCircle(color, radius = 4.dp.toPx(), center = p)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        series(loud) { it.loudness }
        series(relief) { it.relief }
    }
    Text(stringResource(R.string.log_chart_axis), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
