package com.openegg.app.vxfly

/**
 * Ground-unit battery voltage to percent (docs/07 §2.5, libvfconfig
 * `didRecvCallback` case 0x10). The native code indexes a 650-entry
 * `double[]` by millivolt; we use its 25 mV samples with linear interpolation,
 * which the doc puts within about 1 % of the native result.
 */
object VxBattery {
    const val EMPTY_MV = 3400
    const val FULL_MV = 4050

    // 3400..4050 mV in 25 mV steps (single Li-ion cell curve).
    private val CURVE = doubleArrayOf(
        0.20, 1.08, 2.15, 3.54, 5.40, 8.02, 11.55, 15.77,
        20.93, 28.63, 40.58, 50.51, 57.36, 62.88, 67.73, 72.22,
        76.59, 80.76, 84.31, 87.19, 89.78, 92.50, 95.26, 97.54,
        98.94, 99.60, 100.0,
    )
    private const val STEP_MV = 25

    /** `mV = (int)(V*1000)`; a tiny epsilon keeps e.g. 3.912 from truncating to 3911. */
    fun millivolts(volts: Double): Int = (volts * 1000.0 + 1e-6).toInt()

    /** Percent 0.0..100.0, rounded to 2 decimals like the native `(int)((x + 0.005) * 100)`. */
    fun percentFromMillivolts(mV: Int): Double {
        if (mV < EMPTY_MV) return 0.0
        if (mV >= FULL_MV) return 100.0
        val off = mV - EMPTY_MV
        val i = off / STEP_MV
        val frac = (off % STEP_MV).toDouble() / STEP_MV
        val p = CURVE[i] + (CURVE[i + 1] - CURVE[i]) * frac
        return ((p + 0.005) * 100.0).toInt() / 100.0
    }

    /**
     * Native 1 mV hysteresis: a reading exactly 1 mV above the last accepted one is
     * ignored and the last value kept. Returns the millivolt value to use.
     */
    fun applyHysteresis(lastAcceptedMv: Int?, mV: Int): Int =
        if (lastAcceptedMv != null && mV - lastAcceptedMv == 1) lastAcceptedMv else mV
}

/** Downlink signal bars from SNR (docs/07 §2.4 note, `gcs/manager/PowerManager.java:673-695`). */
object VxSignal {
    /** null = "no link" icon (SNR < 0); otherwise 0..5 bars. */
    fun barsFromSnr(snr: Int): Int? = when {
        snr < 0 -> null
        snr == 0 -> 0
        snr <= 5 -> 1
        snr <= 10 -> 2
        snr <= 20 -> 3
        snr <= 25 -> 4
        else -> 5
    }

    /** Parses the `"rssi,snr,noise"` reply; null unless exactly three integers. */
    fun parseRadioStatus(text: String): Triple<Int, Int, Int>? {
        val parts = text.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 3) return null
        return Triple(parts[0], parts[1], parts[2])
    }
}
