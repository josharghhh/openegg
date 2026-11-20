package com.openegg.app.link

import com.openegg.app.link.probe.HexDump
import com.openegg.app.link.probe.ProbeEnvironment
import com.openegg.app.link.probe.ProbeSection
import com.openegg.app.link.probe.RtspRequests
import com.openegg.app.link.probe.Verdict
import com.openegg.app.link.probe.VxFlyProtocol
import com.openegg.app.link.probe.connectFailure
import com.openegg.app.link.probe.connectTcp
import com.openegg.app.mavlink.MavlinkFrameDetector
import com.openegg.app.mavlink.PowerVisionDialect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Endpoints the original Vision+ app talks to (from GlobalConfig, the native SDK
 * strings and libvfvideo/libvfconfig, docs 03/04/07). The link tester probes each
 * one so a single session on real hardware tells us which ones the PowerEgg exposes.
 */
object KnownEndpoints {
    data class Tcp(val label: String, val host: String, val port: Int)
    data class Udp(val label: String, val port: Int)
    data class Rtsp(val label: String, val host: String, val port: Int, val optionsUrl: String, val describeUrls: List<String>)
    data class VxFly(val label: String, val host: String, val extraGets: List<Int>)

    /** Active MAVLink probes (heartbeat + PARAM_REQUEST_READ). */
    val mavlink = listOf(
        Tcp("Ground station TCP server (MAVLink)", "192.168.1.12", 20002),
        Tcp("Fallback MAVLink host", "192.168.1.11", 20002),
    )

    /** RTSP: DESCRIBE on the VxFly stream, OPTIONS only everywhere else. */
    val rtsp = listOf(
        Rtsp("VxFly air unit video", "192.168.1.240", 8557, RtspRequests.VXFLY_URL, listOf(RtspRequests.VXFLY_URL, RtspRequests.VXFLY_URL_CH1)),
        Rtsp("Air unit RTSP 554", "192.168.1.240", 554, RtspRequests.rootUrl("192.168.1.240", 554), emptyList()),
        Rtsp("Air unit RTSP 8554", "192.168.1.240", 8554, RtspRequests.rootUrl("192.168.1.240", 8554), emptyList()),
        Rtsp("Camera RTSP 554", "192.168.1.10", 554, RtspRequests.rootUrl("192.168.1.10", 554), emptyList()),
        Rtsp("Camera RTSP 8554", "192.168.1.10", 8554, RtspRequests.rootUrl("192.168.1.10", 8554), emptyList()),
        Rtsp("FPV RTSP", "192.168.1.20", 8554, RtspRequests.rootUrl("192.168.1.20", 8554), emptyList()),
    )

    /** VxFly pDDL config sockets (TCP 1234). */
    val vxfly = listOf(
        VxFly("VxFly ground radio config", "192.168.1.2", VxFlyProtocol.GROUND_EXTRA),
        VxFly("VxFly air unit config", "192.168.1.240", VxFlyProtocol.AIR_EXTRA),
    )

    /** Passive TCP: connect and listen only. */
    val tcp = listOf(
        Tcp("Camera HTTP", "192.168.1.10", 80),
        Tcp("Ambarella command", "192.168.1.100", 7878),
    )

    /** Passive UDP listeners on the phone. */
    val udp = listOf(
        Udp("Ground station UDP", 6009),
        Udp("Video stream", 60430),
        Udp("MAVLink default GCS port", 14550),
    )
}

