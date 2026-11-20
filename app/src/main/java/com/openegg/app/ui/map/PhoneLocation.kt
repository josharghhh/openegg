package com.openegg.app.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.openegg.app.map.GeoPoint

/**
 * The phone's own position (GPS works without internet). Permission is only requested when
 * [PhoneLocationState.request] is called — never automatically mid-flight — and everything
 * degrades to "no phone marker" if it is denied.
 */
@Stable
class PhoneLocationState internal constructor() {
    internal var launch: () -> Unit = {}

    var granted by mutableStateOf(false)
        internal set
    var location by mutableStateOf<GeoPoint?>(null)
        internal set

    fun request() { if (!granted) launch() }
}

private val PERMS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun hasPermission(ctx: Context) = PERMS.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun rememberPhoneLocation(): PhoneLocationState {
    val ctx = LocalContext.current
    val state = remember { PhoneLocationState() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        state.granted = res.values.any { it } || hasPermission(ctx)
    }
    state.launch = { launcher.launch(PERMS) }
    if (!state.granted && hasPermission(ctx)) state.granted = true

    DisposableEffect(state.granted) {
        if (!state.granted) return@DisposableEffect onDispose {}
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { l: Location -> state.location = GeoPoint(l.latitude, l.longitude) }
        startUpdates(lm, listener, state)
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return state
}

@SuppressLint("MissingPermission") // checked by the caller via state.granted
private fun startUpdates(lm: LocationManager, listener: LocationListener, state: PhoneLocationState) {
    try {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { state.location = GeoPoint(it.latitude, it.longitude) }
        providers.forEach { lm.requestLocationUpdates(it, 2_000L, 2f, listener, Looper.getMainLooper()) }
    } catch (_: SecurityException) {
        state.granted = false
    }
}
