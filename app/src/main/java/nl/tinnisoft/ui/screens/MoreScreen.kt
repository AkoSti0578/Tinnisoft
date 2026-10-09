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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import nl.tinnisoft.R

@Composable
fun MoreScreen(navigate: (String) -> Unit) {
    data class Entry(val route: String, val icon: ImageVector, val title: Int, val subtitle: Int)
    val entries = listOf(
        Entry("matcher", Icons.Filled.GraphicEq, R.string.nav_matcher, R.string.nav_matcher_sub),
        Entry("log", Icons.Filled.MenuBook, R.string.nav_log, R.string.nav_log_sub),
        Entry("schedule", Icons.Filled.Alarm, R.string.nav_schedule, R.string.nav_schedule_sub),
        Entry("settings", Icons.Filled.Settings, R.string.nav_settings, R.string.nav_settings_sub),
        Entry("battery", Icons.Filled.BatteryChargingFull, R.string.battery_title, R.string.nav_battery_sub),
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(entries) { e ->
            Card(
                Modifier.fillMaxWidth().clickable { navigate(e.route) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(e.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(e.title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(e.subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