data class ProbeResult(
    val label: String,
    val target: String,
    val reachable: Boolean,
    val bytesReceived: Int,
    val firstBytes: ByteArray,
    val mavlinkFrames: List<MavlinkFrameDetector.Frame>,
    val error: String? = null,
    val verdictOnError: Verdict = Verdict.NO_ANSWER,
    /** UDP: who sent the datagrams. */
    val senders: Set<String> = emptySet(),
    val elapsedMs: Long = 0,
) {
    val firstBytesHex: String get() = HexDump.hex(firstBytes, 32)

    private val validFrames get() = mavlinkFrames.filter { it.crcValid == true }

    val summary: String
        get() = when {
            error != null && !reachable -> "no answer ($error)"
            validFrames.isNotEmpty() -> {
                val f = validFrames.first()
                "MAVLink v${f.version}: ${validFrames.size} frames, sys=${f.systemId} comp=${f.componentId}, " +
                    "msg ids ${validFrames.map { it.messageId }.distinct().take(12)}"
            }
            bytesReceived > 0 -> "$bytesReceived bytes, not MAVLink: $firstBytesHex"
            reachable -> "open, silent"
            else -> "no data"
        }

    fun toSection(group: String): ProbeSection {
        val verdict = when {
            error != null && !reachable -> verdictOnError
            validFrames.isNotEmpty() -> Verdict.FOUND
            reachable || bytesReceived > 0 -> Verdict.OPEN
            else -> Verdict.NO_ANSWER
        }
        val details = buildList {
            if (error != null) add("error: $error")
            add("received $bytesReceived bytes")
            if (senders.isNotEmpty()) add("from: ${senders.joinToString()}")
            if (validFrames.isNotEmpty()) {
                add("MAVLink ids x count: " + validFrames.groupingBy { it.messageId }.eachCount().entries.joinToString { (id, n) ->
                    "${PowerVisionDialect.dialect[id]?.name ?: id}x$n"
                })
            }
            if (firstBytes.isNotEmpty()) { add("first bytes:"); addAll(HexDump.dump(firstBytes, 256)) }
        }
        return ProbeSection(group, label, target, verdict, summary, listOf("nothing (listen only)"), details, elapsedMs)
    }
}

/** Passive probes: TCP connect-and-listen and UDP listen. Nothing is sent. */
class LinkProbe(private val listenMillis: Long = 3000) {

    suspend fun probeTcp(ep: KnownEndpoints.Tcp, env: ProbeEnvironment = ProbeEnvironment()): ProbeResult = withContext(Dispatchers.IO) {
        val target = "${ep.host}:${ep.port}"
        val t0 = System.nanoTime()
        try {
            env.socketFactory.connectTcp(ep.host, ep.port, 2000).use { s ->
                s.soTimeout = 250
                collect(ep.label, target, reachable = true, t0 = t0) { buf -> s.getInputStream().read(buf) to null }
            }
        } catch (e: Exception) {
            val (v, why) = connectFailure(e)
            ProbeResult(ep.label, target, false, 0, ByteArray(0), emptyList(), why, v, elapsedMs = (System.nanoTime() - t0) / 1_000_000)
        }
    }

    suspend fun probeUdp(ep: KnownEndpoints.Udp, env: ProbeEnvironment = ProbeEnvironment()): ProbeResult = withContext(Dispatchers.IO) {
        val target = "udp:${ep.port}"
        val t0 = System.nanoTime()
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                runCatching { env.bindUdp(s) }
                s.bind(InetSocketAddress(ep.port))
                s.soTimeout = 250
                collect(ep.label, target, reachable = false, t0 = t0) { buf ->
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    p.length to "${p.address?.hostAddress}:${p.port}"
                }
            }
        } catch (e: Exception) {
            ProbeResult(ep.label, target, false, 0, ByteArray(0), emptyList(), e.javaClass.simpleName, Verdict.ERROR,
                elapsedMs = (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private suspend fun collect(
        label: String,
        target: String,
        reachable: Boolean,
        t0: Long,
        read: (ByteArray) -> Pair<Int, String?>,
    ): ProbeResult {
        val detector = PowerVisionDialect.detector()
        val frames = mutableListOf<MavlinkFrameDetector.Frame>()
        val first = ByteArrayOutputStream()
        val senders = linkedSetOf<String>()
        var total = 0
        val buf = ByteArray(65536)
        withTimeoutOrNull(listenMillis) {
            while (true) {
                val (n, from) = try { read(buf) } catch (_: SocketTimeoutException) { 0 to null }
                // Reads are blocking with a short socket timeout; yield every pass so the
                // listen window ends even while data streams continuously.
                kotlinx.coroutines.yield()
                if (n < 0) break
                if (n == 0) continue
                if (from != null && senders.size < 10) senders += from
                if (first.size() < 256) first.write(buf, 0, minOf(n, 256 - first.size()))
                total += n
                frames += detector.feed(buf, n)
            }
        }
        return ProbeResult(label, target, reachable || total > 0, total, first.toByteArray(), frames,
            senders = senders, elapsedMs = (System.nanoTime() - t0) / 1_000_000)
    }
}
