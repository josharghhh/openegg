package com.openegg.app.link

import com.openegg.app.drone.PowerEggMode
import com.openegg.app.drone.Px4Mode
import com.openegg.app.mavlink.MavDialect
import com.openegg.app.mavlink.MavlinkCodec
import com.openegg.app.mavlink.MavlinkFrameDetector
import com.openegg.app.mavlink.PowerVisionDialect
import com.openegg.app.mavlink.gen.MavAutopilot
import com.openegg.app.mavlink.gen.MavModeFlag
import com.openegg.app.mavlink.gen.MavResult
import com.openegg.app.mavlink.gen.MavState
import com.openegg.app.mavlink.gen.MavType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-process stand-in for the PowerEgg (PowerVision PX4 fork, MAVLink v1).
 * Used by unit tests and by the app's demo mode so the UI can be built without
 * the real aircraft. It models only behaviour we have evidence for in the
 * original SDK; anything else stays out until confirmed on hardware.
 */
class SimulatedVehicle(
    dialect: MavDialect = PowerVisionDialect.dialect,
    private val telemetryPeriodMs: Long = 200,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ByteTransport {

    override val description = "simulated PowerEgg"

    private val vehicleCodec = MavlinkCodec(dialect, systemId = 1, componentId = 1, version = 1)
    private val detector = MavlinkFrameDetector(dialect.byId.mapValues { it.value.crcExtra })
    private val toGcs = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending: ByteArray = ByteArray(0)
    private var job: Job? = null

    val params = mutableMapOf(
        "PV_V_KEY_FLAG" to Float.fromBits(0),
        "RTL_RETURN_ALT" to 30f,
        "GF_MAX_VER_DIST" to 120f,
        "PV_CAM_ISO" to Float.fromBits(0),
    )
    val receivedCommands = mutableListOf<Int>()
    val receivedCameraCommands = mutableListOf<Int>()
    @Volatile var customMode: Long = Px4Mode(Px4Mode.MAIN_POSCTL, 0).toCustomMode()
    @Volatile var armed = false
    @Volatile var silent = false

    // --- mission (docs/04 §3.4) -----------------------------------------------------------------
    /** One stored mission item, as the simulated autopilot keeps it. */
    data class SimMissionItem(
        val seq: Int, val frame: Int, val command: Int,
        val param1: Float, val param2: Float, val param3: Float, val param4: Float,
        val x: Float, val y: Float, val z: Float, val autocontinue: Int,
    )

    private val missionLock = Any()
    private var mission: List<SimMissionItem> = emptyList()
    private var incoming: Array<SimMissionItem?>? = null
    private var incomingExpected = 0
    /** Next item the simulated mission flies to (MISSION_CURRENT seq). */
    @Volatile var missionCurrentSeq = 0
        private set
    /** Telemetry ticks spent at each item before it is "reached". */
    @Volatile var missionTicksPerItem = 3
    private var missionTicks = 0
    /** Test hook: number of upcoming MISSION_ITEMs from the GCS to drop (simulates radio loss). */
    @Volatile var dropIncomingMissionItems = 0
    /** Test hook: number of upcoming MISSION_REQUESTs to the GCS to drop. */
    @Volatile var dropOutgoingMissionRequests = 0
    /** Messages names received from the GCS, in order (mission traffic only). */
    val receivedMissionMessages: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    val missionItems: List<SimMissionItem> get() = synchronized(missionLock) { mission }

    override suspend fun open() {
        job = scope.launch {
            var t = 0.0
            while (isActive) {
                if (!silent) {
                    val base = MavModeFlag.CUSTOM_MODE_ENABLED or (if (armed) MavModeFlag.SAFETY_ARMED else 0)
                    emit("HEARTBEAT", mapOf(
                        "type" to MavType.QUADROTOR, "autopilot" to MavAutopilot.PX4,
                        "base_mode" to base, "custom_mode" to customMode,
                        "system_status" to MavState.STANDBY, "mavlink_version" to 3,
                    ))
                    emit("ATTITUDE", mapOf("roll" to (0.05 * sin(t)).toFloat(), "pitch" to (0.03 * cos(t)).toFloat(), "yaw" to (t % 6.28).toFloat()))
                    emit("GLOBAL_POSITION_INT", mapOf(
                        "lat" to (-27.4698 * 1e7).toInt(), "lon" to (153.0251 * 1e7).toInt(),
                        "alt" to 25_000, "relative_alt" to 10_000, "hdg" to 9000,
                    ))
                    emit("GPS_RAW_INT", mapOf("fix_type" to 3, "satellites_visible" to 14, "eph" to 90))
                    emit("SYS_STATUS", mapOf("voltage_battery" to 16_800, "current_battery" to 520, "battery_remaining" to 87))
                    emit("VFR_HUD", mapOf("groundspeed" to 0.2f, "climb" to 0f))
                    emit("PV_INFOR", mapOf("status" to 0x80L, "land_lat" to -27.4698f, "land_lon" to 153.0251f, "land_alt" to 15f))
                    flyMission()
                }
                t += 0.1
                delay(telemetryPeriodMs)
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (pending.isEmpty()) pending = toGcs.receiveCatching().getOrNull() ?: return -1
        val n = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, n)
        pending = pending.copyOfRange(n, pending.size)
        return n
    }

    override suspend fun write(bytes: ByteArray) {
        for (frame in detector.feed(bytes)) {
            if (frame.crcValid == false) continue
            val msg = vehicleCodec.decode(frame) ?: continue
            when (msg.name) {
                "SET_MODE" -> {
                    // The firmware reports some PowerVision modes back as standard AUTO sub-modes.
                    val sent = Px4Mode.fromCustomMode(msg.long("custom_mode"))
                    customMode = (PowerEggMode.entries.firstOrNull { it.sent == sent }?.reported ?: sent).toCustomMode()
                }
                "COMMAND_LONG" -> {
                    val cmd = msg.int("command")
                    receivedCommands += cmd
                    emit("COMMAND_ACK", mapOf("command" to cmd, "result" to MavResult.ACCEPTED))
                }
                "PARAM_SET" -> {
                    val id = msg.string("param_id")
                    params[id] = msg.float("param_value")
                    emitParam(id, msg.int("param_type"))
                }
                "PARAM_REQUEST_READ" -> {
                    val id = msg.string("param_id")
                    if (id in params) emitParam(id, 9)
                }
                "PV_CAMERA_COMMAND" -> {
                    val cmd = msg.int("cmd")
                    receivedCameraCommands += cmd
                    emit("PV_CAMERA_COMMAND", mapOf("cmd" to cmd, "result" to 0))
                }
                "MISSION_CLEAR_ALL", "MISSION_COUNT", "MISSION_ITEM", "MISSION_REQUEST_LIST",
                "MISSION_REQUEST", "MISSION_ACK", "MISSION_SET_CURRENT" -> onMission(msg)
            }
        }
    }

    /** PX4-style mission protocol, autopilot side. */
    private fun onMission(msg: com.openegg.app.mavlink.MavMessage) = synchronized(missionLock) {
        receivedMissionMessages += msg.name
        when (msg.name) {
            "MISSION_CLEAR_ALL" -> {
                mission = emptyList(); incoming = null; missionCurrentSeq = 0; missionTicks = 0
                emitMissionAck(0)
            }
            "MISSION_COUNT" -> {
                val n = msg.int("count")
                missionTicks = 0
                if (n == 0) { mission = emptyList(); incoming = null; emitMissionAck(0) }
                else { incoming = arrayOfNulls(n); incomingExpected = 0; requestItem(0) }
            }
            "MISSION_ITEM" -> {
                val buf = incoming ?: return@synchronized
                if (dropIncomingMissionItems > 0) { dropIncomingMissionItems--; return@synchronized }
                val seq = msg.int("seq")
                when {
                    seq == incomingExpected -> {
                        buf[seq] = SimMissionItem(
                            seq, msg.int("frame"), msg.int("command"),
                            msg.float("param1"), msg.float("param2"), msg.float("param3"), msg.float("param4"),
                            msg.float("x"), msg.float("y"), msg.float("z"), msg.int("autocontinue"),
                        )
                        incomingExpected++
                        if (incomingExpected == buf.size) {
                            mission = buf.map { it!! }; incoming = null; missionCurrentSeq = 0
                            emitMissionAck(0)
                        } else requestItem(incomingExpected)
                    }
                    seq < incomingExpected -> requestItem(incomingExpected) // duplicate: ask again for the next
                    else -> emitMissionAck(13) // MAV_MISSION_INVALID_SEQUENCE
                }
            }
            "MISSION_REQUEST_LIST" -> emit("MISSION_COUNT", mapOf("target_system" to msg.systemId, "target_component" to msg.componentId, "count" to mission.size))
            "MISSION_REQUEST" -> mission.getOrNull(msg.int("seq"))?.let { i ->
                emit("MISSION_ITEM", mapOf(
                    "target_system" to msg.systemId, "target_component" to msg.componentId,
                    "seq" to i.seq, "frame" to i.frame, "command" to i.command, "current" to if (i.seq == missionCurrentSeq) 1 else 0,
                    "autocontinue" to i.autocontinue, "param1" to i.param1, "param2" to i.param2, "param3" to i.param3,
                    "param4" to i.param4, "x" to i.x, "y" to i.y, "z" to i.z,
                ))
            }
            "MISSION_SET_CURRENT" -> { missionCurrentSeq = msg.int("seq").coerceIn(0, maxOf(0, mission.size - 1)); missionTicks = 0 }
            "MISSION_ACK" -> Unit // end of a download
        }
    }

    private fun requestItem(seq: Int) {
        if (dropOutgoingMissionRequests > 0) { dropOutgoingMissionRequests--; return }
        emit("MISSION_REQUEST", mapOf("target_system" to 255, "target_component" to 0, "seq" to seq))
    }

    private fun emitMissionAck(type: Int) = emit("MISSION_ACK", mapOf("target_system" to 255, "target_component" to 0, "type" to type))

    /** While in AUTO/MISSION, "flies" one item every [missionTicksPerItem] ticks; then hovers (AUTO/LOITER), as PX4 does. */
    private fun flyMission() = synchronized(missionLock) {
        if (mission.isEmpty()) return@synchronized
        emit("MISSION_CURRENT", mapOf("seq" to missionCurrentSeq))
        if (customMode != PowerEggMode.WAYPOINTS.reported.toCustomMode()) return@synchronized
        if (++missionTicks < missionTicksPerItem) return@synchronized
        missionTicks = 0
        emit("MISSION_ITEM_REACHED", mapOf("seq" to missionCurrentSeq))
        if (missionCurrentSeq >= mission.lastIndex) {
            customMode = PowerEggMode.HOVER.reported.toCustomMode()
        } else {
            missionCurrentSeq++
            emit("MISSION_CURRENT", mapOf("seq" to missionCurrentSeq))
        }
    }

    private fun emitParam(id: String, type: Int) {
        emit("PARAM_VALUE", mapOf(
            "param_id" to id, "param_value" to params.getValue(id), "param_type" to type,
            "param_count" to params.size, "param_index" to params.keys.indexOf(id),
        ))
    }

    private fun emit(name: String, values: Map<String, Any>) {
        toGcs.trySend(vehicleCodec.encode(name, values))
    }

    override fun close() {
        job?.cancel()
        toGcs.close()
    }
}
