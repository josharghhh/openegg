package com.openegg.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openegg.app.map.GeoBounds
import com.openegg.app.map.OfflineLimits
import com.openegg.app.map.OfflineMapManager
import com.openegg.app.map.OfflineMapManager.DownloadState
import com.openegg.app.ui.flight.FlightColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pre-download map areas for offline flying. Must be used with normal internet (home Wi-Fi or
 * mobile data), not while connected to the drone's base station.
 */
@Composable
fun OfflineMapsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { OfflineMapManager.get(context) }
    val areas by manager.areas.collectAsStateWithLifecycle()
    val download by manager.download.collectAsStateWithLifecycle()
    val storage by manager.storageBytes.collectAsStateWithLifecycle()
    val phone = rememberPhoneLocation()

    var viewport by remember { mutableStateOf<Pair<GeoBounds, Double>?>(null) }
    var askedLocation by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<OfflineMapManager.Area?>(null) }
    var initialCenter by remember { mutableStateOf(areas.firstOrNull()?.bounds?.center) }

    LaunchedEffect(Unit) {
        manager.refresh()
        // At home, before any flight: a fine moment to ask for location, so "around me" works.
        if (!askedLocation) { askedLocation = true; phone.request() }
    }
    LaunchedEffect(phone.location == null) { if (initialCenter == null) initialCenter = phone.location }
    LaunchedEffect(areas.isEmpty()) { if (initialCenter == null) initialCenter = areas.firstOrNull()?.bounds?.center }

    Row(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        FlightMap(
            state = MapAircraftState(),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            showFlightControls = false,
            onViewportChanged = { b, z -> viewport = b to z },
            centerOn = initialCenter,
            phoneLocation = phone,
        )
        Column(Modifier.width(320.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Back") }
                Text("Offline maps", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "Download the area you will fly in while you still have internet. " +
                    "The drone's Wi-Fi has none, so only downloaded areas show in the field.",
                fontSize = 13.sp, color = FlightColors.muted,
            )

            val plan = viewport?.let { manager.plan(it.first) }
            val running = download as? DownloadState.Running
            when {
                running != null -> {
                    Text("Downloading “${running.name}” (${running.styleIndex + 1}/${running.styleCount})…")
                    LinearProgressIndicator(progress = { running.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("${running.completed}/${running.required} files · ${formatBytes(running.bytes)}", fontSize = 12.sp, color = FlightColors.muted)
                    running.lastError?.let { Text(it, fontSize = 12.sp, color = FlightColors.warn) }
                    OutlinedButton(onClick = manager::cancel) { Text("Cancel") }
                }
                else -> {
                    when {
                        viewport == null -> Text("Move the map to the area you want.", fontSize = 13.sp)
                        plan == null -> Text("This view is too large — zoom in.", fontSize = 13.sp, color = FlightColors.warn)
                        else -> Text("This view: ~${plan.tiles} tiles, ~${formatBytes(plan.estimatedBytes)} per style, detail to zoom ${plan.maxZoom}.", fontSize = 13.sp)
                    }
                    Button(enabled = plan != null, onClick = {
                        viewport?.let { manager.downloadArea(it.first, areaName(it.first)) }
                    }) { Text("Download this area") }
                    phone.location?.let { me ->
                        OutlinedButton(onClick = {
                            val b = GeoBounds.around(me, OfflineLimits.DEFAULT_RADIUS_M)
                            manager.downloadArea(b, "Around me · " + areaName(b))
                        }) { Text("Download ${(OfflineLimits.DEFAULT_RADIUS_M / 1000).toInt()} km around me") }
                    }
                    when (val d = download) {
                        is DownloadState.Done -> Text("Saved “${d.name}” (${formatBytes(d.bytes)}).", fontSize = 13.sp, color = FlightColors.ok)
                        is DownloadState.Failed -> Text("“${d.name}” failed: ${d.message}", fontSize = 13.sp, color = FlightColors.danger)
                        else -> {}
                    }
                }
            }

            HorizontalDivider()
            Text("Saved areas · ${formatBytes(storage)}", style = MaterialTheme.typography.titleSmall)
            if (areas.isEmpty()) Text("None yet.", fontSize = 13.sp, color = FlightColors.muted)
            LazyColumn(Modifier.weight(1f)) {
                items(areas, key = { it.areaId }) { a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontSize = 14.sp)
                            Text(
                                formatBytes(a.bytes) + (if (a.complete) "" else " · incomplete") + " · z${a.maxZoom}",
                                fontSize = 12.sp, color = if (a.complete) FlightColors.muted else FlightColors.warn,
                            )
                        }
                        TextButton(onClick = { initialCenter = a.bounds.center }) { Text("Show") }
                        TextButton(onClick = { confirmDelete = a }) { Text("Delete", color = FlightColors.danger) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Map data © OpenStreetMap contributors, tiles by OpenFreeMap.", fontSize = 11.sp, color = FlightColors.muted)
        }
    }

    confirmDelete?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${a.name}”?") },
            text = { Text("The map for this area will no longer be available offline. You can download it again later.") },
            confirmButton = { TextButton(onClick = { manager.delete(a.areaId); confirmDelete = null }) { Text("Delete", color = FlightColors.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

private fun areaName(b: GeoBounds): String {
    val c = b.center
    return SimpleDateFormat("d MMM", Locale.getDefault()).format(Date()) + " · %.3f, %.3f".format(Locale.US, c.lat, c.lon)
}

internal fun formatBytes(n: Long): String = when {
    n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
    n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
    n >= 1L shl 10 -> "%.0f KB".format(n / (1L shl 10).toDouble())
    else -> "$n B"
}
