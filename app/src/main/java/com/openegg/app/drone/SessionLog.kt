package com.openegg.app.drone

import com.openegg.app.mavlink.MavMessage
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Records a flight session to a text file so a hardware test can be analysed
 * afterwards: link state changes, every command we send and its outcome, every
 * message id we receive (full contents the first few times, then rate-limited),
 * and anything the app did not understand.
 *
 * Files live in the app's external files dir (Android/data/com.openegg.app/files/sessions),
 * readable over USB and shareable from the app.
 */
class SessionLog(
    dir: File,
    label: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Full dumps of each message id before falling back to periodic samples. */
    private val fullDumpsPerId: Int = 5,
    /** After the full dumps, log one sample per id at most this often. */
    private val sampleEveryMs: Long = 2000,
) : AutoCloseable {

    val file: File

    private val writer: BufferedWriter
    private val counts = HashMap<Int, Int>()
    private val lastSampleAt = HashMap<Int, Long>()
    private val startedAt = clock()
    private var closed = false

    init {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAt))
        file = File(dir, "session-$stamp-${label.lowercase(Locale.US)}.log")
        writer = file.bufferedWriter()
        line("SESSION", "start $label at ${iso(startedAt)}")
    }

    @Synchronized
    fun event(kind: String, text: String) = line(kind, text)

    @Synchronized
    fun received(m: MavMessage) {
        val n = (counts[m.id] ?: 0) + 1
        counts[m.id] = n
        val now = clock()
        val dump = n <= fullDumpsPerId || now - (lastSampleAt[m.id] ?: 0L) >= sampleEveryMs
        if (!dump) return
        lastSampleAt[m.id] = now
        line("RX", "#${m.id} ${m.name} (n=$n) sys=${m.systemId} comp=${m.componentId} ${formatValues(m.values)}")
    }

    @Synchronized
    fun unknownFrame(messageId: Int, payload: ByteArray) {
        val n = (counts[-messageId - 1] ?: 0) + 1
        counts[-messageId - 1] = n
        if (n <= fullDumpsPerId) line("RX?", "unknown id $messageId len=${payload.size} ${hex(payload)}")
    }

    /** Totals per message id; written on close and available for the UI. */
    @Synchronized
    fun summary(): String = buildString {
        append("received: ")
        append(counts.filterKeys { it >= 0 }.toSortedMap().entries.joinToString { "${it.key}×${it.value}" })
        val unknown = counts.filterKeys { it < 0 }
        if (unknown.isNotEmpty()) {
            append(" | unknown: ")
            append(unknown.entries.joinToString { "${-it.key - 1}×${it.value}" })
        }
    }

    @Synchronized
    fun flush() { if (!closed) writer.flush() }

    @Synchronized
    override fun close() {
        if (closed) return
        line("SESSION", "end after ${(clock() - startedAt) / 1000}s; ${summary()}")
        closed = true
        writer.close()
    }

    private fun line(kind: String, text: String) {
        if (closed) return
        val t = (clock() - startedAt) / 1000.0
        writer.write("%9.3f %-7s %s\n".format(Locale.US, t, kind, text))
    }

    private fun formatValues(values: Map<String, Any>) = values.entries.joinToString(" ") { (k, v) ->
        "$k=" + when (v) {
            is Float -> "%.6g".format(Locale.US, v)
            is Double -> "%.9g".format(Locale.US, v)
            is IntArray -> v.joinToString(",", "[", "]")
            is LongArray -> v.joinToString(",", "[", "]")
            is FloatArray -> v.joinToString(",", "[", "]") { "%.4g".format(Locale.US, it) }
            is String -> "\"$v\""
            else -> v.toString()
        }
    }

    private fun hex(b: ByteArray) = b.take(64).joinToString(" ") { "%02x".format(it) } + if (b.size > 64) " …" else ""

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
