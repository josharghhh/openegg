package com.openegg.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingDefTest {

    @Test
    fun intBitsRoundTripThroughFloat() {
        for (v in listOf(0, 1, 7, 13, 204_799, 204_800, -1, Int.MAX_VALUE, Int.MIN_VALUE, 0x7FC00001)) {
            assertEquals(v, ParamBits.decode(ParamBits.encode(v)))
        }
        // The value is the raw bit pattern, not a numeric conversion.
        assertEquals(Float.fromBits(1), ParamBits.encode(1))
        assertTrue(ParamBits.encode(1) != 1f)
    }

    @Test
    fun paramNamesAreUniqueAndPrefixed() {
        val names = CameraSettings.all.map { it.param }
        assertEquals(names.size, names.toSet().size)
        CameraSettings.camera.forEach { assertTrue(it.param, it.param.startsWith("PV_CAM_")) }
        CameraSettings.gimbal.forEach { assertTrue(it.param, it.param.startsWith("PV_GIM_")) }
    }

    @Test
    fun everyDocumentedCameraSettingIsPresent() {
        val expected = setOf(
            "PV_CAM_ISO", "PV_CAM_EV", "PV_CAM_WB", "PV_CAM_LT", "PV_CAM_STYLE", "PV_CAM_ACUT", "PV_CAM_SCENE_M",
            "PV_CAM_L_FREQ", "PV_CAM_P_SIZE", "PV_CAM_LONG_EXP", "PV_CAM_V_SIZE", "PV_CAM_TV_MODE",
            "PV_CAM_SD_LEFT", "PV_CAM_SD_CAP", "PV_CAM_STAT",
        )
        assertEquals(expected, CameraSettings.camera.map { it.param }.toSet())
    }

    @Test
    fun noDangerousParamsInTable() {
        val forbidden = setOf("PV_GIM_RESET_PM", "PV_GIM_AUTO_CAL", "PV_GIM_P_30", "PV_CAM_FORMAT", "PV_CAM_REQ_ALL")
        assertTrue(CameraSettings.all.none { it.param in forbidden })
    }

    @Test
    fun readOnlySettingsRejectWrites() {
        val ro = CameraSettings.all.filter { it.readOnly }.map { it.param }.toSet()
        assertEquals(setOf("PV_CAM_SD_LEFT", "PV_CAM_SD_CAP", "PV_CAM_STAT"), ro)
        assertFalse(CameraSettings.SD_LEFT.accepts(100))
        assertFalse(CameraSettings.STATUS.accepts(1))
    }

    @Test
    fun optionTablesMatchDoc06() {
        assertEquals((1..6).toList(), CameraSettings.ISO.options.map { it.value })
        assertEquals("Auto", CameraSettings.ISO.labelFor(1))
        assertEquals("1600", CameraSettings.ISO.labelFor(6))
        assertEquals((1..13).toList(), CameraSettings.EV.options.map { it.value })
        assertEquals("0.0", CameraSettings.EV.labelFor(7))
        assertEquals("-2.0", CameraSettings.EV.labelFor(1))
        assertEquals("+2.0", CameraSettings.EV.labelFor(13))
        assertEquals(7, CameraSettings.WHITE_BALANCE.options.size)
        assertEquals(3, CameraSettings.METERING.options.size)
        assertEquals(7, CameraSettings.STYLE.options.size)
        assertEquals(3, CameraSettings.SHARPNESS.options.size)
        assertEquals(7, CameraSettings.SCENE.options.size)
        assertEquals(listOf(1, 2, 3), CameraSettings.ANTI_FLICKER.options.map { it.value })
        assertEquals(listOf(2, 3, 4, 5), CameraSettings.PHOTO_SIZE.options.map { it.value })
        assertFalse("photo size has no code 1", CameraSettings.PHOTO_SIZE.accepts(1))
        assertEquals((1..9).toList(), CameraSettings.LONG_EXPOSURE.options.map { it.value })
        assertEquals("1/30 s", CameraSettings.LONG_EXPOSURE.labelFor(2))
        assertEquals("4K 30fps", CameraSettings.VIDEO_SIZE.labelFor(1))
        assertEquals("PAL", CameraSettings.TV_SYSTEM.labelFor(2))
        CameraSettings.all.filter { it.isOptionList }.forEach { def ->
            assertEquals(def.param, def.options.size, def.options.map { it.value }.toSet().size)
        }
    }

    @Test
    fun unknownCodesAreVisible() {
        assertEquals("Code 42", CameraSettings.ISO.labelFor(42))
        assertEquals("—", CameraSettings.ISO.labelFor(null))
        assertEquals("Photo mode", CameraSettings.STATUS.labelFor(1))
        assertEquals("Recording", CameraSettings.STATUS.labelFor(3))
        assertEquals("Code 2", CameraSettings.STATUS.labelFor(2))
    }

    @Test
    fun gimbalRangesMatchOriginalSeekBars() {
        assertEquals(6..100, CameraSettings.GIMBAL_PITCH_SPEED.range)
        assertEquals(6..100, CameraSettings.GIMBAL_YAW_SPEED.range)
        assertEquals(1..30, CameraSettings.GIMBAL_PITCH_SMOOTHING.range)
        assertEquals(1..30, CameraSettings.GIMBAL_YAW_SMOOTHING.range)
        assertTrue(CameraSettings.GIMBAL_PITCH_SMOOTHING.accepts(30))
        assertFalse(CameraSettings.GIMBAL_PITCH_SMOOTHING.accepts(31))
        assertFalse(CameraSettings.GIMBAL_PITCH_SPEED.accepts(5))
        assertEquals("50", CameraSettings.GIMBAL_PITCH_SPEED.labelFor(50))
        assertEquals(listOf(1, 2), CameraSettings.GIMBAL_MODE.options.map { it.value })
    }

    @Test
    fun sdCardFormatting() {
        assertEquals("No SD card", SdCard.format(204_800))
        assertEquals("No SD card", SdCard.format(-1))
        assertEquals("512 MB", SdCard.format(512))
        assertEquals("29.7 GB", SdCard.format(30_436))
        assertTrue(SdCard.isPresent(0))
        assertFalse(SdCard.isPresent(null))
        assertEquals("29.7 GB", CameraSettings.SD_CAPACITY.labelFor(30_436))
    }

    @Test
    fun simulatorDefaultsAreValidAndBitEncoded() {
        val d = CameraSettings.simulatorDefaults()
        assertEquals(CameraSettings.all.map { it.param }.toSet(), d.keys)
        d.forEach { (name, raw) ->
            val def = CameraSettings.byParam(name)
            assertNotNull(name, def)
            def!!
            val v = ParamBits.decode(raw)
            if (!def.readOnly) assertTrue("$name=$v", def.accepts(v))
        }
    }

    @Test
    fun nudgeMappingUsesOriginalMagnitudes() {
        assertEquals(0, GimbalNudges.forDeflection(0f).pitchRate)
        assertEquals(0, GimbalNudges.forDeflection(0.1f).pitchRate)
        assertEquals(-400, GimbalNudges.forDeflection(-0.3f).pitchRate)
        assertEquals(-800, GimbalNudges.forDeflection(-1f).pitchRate)
        assertEquals(500, GimbalNudges.forDeflection(0.3f).pitchRate)
        assertEquals(1000, GimbalNudges.forDeflection(0.9f).pitchRate)
        assertEquals(0, GimbalNudges.forDeflection(Float.NaN).pitchRate)
    }

    @Test
    fun mountConfigureDefMatchesSdkCrc() {
        assertEquals(19, MOUNT_CONFIGURE_DEF.computeCrcExtra())
        assertEquals(6, MOUNT_CONFIGURE_DEF.baseLength)
    }
}
