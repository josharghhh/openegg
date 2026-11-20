package com.openegg.app.link.probe

import java.net.ConnectException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.SocketFactory

/** How far a probe got. Ordered from best to worst for sorting the summary. */
enum class Verdict(val tag: String) {
    /** The endpoint spoke the protocol we expected (MAVLink frames, RTSP status line, VxFly reply). */
    FOUND("FOUND"),
    /** TCP connected or bytes arrived, but not the expected protocol (or silent). */
    OPEN("OPEN"),
    /** The host answered with a TCP reset: it is alive but nothing listens on that port. */
    REFUSED("REFUSED"),
    /** Connect timed out / no route / UDP silence. */
    NO_ANSWER("NO ANSWER"),
    ERROR("ERROR"),
}

/** One probe's result, ready for the screen and the text report. */
data class ProbeSection(
    val group: String,
    val label: String,
    val target: String,
    val verdict: Verdict,
    /** One line for the summary table / card header. */
    val summary: String,
    /** Everything we sent, human readable (with hex). */
    val sent: List<String> = emptyList(),
    /** Detailed findings: decoded fields, raw dumps, timings. */
    val details: List<String> = emptyList(),
    val elapsedMs: Long = 0,
)

/** What a probe needs from the platform; Android passes the base-station [android.net.Network]'s binders. */
class ProbeEnvironment(
    val socketFactory: SocketFactory = SocketFactory.getDefault(),
    val bindUdp: (DatagramSocket) -> Unit = {},
)

internal fun SocketFactory.connectTcp(host: String, port: Int, timeoutMs: Int): Socket {
    val s = createSocket()
    try {
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
    } catch (e: Exception) {
        runCatching { s.close() }
        throw e
    }
    return s
}

internal fun describeError(e: Throwable): String =
    e.javaClass.simpleName + (e.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

/** Maps a failed TCP connect to a verdict: a reset (refused) proves the host is up. */
internal fun connectFailure(e: Throwable): Pair<Verdict, String> = when (e) {
    is ConnectException ->
        if (e.message?.contains("refused", ignoreCase = true) == true) Verdict.REFUSED to "connection refused (host up, port closed)"
        else Verdict.NO_ANSWER to describeError(e)
    is SocketTimeoutException -> Verdict.NO_ANSWER to "connect timed out (host absent or filtered)"
    is NoRouteToHostException -> Verdict.NO_ANSWER to "no route to host"
    is UnknownHostException -> Verdict.ERROR to describeError(e)
    else -> Verdict.ERROR to describeError(e)
}

internal fun elapsedSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

internal fun fmtT(ms: Long): String = "t+%.2fs".format(ms / 1000.0)
