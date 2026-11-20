package com.openegg.app.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openegg.app.link.DroneNetwork
import com.openegg.app.link.probe.LinkTestPlan
import com.openegg.app.link.probe.ProbeEnvironment
import com.openegg.app.link.probe.ProbeReport
import com.openegg.app.link.probe.ProbeSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime

class LinkTestViewModel(app: Application) : AndroidViewModel(app) {

    val network: StateFlow<DroneNetwork.State> = DroneNetwork(app).observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DroneNetwork.State(null, null, null))

    data class Row(val id: String, val group: String, val label: String, val target: String, val section: ProbeSection?)

    data class UiState(
        val running: Boolean = false,
        val rows: List<Row> = emptyList(),
        val report: String? = null,
        val savedPath: String? = null,
        val saveError: String? = null,
        val startedAtMs: Long = 0,
    ) {
        val done get() = rows.count { it.section != null }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun runProbe() {
        if (_ui.value.running) return
        val tasks = LinkTestPlan.tasks()
        _ui.value = UiState(
            running = true,
            rows = tasks.map { Row(it.id, it.group, it.label, it.target, null) },
            startedAtMs = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            val app = getApplication<Application>()
            val started = ZonedDateTime.now()
            val t0 = System.nanoTime()
            // Video and some discovery traffic is multicast/broadcast on the base station LAN.
            val wifi = app.getSystemService(WifiManager::class.java)
            val lock = wifi.createMulticastLock("openegg-probe").apply { setReferenceCounted(false); acquire() }
            val net = network.value.network
            val env = if (net != null) ProbeEnvironment(net.socketFactory) { net.bindSocket(it) } else ProbeEnvironment()
            try {
                val sections = LinkTestPlan.runAll(env, tasks) { task, section ->
                    _ui.update { s -> s.copy(rows = s.rows.map { if (it.id == task.id) it.copy(section = section) else it }) }
                }
                val text = ProbeReport.format(header(started, (System.nanoTime() - t0) / 1_000_000), sections)
                _ui.update { it.copy(report = text) }
                saveReport(started, text)
            } finally {
                lock.release()
                _ui.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun saveReport(time: ZonedDateTime, text: String) {
        val app = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val dir = app.getExternalFilesDir("reports") ?: File(app.filesDir, "reports")
                dir.mkdirs()
                File(dir, ProbeReport.fileName(time)).apply { writeText(text) }.absolutePath
            }
        }
        _ui.update { it.copy(savedPath = result.getOrNull(), saveError = result.exceptionOrNull()?.let { e -> e.javaClass.simpleName + ": " + e.message }) }
    }

    /** Report text: the finished one, or a partial one built from what has completed so far. */
    fun report(): String = _ui.value.report ?: ProbeReport.format(
        header(ZonedDateTime.now(), null),
        _ui.value.rows.mapNotNull { it.section },
    )

    private fun header(time: ZonedDateTime, runMs: Long?): ProbeReport.Header {
        val app = getApplication<Application>()
        val version = runCatching {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
            "${pi.versionName} (${pi.longVersionCode})"
        }.getOrDefault("?")
        return ProbeReport.Header(
            time = time,
            app = "OpenEgg $version (${app.packageName})",
            device = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}",
            network = networkLines(),
            runMs = runMs,
        )
    }

    private fun networkLines(): List<String> {
        val n = network.value
        val lines = mutableListOf(
            "base-station Wi-Fi bound=${if (n.isUp) "yes" else "NO"} local=${n.localAddress ?: "-"} gateway=${n.gateway ?: "-"}",
        )
        val net = n.network ?: return lines + "probes used the default network (not bound to the station Wi-Fi)"
        runCatching {
            val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(net)
            val wi = caps?.transportInfo as? WifiInfo
            if (wi != null) lines += "wifi rssi=${wi.rssi} dBm freq=${wi.frequency} MHz link=${wi.linkSpeed} Mbps"
            cm.getLinkProperties(net)?.let { lp ->
                lines += "iface=${lp.interfaceName} addrs=${lp.linkAddresses.joinToString()} dns=${lp.dnsServers.joinToString { it.hostAddress ?: "?" }}"
                lines += "routes=${lp.routes.joinToString { it.toString() }}"
            }
            lines += "has-internet=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)} validated=${caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
        }
        return lines
    }
}
