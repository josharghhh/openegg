package com.openegg.app.ui.flight

import android.content.Context
import android.speech.tts.TextToSpeech
import com.openegg.app.drone.Alarms
import com.openegg.app.drone.AlarmSeverity
import com.openegg.app.drone.BatteryLevel
import com.openegg.app.drone.Telemetry
import com.openegg.app.drone.batteryLevel
import java.util.Locale

/**
 * Decides what to announce from successive telemetry snapshots. Pure, so it is unit-tested;
 * [VoiceAlerts] feeds its output to Android's on-device text-to-speech (replacing the
 * original app's Baidu cloud TTS).
 */
class AlertPolicy(private val repeatAfterMs: Long = 30_000) {
    private var lastAlarmWord = 0L
    private var lastBattery = BatteryLevel.UNKNOWN
    private var lastMode: String? = null
    private var lastStale: Boolean? = null
    private val lastSpokenAt = HashMap<String, Long>()

    fun next(t: Telemetry, linkStale: Boolean, connected: Boolean, now: Long): List<String> {
        val out = mutableListOf<String>()
        fun say(key: String, text: String, repeatable: Boolean = false) {
            val last = lastSpokenAt[key]
            if (last == null || (repeatable && now - last >= repeatAfterMs)) {
                out += text
                lastSpokenAt[key] = now
            }
        }

        if (connected) {
            if (lastStale == false && linkStale) say("link", "Signal lost")
            if (lastStale == true && !linkStale) { lastSpokenAt.remove("link"); out += "Signal restored" }
            lastStale = linkStale
        }

        for (a in Alarms.newlyRaised(lastAlarmWord, t.alarmWord)) {
            if (a.severity != AlarmSeverity.INFO || a.bit == 36) say("alarm${a.bit}", a.spoken)
        }
        // Allow an alarm to be spoken again if it clears and comes back.
        for (bit in 0..63) if ((t.alarmWord ushr bit) and 1L == 0L) lastSpokenAt.remove("alarm$bit")
        lastAlarmWord = t.alarmWord

        val b = t.batteryLevel
        if (b != lastBattery) {
            when (b) {
                BatteryLevel.LOW -> say("battLow", "Battery low, ${t.batteryPercent} percent")
                BatteryLevel.CRITICAL -> say("battCrit", "Battery critical, land now", repeatable = true)
                else -> {}
            }
            lastBattery = b
        } else if (b == BatteryLevel.CRITICAL) say("battCrit", "Battery critical, land now", repeatable = true)

        val mode = t.mode?.label
        if (mode != null && lastMode != null && mode != lastMode) say("mode:$mode:$now", mode)
        if (mode != null) lastMode = mode
        return out
    }
}

class VoiceAlerts(context: Context) : AutoCloseable {
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts.setLanguage(Locale.getDefault())
        }
    }

    fun speak(lines: List<String>) {
        if (!ready) return
        for (l in lines) tts.speak(l, TextToSpeech.QUEUE_ADD, null, l.hashCode().toString())
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }
}
