package nl.tinnisoft.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import nl.tinnisoft.R
import nl.tinnisoft.ui.components.SectionCard

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(Icons.Filled.HealthAndSafety, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.welcome_text))
        SectionCard(stringResource(R.string.disclaimer_title)) {
            Text(stringResource(R.string.disclaimer_text))
            Text(stringResource(R.string.disclaimer_urgent), color = MaterialTheme.colorScheme.tertiary)
        }
        Text(stringResource(R.string.volume_advice), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.disclaimer_accept)) }
    }
}

/** Battery optimization exemption, with manufacturer-specific tips. */
@SuppressLint("BatteryLife") // sideloaded audio app: needed for reliable background playback
@Composable
fun BatteryScreen(onDone: () -> Unit, showSkip: Boolean = true) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val maker = Build.MANUFACTURER.lowercase()
    val isSamsung = maker.contains("samsung")
    val isXiaomi = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(Icons.Filled.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.battery_text))
        if (ignoring) {
            Text(stringResource(R.string.battery_ok), color = MaterialTheme.colorScheme.primary)
        } else {
            Button(onClick = {
                val i = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                runCatching { ctx.startActivity(i) }.onFailure {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.battery_request)) }
        }
        SectionCard(stringResource(R.string.tips_samsung_title) + if (isSamsung) " ★" else "") {
            Text(stringResource(R.string.tips_samsung))
        }
        SectionCard(stringResource(R.string.tips_xiaomi_title) + if (isXiaomi) " ★" else "") {
            Text(stringResource(R.string.tips_xiaomi))
        }
        SectionCard(stringResource(R.string.tips_other_title)) {
            Text(stringResource(R.string.tips_other))
        }
        OutlinedButton(onClick = {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)),
            )
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_app_settings)) }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (ignoring || !showSkip) R.string.done else R.string.later))
        }
    }
}
