package com.openegg.app.link

import com.openegg.app.link.probe.LinkTestPlan
import com.openegg.app.link.probe.MavlinkProbe
import com.openegg.app.link.probe.ProbeEnvironment
import com.openegg.app.link.probe.ProbeSection
import com.openegg.app.link.probe.ProbeTask
import com.openegg.app.link.probe.RtspProbe
import com.openegg.app.link.probe.Verdict
import com.openegg.app.link.probe.VxFlyProbe
import com.openegg.app.link.probe.VxFlyProtocol
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.PowerVisionDialect
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/** Runs each active probe against an in-process fake server on 127.0.0.1. */
class ProbeLoopbackTest {

    private val servers = mutableListOf<ServerSocket>()
    private val env = ProbeEnvironment()

    @After
    fun tearDown() { servers.forEach { runCatching { it.close() } } }

    private fun serve(handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        servers += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { s.use { runCatching { handler(it) } } }
            }
        }
        return ss.localPort
    }

    @Test
    fun mavlinkProbeHandshakesWithFakeVehicle() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val port = serve { s ->
            s.soTimeout = 50
            val codec = MavlinkCodec(PowerVisionDialect.dialect, systemId = 1, componentId = 1)
            val detector = PowerVisionDialect.detector()
            val out = s.getOutputStream()
            val buf = ByteArray(1024)
            var lastHb = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastHb > 200) {
                    out.write(codec.encode("HEARTBEAT", mapOf("type" to 2, "autopilot" to 12, "base_mode" to 0x51,
                        "custom_mode" to 0x00030000L, "system_status" to 3, "mavlink_version" to 3)))
                    out.write(codec.encode("ATTITUDE", mapOf("roll" to 0.1f)))
                    lastHb = now
                }
                val n = try { s.getInputStream().read(buf) } catch (_: SocketTimeoutException) { 0 }
                if (n < 0) break
                for (f in detector.feed(buf, n.coerceAtLeast(0))) {
                    received += f.messageId
                    if (f.messageId == 20) {
                        out.write(codec.encode("PARAM_VALUE", mapOf("param_id" to "PV_V_KEY_FLAG",
                            "param_value" to Float.fromBits(1), "param_type" to 6, "param_count" to 1, "param_index" to 0)))
                    }
                }
            }
        }
        val section = MavlinkProbe(listenMs = 1800, fallbackMs = 500).run(env, "fake", "127.0.0.1", port)
        assertEquals(section.summary, Verdict.FOUND, section.verdict)
        assertTrue(section.summary, section.summary.contains("PV_V_KEY_FLAG=0x00000001"))
        assertTrue(section.summary, section.summary.contains("autopilot 12"))
        assertTrue(section.details.joinToString("\n"), section.details.any { it.contains("ATTITUDE") })
        assertTrue(received.contains(0))
        assertTrue(received.contains(20))
        assertTrue("only heartbeat and param read may be sent: $received", received.all { it == 0 || it == 20 })
        // The param reply stops further requests.
        assertEquals(1, received.count { it == 20 })
    }

    @Test
    fun mavlinkProbeReportsSilentSocket() = runBlocking {
        val port = serve { s -> s.soTimeout = 3000; runCatching { while (s.getInputStream().read() >= 0) Unit } }
        val section = MavlinkProbe(listenMs = 1200, fallbackMs = 400).run(env, "fake", "127.0.0.1", port)
        assertEquals(Verdict.OPEN, section.verdict)
        // It still tried our heartbeat and the param read after the fallback.
        assertTrue(section.sent.joinToString(), section.sent.any { it.contains("sent anyway") })
        assertTrue(section.sent.any { it.contains("target 1/1") })
    }

    @Test
    fun rtspProbeParsesDescribe() = runBlocking {
        val sps = Base64.getEncoder().encodeToString(TestSps.baseline720())
        val pps = Base64.getEncoder().encodeToString(TestSps.PPS)
        val sdp = "v=0\r\ns=VFLive\r\nt=0 0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;profile-level-id=42C01F;sprop-parameter-sets=$sps,$pps\r\na=control:track1\r\n"
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val port = serve { s ->
            val input = s.getInputStream()
            val acc = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) break
                acc.write(b)
                val text = acc.toString(Charsets.US_ASCII)
                if (!text.endsWith("\r\n\r\n")) continue
                acc.reset()
                requests += text
                val cseq = text.lines().first { it.startsWith("CSeq") }.substringAfter(':').trim()
                val reply = when {
                    text.startsWith("OPTIONS") -> "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n\r\n"
                    text.contains("channels/2") -> "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nServer: FakeLive\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp"
                    else -> "RTSP/1.0 404 Stream Not Found\r\nCSeq: $cseq\r\n\r\n"
                }
                s.getOutputStream().write(reply.toByteArray())
            }
        }
        val url2 = "rtsp://127.0.0.1:$port/PSIA/Streaming/channels/2?videoCodecType=H.264"
        val url1 = "rtsp://127.0.0.1:$port/PSIA/Streaming/channels/1?videoCodecType=H.264"
        val section = RtspProbe(responseTimeoutMs = 1500).run(env, "fake", "127.0.0.1", port, url2, listOf(url2, url1))
        assertEquals(Verdict.FOUND, section.verdict)
        assertTrue(section.summary, section.summary.contains("OPTIONS 200 OK"))
        assertTrue(section.summary, section.summary.contains("DESCRIBE 404"))
        assertTrue(section.summary, section.summary.contains("H264 1280x720"))
        assertTrue(section.summary, section.summary.contains("Server: FakeLive"))
        assertTrue(section.details.any { it.contains("sprop-parameter-sets") })
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.contains("User-Agent: OpenEgg\r\n") })
        assertTrue(requests.none { it.startsWith("SETUP") || it.startsWith("PLAY") })
    }

    @Test
    fun vxflyProbeLogsReplies() = runBlocking {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val port = serve { s ->
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val dec = VxFlyProtocol.Decoder()
            val buf = ByteArray(64)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (f in dec.feed(buf, n)) {
                    received += f.cmd
                    val reply = when (f.cmd) {
                        0x1C -> "SUCCESS"; 0x10 -> "3.912"; 0x1F -> "-62,28,-95"; 0x1E -> "PV-FTA-S-V1.0"; else -> null
                    } ?: continue
                    out.write(byteArrayOf(f.cmd.toByte(), reply.length.toByte()) + reply.toByteArray())
                }
            }
        }
        val section = VxFlyProbe(listenMs = 2500).runOn(port)
        assertEquals(section.details.joinToString("\n"), Verdict.FOUND, section.verdict)
        assertTrue(section.summary, section.summary.contains("battery 3.912 V"))
        assertTrue(section.summary, section.summary.contains("\"-62,28,-95\""))
        assertTrue(section.details.any { it.contains("PV-FTA-S-V1.0") })
        assertEquals(listOf(0x1C, 0x00, 0x10, 0x1F, 0x1E), received.take(5))
        assertFalse(received.any { it in VxFlyProtocol.FORBIDDEN })
    }

    private suspend fun VxFlyProbe.runOn(port: Int): ProbeSection {
        // VxFlyProbe always uses port 1234; point it at the fake through a redirecting factory.
        val redirect = object : javax.net.SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
                    super.connect(java.net.InetSocketAddress("127.0.0.1", port), timeout)
            }
            override fun createSocket(h: String?, p: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(h: String?, p: Int, la: InetAddress?, lp: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(h: InetAddress?, p: Int): Socket = throw UnsupportedOperationException()
            override fun createSocket(h: InetAddress?, p: Int, la: InetAddress?, lp: Int): Socket = throw UnsupportedOperationException()
        }
        return run(ProbeEnvironment(redirect), "fake", "127.0.0.1", VxFlyProtocol.GROUND_EXTRA)
    }

    @Test
    fun closedPortIsNotFound() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val section = RtspProbe().run(env, "closed", "127.0.0.1", port)
        assertTrue(section.summary, section.verdict == Verdict.REFUSED || section.verdict == Verdict.NO_ANSWER)
    }

    @Test
    fun runAllRunsInParallelAndKeepsOrder() = runBlocking {
        val tasks = (1..5).map { i ->
            ProbeTask("t$i", "G", "task $i", "x:$i") { _ ->
                delay(300)
                ProbeSection("G", "task $i", "x:$i", Verdict.OPEN, "done $i")
            }
        }
        val seen = Collections.synchronizedList(mutableListOf<String>())
        val start = System.nanoTime()
        val out = LinkTestPlan.runAll(env, tasks) { t, _ -> seen += t.id }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 1200)
        assertEquals((1..5).map { "done $it" }, out.map { it.summary })
        assertEquals(5, seen.size)
    }

    @Test
    fun runAllConvertsTimeoutAndCrash() = runBlocking {
        val tasks = listOf(
            ProbeTask("slow", "G", "slow", "x") { _ -> delay(5000); error("unreachable") },
            ProbeTask("boom", "G", "boom", "y") { _ -> throw IllegalStateException("kaput") },
        )
        val out = LinkTestPlan.runAll(env, tasks, timeoutMs = 200)
        assertEquals(Verdict.ERROR, out[0].verdict)
        assertTrue(out[0].summary.contains("exceeded"))
        assertEquals(Verdict.ERROR, out[1].verdict)
        assertTrue(out[1].summary.contains("kaput"))
    }

    @Test
    fun planCoversEveryRequestedEndpoint() {
        val targets = LinkTestPlan.tasks().map { "${it.group} ${it.target}" }.toSet()
        for (t in listOf(
            "MAVLink 192.168.1.12:20002", "MAVLink 192.168.1.11:20002",
            "RTSP 192.168.1.240:8557", "RTSP 192.168.1.240:554", "RTSP 192.168.1.240:8554",
            "RTSP 192.168.1.10:554", "RTSP 192.168.1.10:8554",
            "VxFly 192.168.1.2:1234", "VxFly 192.168.1.240:1234",
            "UDP udp:6009", "UDP udp:60430", "UDP udp:14550",
        )) assertTrue("missing $t in $targets", t in targets)
        assertEquals("task ids must be unique", LinkTestPlan.tasks().size, LinkTestPlan.tasks().map { it.id }.toSet().size)
    }
}
