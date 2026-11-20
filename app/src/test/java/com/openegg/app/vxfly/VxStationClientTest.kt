package com.openegg.app.vxfly

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs on virtual time: the client's delays and the dead-man use the test scheduler's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class VxStationClientTest {

    private fun TestScope.client(
        station: SimulatedVxStation,
        unit: VxUnit = VxUnit.GROUND,
        poll: Boolean = unit == VxUnit.GROUND,
    ) = VxStationClient(
        host = if (unit == VxUnit.AIR) VxStationClient.AIR_HOST else VxStationClient.GROUND_HOST,
        transportFactory = station::transport,
        scope = backgroundScope,
        unit = unit,
        pollEnabled = poll,
        monotonicMs = { testScheduler.currentTime },
    ).also { it.start() }

    private suspend fun VxStationClient.await(what: (StationStatus) -> Boolean): StationStatus =
        withTimeout(30_000) { status.first(what) }

    @Test
    fun sendsLoginFirstAndBecomesLinked() = runTest {
        val station = SimulatedVxStation()
        val c = client(station)
        val s = c.await { it.loggedIn }
        assertTrue(s.connected && s.linked)
        assertNotNull(s.lastSeenAt)
        assertArrayEquals(byteArrayOf(0x1C, 0x00), station.received.first().encode())
    }

    @Test
    fun statusIsPopulatedAfterPoll() = runTest {
        val station = SimulatedVxStation()
        val c = client(station)
        val s = c.await { it.groundBatteryPercent != null && it.signalBars != null && it.swVersion != null }
        assertEquals(3.912, s.groundBatteryVolts!!, 1e-9)
        assertEquals(91.06, s.groundBatteryPercent!!, 1.0)
        assertEquals(-55, s.rssi); assertEquals(25, s.snr); assertEquals(-90, s.noise)
        assertEquals(4, s.signalBars)
        assertEquals("PV-FTA-S-V-SIM", s.swVersion)
        assertTrue("first poll at ~1 s", currentTime in 1000..1500)
        val cmds = station.received.map { it.cmd }
        assertTrue(VxCmd.GET_BATTERY_VOLTAGE in cmds && VxCmd.GET_RADIO_STATUS in cmds)
    }

    @Test
    fun pollRepeatsEveryFiveSecondsAndVersionOnlyOnce() = runTest {
        val station = SimulatedVxStation()
        client(station)
        delay(16_500) // polls at 1, 6, 11, 16 s
        val cmds = station.received.map { it.cmd }
        assertEquals(4, cmds.count { it == VxCmd.GET_BATTERY_VOLTAGE })
        assertEquals(4, cmds.count { it == VxCmd.GET_RADIO_STATUS })
        assertEquals(1, cmds.count { it == VxCmd.GET_SW_VERSION })
    }

    @Test
    fun handlesRepliesSplitIntoSingleBytes() = runTest {
        val station = SimulatedVxStation(maxReadChunk = 1)
        val c = client(station)
        val s = c.await { it.groundBatteryPercent != null && it.signalBars != null }
        assertEquals(4, s.signalBars)
    }

    @Test
    fun keepaliveIsOneSecondAfterLoginAnswered() = runTest {
        val station = SimulatedVxStation()
        val c = client(station, poll = false)
        c.await { it.loggedIn }
        delay(10_000)
        val n = station.received.count { it.cmd == VxCmd.KEEPALIVE }
        assertTrue("expected ~10 keepalives in 10 s, got $n", n in 8..11)
        assertTrue(c.status.value.linked)
    }

    @Test
    fun keepaliveIsTwoAndAHalfSecondsBeforeLogin() = runTest {
        val station = SimulatedVxStation().apply { answerLogin = false }
        val c = client(station, poll = false)
        c.await { it.linked } // keepalive echoes count as alive
        delay(10_000)
        val n = station.received.count { it.cmd == VxCmd.KEEPALIVE }
        assertTrue("expected ~4 keepalives in 10 s, got $n", n in 3..5)
        assertFalse(c.status.value.loggedIn)
    }

    @Test
    fun linkDropsWhenStationGoesSilentAndRecovers() = runTest {
        val station = SimulatedVxStation()
        val c = client(station)
        c.await { it.linked && it.signalBars != null && it.groundBatteryPercent != null }
        station.silent = true
        val t0 = currentTime
        val dropped = c.await { !it.linked }
        val dt = currentTime - t0
        assertTrue("dead-man fired after $dt ms", dt in 1500..5200)
        assertNull("radio fields cleared on drop", dropped.signalBars)
        assertNotNull("battery kept as last known", dropped.groundBatteryPercent)
        assertTrue(dropped.lastError!!.startsWith("no data"))

        // reconnect attempts continue while silent; nothing links
        delay(10_000)
        assertFalse(c.status.value.linked)
        assertTrue(station.connections >= 2)

        station.silent = false
        val back = c.await { it.linked && it.signalBars != null }
        assertEquals(4, back.signalBars)
    }

    @Test
    fun deadManIsFiveSecondsWhenNothingEverArrives() = runTest {
        val station = SimulatedVxStation().apply { silent = true }
        val c = client(station, poll = false)
        c.await { it.connected }
        val t0 = currentTime
        c.await { !it.connected }
        val dt = currentTime - t0
        assertTrue("dead-man fired after $dt ms", dt in 4900..5200)
        assertFalse(c.status.value.linked)
    }

    @Test
    fun reconnectsAfterRefusedAndDroppedConnections() = runTest {
        val station = SimulatedVxStation().apply { acceptConnections = false }
        val c = client(station)
        val failing = c.await { it.connectAttempts >= 2 }
        assertTrue(failing.lastError!!.startsWith("connect failed"))
        station.acceptConnections = true
        c.await { it.loggedIn }
        station.dropConnections()
        c.await { !it.connected }
        c.await { it.loggedIn }
        assertEquals(2, station.connections)
    }

    @Test
    fun failedBatteryReplyLeavesBatteryUnknown() = runTest {
        val station = SimulatedVxStation().apply { failCommands += VxCmd.GET_BATTERY_VOLTAGE }
        val c = client(station)
        val s = c.await { it.signalBars != null }
        delay(100)
        assertNull(c.status.value.groundBatteryPercent)
        assertNull(s.groundBatteryVolts)
    }

    @Test
    fun setDownlinkChannelSendsSetThenApply() = runTest {
        val station = SimulatedVxStation()
        val c = client(station, poll = false)
        c.await { it.loggedIn }
        station.clearReceived()
        val r = c.setDownlinkChannel(12)
        assertTrue(r.ok)
        val wire = station.received.filter { it.cmd != VxCmd.KEEPALIVE }.map { it.encode().toList() }
        assertEquals(listOf(listOf<Byte>(0x14, 0x02, 0x31, 0x32), listOf<Byte>(0x1B, 0x00)), wire)
        assertEquals(12, c.readDownlinkChannel())
    }

    @Test
    fun failedSetDownlinkChannelIsNotApplied() = runTest {
        val station = SimulatedVxStation().apply { failCommands += VxCmd.SET_DL_CHANNEL }
        val c = client(station, poll = false)
        c.await { it.loggedIn }
        val r = c.setDownlinkChannel(9)
        assertTrue(r.set is VxReply.Failed)
        assertNull(r.apply)
        assertFalse(r.ok)
        assertFalse(station.received.any { it.cmd == VxCmd.APPLY_DL_CONFIG })
    }

    @Test
    fun failedApplyIsReported() = runTest {
        val station = SimulatedVxStation().apply { failCommands += VxCmd.APPLY_DL_CONFIG }
        val c = client(station, poll = false)
        c.await { it.loggedIn }
        val r = c.setDownlinkChannel(9)
        assertTrue(r.set is VxReply.Ok)
        assertTrue(r.apply is VxReply.Failed)
        assertFalse(r.ok)
    }

    @Test
    fun setVideoRateOnAirUnit() = runTest {
        val station = SimulatedVxStation()
        val c = client(station, unit = VxUnit.AIR)
        c.await { it.loggedIn }
        assertTrue(c.setVideoRateMbps(2).isOk)
        assertEquals(2, station.videoRateMbps)
        assertArrayEquals(byteArrayOf(0x1A, 0x01, 0x32), station.received.last { it.cmd == VxCmd.SET_VIDEO_RATE }.encode())
        assertEquals(2, c.readVideoRateMbps())
        // the air client does not poll ground battery
        delay(6_000)
        assertFalse(station.received.any { it.cmd == VxCmd.GET_BATTERY_VOLTAGE })
    }

    @Test
    fun failedVideoRateIsReported() = runTest {
        val station = SimulatedVxStation().apply { failCommands += VxCmd.SET_VIDEO_RATE }
        val c = client(station, unit = VxUnit.AIR)
        c.await { it.loggedIn }
        assertTrue(c.setVideoRateMbps(3) is VxReply.Failed)
    }

    @Test
    fun unansweredRequestTimesOut() = runTest {
        val station = SimulatedVxStation().apply { ignoreCommands += VxCmd.GET_DL_CHANNEL }
        val c = client(station, poll = false)
        c.await { it.loggedIn }
        val t0 = currentTime
        assertEquals(VxReply.Timeout, c.query(VxCmd.GET_DL_CHANNEL))
        assertTrue(currentTime - t0 in 2000L..2010L)
        assertTrue("link survives a missed reply", c.status.value.linked)
    }

    @Test
    fun requestWithoutSessionIsNotConnected() = runTest {
        val station = SimulatedVxStation().apply { acceptConnections = false }
        val c = client(station)
        assertEquals(VxReply.NotConnected, c.query(VxCmd.GET_DL_CHANNEL))
    }

    @Test
    fun requestInFlightWhenLinkDropsIsNotConnected() = runTest {
        val station = SimulatedVxStation().apply { ignoreCommands += VxCmd.GET_DL_CHANNEL }
        val c = client(station, poll = false)
        c.await { it.loggedIn }
        backgroundScope.launch { delay(500); station.dropConnections() }
        assertEquals(VxReply.NotConnected, c.query(VxCmd.GET_DL_CHANNEL))
    }

    @Test
    fun rejectsUnsafeOrOutOfRangeRequests() = runTest {
        val station = SimulatedVxStation()
        val ground = client(station, poll = false)
        val air = client(station, unit = VxUnit.AIR)
        assertThrows<IllegalArgumentException> { ground.query(VxCmd.CU_REBOOT) }
        assertThrows<IllegalArgumentException> { ground.query(VxCmd.BIND_DL) }
        assertThrows<IllegalArgumentException> { ground.setDownlinkChannel(5) }
        assertThrows<IllegalArgumentException> { ground.setDownlinkChannel(20) }
        assertThrows<IllegalArgumentException> { air.setVideoRateMbps(0) }
        assertThrows<IllegalArgumentException> { air.setVideoRateMbps(5) }
        assertThrows<IllegalStateException> { air.setDownlinkChannel(12) }
        assertThrows<IllegalStateException> { ground.setVideoRateMbps(3) }
        assertFalse(station.received.any { it.cmd in VxCmd.DANGEROUS })
    }

    @Test
    fun stopClosesTheSession() = runTest {
        val station = SimulatedVxStation()
        val c = client(station)
        c.await { it.loggedIn }
        c.stop()
        assertFalse(c.status.value.connected)
        val before = station.received.size
        delay(10_000)
        assertEquals(before, station.received.size)
    }

    private suspend inline fun <reified T : Throwable> assertThrows(crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }
}
