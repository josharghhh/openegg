package com.openegg.app.link.probe

import com.openegg.app.link.KnownEndpoints
import com.openegg.app.link.LinkProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** One probe to run: identity for the UI plus the work itself. */
class ProbeTask(
    val id: String,
    val group: String,
    val label: String,
    val target: String,
    val run: suspend (ProbeEnvironment) -> ProbeSection,
)

/** Every probe of the link test, all run in parallel. Worst case is ~12 s (RTSP: connect + 3 requests). */
object LinkTestPlan {

    const val PER_TASK_TIMEOUT_MS = 16_000L

    fun tasks(
        mavlink: MavlinkProbe = MavlinkProbe(),
        rtsp: RtspProbe = RtspProbe(),
        vxfly: VxFlyProbe = VxFlyProbe(),
        passive: LinkProbe = LinkProbe(),
    ): List<ProbeTask> = buildList {
        for (ep in KnownEndpoints.mavlink) {
            add(ProbeTask("mav-${ep.host}", MavlinkProbe.GROUP, ep.label, "${ep.host}:${ep.port}") { env ->
                mavlink.run(env, ep.label, ep.host, ep.port)
            })
        }
        for (ep in KnownEndpoints.rtsp) {
            add(ProbeTask("rtsp-${ep.host}:${ep.port}", RtspProbe.GROUP, ep.label, "${ep.host}:${ep.port}") { env ->
                rtsp.run(env, ep.label, ep.host, ep.port, ep.optionsUrl, ep.describeUrls)
            })
        }
        for (ep in KnownEndpoints.vxfly) {
            add(ProbeTask("vf-${ep.host}", VxFlyProbe.GROUP, ep.label, "${ep.host}:${VxFlyProtocol.PORT}") { env ->
                vxfly.run(env, ep.label, ep.host, ep.extraGets)
            })
        }
        for (ep in KnownEndpoints.tcp) {
            add(ProbeTask("tcp-${ep.host}:${ep.port}", "TCP", ep.label, "${ep.host}:${ep.port}") { env ->
                passive.probeTcp(ep, env).toSection("TCP")
            })
        }
        for (ep in KnownEndpoints.udp) {
            add(ProbeTask("udp-${ep.port}", "UDP", ep.label, "udp:${ep.port}") { env ->
                passive.probeUdp(ep, env).toSection("UDP")
            })
        }
    }

    /** Runs [tasks] concurrently; [onResult] fires as each finishes. Results come back in task order. */
    suspend fun runAll(
        env: ProbeEnvironment,
        tasks: List<ProbeTask>,
        timeoutMs: Long = PER_TASK_TIMEOUT_MS,
        onResult: (ProbeTask, ProbeSection) -> Unit = { _, _ -> },
    ): List<ProbeSection> = coroutineScope {
        tasks.map { task ->
            async {
                val t0 = System.nanoTime()
                val section = try {
                    withTimeoutOrNull(timeoutMs) { task.run(env) }
                        ?: ProbeSection(task.group, task.label, task.target, Verdict.ERROR, "probe exceeded ${timeoutMs / 1000} s", elapsedMs = elapsedSince(t0))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ProbeSection(task.group, task.label, task.target, Verdict.ERROR, "probe crashed: ${describeError(e)}", elapsedMs = elapsedSince(t0))
                }
                onResult(task, section)
                section
            }
        }.awaitAll()
    }
}
