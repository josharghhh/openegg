package com.openegg.app.camera

import com.openegg.app.camera.CameraSettingsRepository.Nudge
import kotlin.math.abs

/**
 * Maps a vertical stick deflection to the original gesture magnitudes.
 *
 * [deflection] is -1 (fully up) .. +1 (fully down). The original moved after 10 px of drag and
 * went "fast" beyond 60 px; here that is a dead zone of [DEAD_ZONE] and a fast zone beyond [FAST_ZONE].
 */
object GimbalNudges {
    const val DEAD_ZONE = 0.15f
    const val FAST_ZONE = 0.6f

    fun forDeflection(deflection: Float): Nudge {
        val a = abs(deflection)
        return when {
            deflection.isNaN() || a < DEAD_ZONE -> Nudge.STOP
            deflection < 0 -> if (a >= FAST_ZONE) Nudge.UP_FAST else Nudge.UP
            else -> if (a >= FAST_ZONE) Nudge.DOWN_FAST else Nudge.DOWN
        }
    }
}
