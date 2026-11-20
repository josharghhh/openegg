package com.openegg.app.drone

import com.openegg.app.mavlink.MavMessage
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.PowerVisionDialect
import com.openegg.app.mavlink.gen.MavParamType
import com.openegg.app.mavlink.gen.MavResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Request/response operations on the PowerEgg, mirroring the original SDK:
 *  - flight modes (incl. return-home) via SET_MODE, confirmed by the next heartbeat;
 *  - parameters via PARAM_SET / PARAM_REQUEST_READ, confirmed by PARAM_VALUE;
 *  - camera actions via PowerVision message 160, confirmed by the aircraft's reply;
 *  - COMMAND_LONG for the few commands the firmware handles (calibration, circle).
 *
 * Arming, take-off and landing are deliberately NOT exposed: on the PowerEgg the
 * physical remote does those, exactly as with the original app.
 */
class DroneCommands(
    private val link: MavlinkLink,
    private val ackTimeoutMs: Long = 1500,
    private val modeTimeoutMs: Long = 3000,
    private val cameraTimeoutMs: Long = 5000,
    private val retries: Int = 3,
) {
    sealed interface Result {
        data object Ok : Result
        data class Rejected(val code: Int) : Result
        data object NoVehicle : Result
        data object Timeout : Result
    }

    private val target get() = link.vehicle

    // --- Flight modes -------------------------------------------------------------------------

    suspend fun setMode(mode: PowerEggMode): Result {
        val v = target ?: return Result.NoVehicle
        if (Px4Mode.fromCustomMode(v.customMode) == mode.reported) return Result.Ok
        repeat(retries) {
            val confirmed = awaitAfter(
                match = { it.name == "HEARTBEAT" && it.systemId == v.systemId && Px4Mode.fromCustomMode(it.long("custom_mode")) == mode.reported },
                timeoutMs = modeTimeoutMs,
            ) {
                val base = (link.vehicle?.baseMode ?: v.baseMode) and 0x81
                link.send(
                    "SET_MODE",
                    mapOf("target_system" to v.systemId, "base_mode" to base, "custom_mode" to mode.sent.toCustomMode()),
                )
            }
            if (confirmed != null) return Result.Ok
        }
        return Result.Timeout
    }

    suspend fun returnToHome() = setMode(PowerEggMode.RETURN_HOME)

    suspend fun hover() = setMode(PowerEggMode.HOVER)

    // --- COMMAND_LONG -------------------------------------------------------------------------

    suspend fun commandLong(command: Int, vararg params: Float): Result {
        val v = target ?: return Result.NoVehicle
        val p = FloatArray(7) { params.getOrElse(it) { 0f } }
        repeat(retries) { attempt ->
            val ack = awaitAfter({ it.name == "COMMAND_ACK" && it.int("command") == command }) {
                link.send(
                    "COMMAND_LONG",
                    mapOf(
                        "target_system" to v.systemId, "target_component" to v.componentId,
                        "command" to command, "confirmation" to attempt,
                        "param1" to p[0], "param2" to p[1], "param3" to p[2], "param4" to p[3],
                        "param5" to p[4], "param6" to p[5], "param7" to p[6],
                    ),
                )
            }
            if (ack != null) {
                val r = ack.int("result")
                return if (r == MavResult.ACCEPTED) Result.Ok else Result.Rejected(r)
            }
        }
        return Result.Timeout
    }

    // --- Parameters ---------------------------------------------------------------------------

    /** Reads one parameter by name; null on timeout. */
    suspend fun readParameter(name: String): Float? {
        val v = target ?: return null
        repeat(retries) {
            val reply = awaitAfter({ it.name == "PARAM_VALUE" && it.string("param_id") == name }) {
                link.send(
                    "PARAM_REQUEST_READ",
                    mapOf("target_system" to v.systemId, "target_component" to v.componentId, "param_id" to name, "param_index" to -1),
                )
            }
            if (reply != null) return reply.float("param_value")
        }
        return null
    }

    /** Writes a parameter and returns the value the vehicle echoes back (null on timeout). */
    suspend fun writeParameter(name: String, value: Float, type: Int = MavParamType.REAL32): Float? {
        val v = target ?: return null
        repeat(retries) {
            val reply = awaitAfter({ it.name == "PARAM_VALUE" && it.string("param_id") == name }) {
                link.send(
                    "PARAM_SET",
                    mapOf(
                        "target_system" to v.systemId, "target_component" to v.componentId,
                        "param_id" to name, "param_value" to value, "param_type" to type,
                    ),
                )
            }
            if (reply != null) return reply.float("param_value")
        }
        return null
    }

    /** PowerVision integer params travel as the int's bit pattern inside the float. */
    suspend fun readIntParameter(name: String): Int? = readParameter(name)?.toRawBits()

    suspend fun writeIntParameter(name: String, value: Int): Int? =
        writeParameter(name, Float.fromBits(value), MavParamType.INT32)?.toRawBits()

    /** Local activation the original app performed (PowerSDK.activate). No server involved. */
    suspend fun activate(): Boolean = writeIntParameter("PV_V_KEY_FLAG", 1) == 1

    // --- Camera -------------------------------------------------------------------------------

    /** Sends a camera action; returns the aircraft's result code, or null on timeout. */
    suspend fun camera(cmd: Int): Int? {
        if (target == null) return null
        val reply = awaitAfter(
            match = { it.name == "PV_CAMERA_COMMAND" && it.int("cmd") == cmd },
            timeoutMs = cameraTimeoutMs,
        ) {
            link.send("PV_CAMERA_COMMAND", mapOf("cmd" to cmd, "result" to 0))
        }
        return reply?.int("result")
    }

    suspend fun takePhoto() = camera(PowerVisionDialect.CameraCmd.TAKE_PHOTO)
    suspend fun startRecording() = camera(PowerVisionDialect.CameraCmd.RECORD_START)
    suspend fun stopRecording() = camera(PowerVisionDialect.CameraCmd.RECORD_STOP)

    // --- plumbing -----------------------------------------------------------------------------

    /** Subscribes first, then sends, so a fast reply can't be missed. */
    private suspend fun awaitAfter(
        match: (MavMessage) -> Boolean,
        timeoutMs: Long = ackTimeoutMs,
        send: suspend () -> Boolean,
    ): MavMessage? = coroutineScope {
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(timeoutMs) { link.messages.first(match) }
        }
        if (!send()) { waiter.cancel(); return@coroutineScope null }
        waiter.await()
    }
}
