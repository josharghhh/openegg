package com.openegg.app.camera

import com.openegg.app.drone.DroneCommands
import com.openegg.app.mavlink.MavField
import com.openegg.app.mavlink.MavMessageDef
import com.openegg.app.mavlink.MavType
import com.openegg.app.mavlink.MavlinkLink

/**
 * The few link operations the camera/gimbal repository needs. Production code uses
 * [DroneCameraPort]; tests substitute a fake.
 */
interface CameraParamPort {
    /** PARAM_REQUEST_READ → PARAM_VALUE, decoded from raw bits. Null on timeout / no vehicle. */
    suspend fun readInt(name: String): Int?

    /** PARAM_SET (INT32) → echoed PARAM_VALUE, decoded from raw bits. Null on timeout / no vehicle. */
    suspend fun writeInt(name: String, value: Int): Int?

    /** PV_CAMERA_COMMAND (msg 160); returns the aircraft's result byte, or null on timeout. */
    suspend fun cameraCommand(cmd: Int): Int?

    /** Fire-and-forget MAVLink message. False if not connected or the dialect lacks [name]. */
    suspend fun send(name: String, values: Map<String, Any>): Boolean

    /** Vehicle sysid/compid from the HEARTBEAT, or null when no vehicle is seen. */
    val vehicleIds: Pair<Int, Int>?
}

/** [CameraParamPort] over the app's real [DroneCommands] + [MavlinkLink]. */
class DroneCameraPort(private val commands: DroneCommands, private val link: MavlinkLink) : CameraParamPort {
    override suspend fun readInt(name: String) = commands.readIntParameter(name)
    override suspend fun writeInt(name: String, value: Int) = commands.writeIntParameter(name, value)
    override suspend fun cameraCommand(cmd: Int) = commands.camera(cmd)
    override suspend fun send(name: String, values: Map<String, Any>) = link.send(name, values)
    override val vehicleIds get() = link.vehicle?.let { it.systemId to it.componentId }
}

/**
 * MOUNT_CONFIGURE (156) as the original SDK encoded it (doc 06 §2.2: len 6, CRC_EXTRA 19, target 1/1).
 * It is NOT in [com.openegg.app.mavlink.PowerVisionDialect] yet, so sending it is a silent no-op until the
 * lead adds this def to the dialect. Field order is ArduPilot's; doc 06 flags the stab_* argument order as
 * unverified.
 */
val MOUNT_CONFIGURE_DEF = MavMessageDef(
    156, "MOUNT_CONFIGURE",
    listOf(
        MavField("target_system", MavType.UINT8),
        MavField("target_component", MavType.UINT8),
        MavField("mount_mode", MavType.UINT8),
        MavField("stab_roll", MavType.UINT8),
        MavField("stab_pitch", MavType.UINT8),
        MavField("stab_yaw", MavType.UINT8),
    ),
    crcExtra = 19,
)
