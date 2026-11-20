package com.openegg.app.mavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerVisionDialectTest {

    /**
     * CRC_EXTRA table copied from libpvsdk_api-release.so (1.7.3, file offset 0x162873) for the ids
     * we use. Standard ids double as a check that the table was located correctly.
     */
    private val firmwareTable = mapOf(
        0 to 50, 1 to 124, 22 to 220, 23 to 168, 24 to 24, 30 to 39, 33 to 104, 74 to 20, 76 to 152, 77 to 143, 253 to 83,
        157 to 21, 158 to 134, 159 to 174, 160 to 133, 189 to 108,
    )

    @Test
    fun dialectCrcExtrasMatchFirmwareTable() {
        val d = PowerVisionDialect.dialect
        for ((id, crc) in firmwareTable) assertEquals("id $id", crc, d[id]!!.crcExtra)
    }

    @Test
    fun reconstructedFieldListsReproduceFirmwareCrc() {
        // These definitions were rebuilt from the decompile; reproducing the firmware CRC proves name+type+order.
        for (def in listOf(PowerVisionDialect.PV_INFOR, PowerVisionDialect.HELM_CONTROL, PowerVisionDialect.MOUNT_CONTROL, PowerVisionDialect.MOUNT_STATUS)) {
            assertEquals(def.name, def.crcExtra, def.computeCrcExtra())
        }
    }

    @Test
    fun payloadLengthsMatchFirmware() {
        assertEquals(20, PowerVisionDialect.PV_INFOR.baseLength)
        assertEquals(2, PowerVisionDialect.PV_CAMERA_COMMAND.baseLength)
        assertEquals(2, PowerVisionDialect.HELM_CONTROL.baseLength)
    }

    @Test
    fun cameraCommandFrameMatchesOriginalSdk() {
        // Take-photo frame as the original SDK builds it: sys 255, comp 190, payload [cmd=1, 0].
        val codec = MavlinkCodec(PowerVisionDialect.dialect, systemId = 255, componentId = 190, version = 1)
        val frame = codec.encode("PV_CAMERA_COMMAND", mapOf("cmd" to 1, "result" to 0))
        assertEquals(listOf(0xFE, 2, 0, 255, 190, 160, 1, 0), frame.take(8).map { it.toInt() and 0xFF })
        val parsed = PowerVisionDialect.detector().feed(frame).single()
        assertEquals(true, parsed.crcValid)
    }

    @Test
    fun acceptsFirmwareAlternateCrcForHomePosition() {
        val def = PowerVisionDialect.dialect["HOME_POSITION"]
        val codec = MavlinkCodec(PowerVisionDialect.dialect, systemId = 1, componentId = 1, version = 1)
        val std = codec.encode(def, mapOf("latitude" to 1, "longitude" to 2, "altitude" to 3))
        // Re-sign the same frame with CRC_EXTRA 0, as the firmware's table would.
        val body = std.copyOfRange(1, std.size - 2)
        val crc = X25.of(body, 0)
        val alt = byteArrayOf(0xFE.toByte()) + body + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        assertTrue(PowerVisionDialect.detector().feed(std).single().crcValid == true)
        assertTrue(PowerVisionDialect.detector().feed(alt).single().crcValid == true)
    }
}
