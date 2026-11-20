package com.openegg.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openegg.app.drone.DroneSession

@Composable
fun StartScreen(onFly: (DroneSession.Source) -> Unit, onLinkTest: () -> Unit, onOfflineMaps: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OpenEgg", style = MaterialTheme.typography.displaySmall)
        Text(
            "Ground control for the original PowerVision PowerEgg. No account, no cloud.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        Text(
            "1. Power on the remote, base station and aircraft.\n" +
                "2. Join the base station Wi-Fi (Power_Ground…, password 1234567890).\n" +
                "3. Tap Fly.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.widthIn(max = 520.dp).padding(bottom = 24.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onFly(DroneSession.Source.BASE_STATION) }) { Text("Fly") }
            OutlinedButton(onClick = { onFly(DroneSession.Source.DEMO) }) { Text("Demo mode") }
            OutlinedButton(onClick = onLinkTest) { Text("Link test") }
            OutlinedButton(onClick = onOfflineMaps) { Text("Offline maps") }
        }
        Text(
            "Tip: download your flying area under Offline maps while you still have internet. " +
                "The base station Wi-Fi has none.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(max = 520.dp).padding(top = 16.dp),
        )
    }
}
