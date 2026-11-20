package com.openegg.app.mavlink

import com.openegg.app.mavlink.gen.CommonDialect

/**
 * PowerVision's MAVLink v1 dialect for the original PowerEgg, reconstructed from
 * libpvsdk_api-release.so (see docs/04-mavlink-px4-protocol.md and
 * docs/06-poweregg-camera-gimbal.md).
 *
 * CRC_EXTRA values are the ones in the firmware SDK's own table (file offset
 * 0x162873 in the 1.7.3 build). Where our field names reproduce that CRC the
 * definition is verified; otherwise the CRC is pinned and the names are ours.
 */
object PowerVisionDialect {

    /** Custom PowerEgg messages. */
    val PV_INFOR = MavMessageDef(
        159, "PV_INFOR",
        listOf(
            MavField("status", MavType.UINT64),
            MavField("land_lat", MavType.FLOAT),
            MavField("land_lon", MavType.FLOAT),
            MavField("land_alt", MavType.FLOAT),
        ),
        crcExtra = 174,
    )

    /** Camera actions (GCS → aircraft) and their results (aircraft → GCS). Field names are ours; CRC pinned. */
    val PV_CAMERA_COMMAND = MavMessageDef(
        160, "PV_CAMERA_COMMAND",
        listOf(MavField("cmd", MavType.UINT8), MavField("result", MavType.UINT8)),
        crcExtra = 133,
    )

    val HELM_CONTROL = MavMessageDef(
        189, "HELM_CONTROL",
        listOf(MavField("mode", MavType.UINT8), MavField("expect", MavType.UINT8)),
        crcExtra = 108,
    )

    /** ArduPilot-dialect gimbal messages the PowerEgg uses (ids 157/158 conflict with nothing in common.xml). */
    val MOUNT_CONTROL = MavMessageDef(
        157, "MOUNT_CONTROL",
        listOf(
            MavField("target_system", MavType.UINT8),
            MavField("target_component", MavType.UINT8),
            MavField("input_a", MavType.INT32),
            MavField("input_b", MavType.INT32),
            MavField("input_c", MavType.INT32),
            MavField("save_position", MavType.UINT8),
        ),
        crcExtra = 21,
    )

    val MOUNT_STATUS = MavMessageDef(
        158, "MOUNT_STATUS",
        listOf(
            MavField("target_system", MavType.UINT8),
            MavField("target_component", MavType.UINT8),
            MavField("pointing_a", MavType.INT32),
            MavField("pointing_b", MavType.INT32),
            MavField("pointing_c", MavType.INT32),
        ),
        crcExtra = 134,
    )

    val custom = MavDialect(listOf(PV_INFOR, PV_CAMERA_COMMAND, HELM_CONTROL, MOUNT_CONTROL, MOUNT_STATUS))

    /** Everything the PowerEgg link understands: common.xml plus the PowerVision additions. */
    val dialect: MavDialect by lazy { CommonDialect.dialect + custom }

    /**
     * The aircraft SDK's CRC table disagrees with upstream for a few ids (e.g. HOME_POSITION
     * is 0 in its table, EXTENDED_SYS_STATE is 245). Accept either value on receive.
     */
    val alternateCrcExtras: Map<Int, Set<Int>> = mapOf(242 to setOf(0), 245 to setOf(245))

    fun detector() = MavlinkFrameDetector(dialect.byId.mapValues { it.value.crcExtra }, alternateCrcExtras)

    /** Camera command codes for [PV_CAMERA_COMMAND] (doc 06). */
    object CameraCmd {
        const val TAKE_PHOTO = 1
        const val RECORD_START = 2
        const val RECORD_STOP = 3
        const val PHOTO_MODE = 4
        const val VIDEO_MODE = 5
        const val FORMAT_SD = 6
        const val FACTORY_RESET = 7
    }
}
