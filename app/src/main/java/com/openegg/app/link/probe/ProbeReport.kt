package com.openegg.app.link.probe

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Plain-text link test report, meant to be pasted back for analysis. Pure; unit tested. */
object ProbeReport {

    data class Header(
        val time: ZonedDateTime,
        val app: String,
        val device: String,
        val android: String,
        /** Wi-Fi / network lines, already formatted ("bound=yes local=… gateway=…"). */
        val network: List<String>,
        val runMs: Long?,
    )

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
    private val FILE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Max detail lines per probe, so one chatty endpoint cannot bury the rest. */
    const val MAX_DETAIL_LINES = 160

    fun fileName(time: ZonedDateTime) = "openegg-linktest-${time.format(FILE)}.txt"

    fun format(header: Header, sections: List<ProbeSection>): String = buildString {
        appendLine("==== OpenEgg link test ====")
        appendLine("Time:    ${header.time.format(ISO)}" + (header.runMs?.let { "  (run took %.1f s)".format(it / 1000.0) } ?: ""))
        appendLine("App:     ${header.app}")
        appendLine("Device:  ${header.device}")
        appendLine("Android: ${header.android}")
        header.network.forEachIndexed { i, l -> appendLine((if (i == 0) "Network: " else "         ") + l) }
        appendLine()

        val found = sections.count { it.verdict == Verdict.FOUND }
        appendLine("SUMMARY  ($found of ${sections.size} endpoints answered with their protocol)")
        val tw = sections.maxOfOrNull { it.target.length } ?: 0
        val gw = sections.maxOfOrNull { it.group.length } ?: 0
        for (s in sections) {
            appendLine("  %-11s %-${gw}s %-${tw}s  %s".format("[${s.verdict.tag}]", s.group, s.target, s.summary))
        }
        appendLine()
        appendLine("DETAILS")
        for (s in sections) {
            appendLine()
            appendLine("--- ${s.group} · ${s.label} · ${s.target} · ${s.verdict.tag} · %.1f s".format(s.elapsedMs / 1000.0))
            appendLine("Result: ${s.summary}")
            if (s.sent.isNotEmpty()) {
                appendLine("Sent:")
                s.sent.forEach { appendLine("  $it") }
            }
            if (s.details.isNotEmpty()) {
                appendLine("Details:")
                s.details.take(MAX_DETAIL_LINES).forEach { appendLine("  $it") }
                if (s.details.size > MAX_DETAIL_LINES) appendLine("  … ${s.details.size - MAX_DETAIL_LINES} more lines cut")
            }
        }
        appendLine()
        appendLine("==== end of report ====")
    }
}
