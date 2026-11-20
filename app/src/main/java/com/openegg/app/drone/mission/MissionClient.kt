package com.openegg.app.drone.mission

import com.openegg.app.drone.DroneCommands
import com.openegg.app.drone.PowerEggMode
import com.openegg.app.drone.Px4Mode
import com.openegg.app.mavlink.MavMessage
import com.openegg.app.mavlink.MavlinkLink
import com.openegg.app.mavlink.gen.MavCmd
import com.openegg.app.mavlink.gen.MavMissionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the mission client is doing, for the UI. */
data class MissionProgress(
    val phase: Phase = Phase.IDLE,
    /** Items sent so far / total during an upload or received during a download. */
    val transferDone: Int = 0,
    val transferTotal: Int = 0,
    /** The mission currently on the aircraft (as uploaded by us, or downloaded). */
    val onboard: Mission? = null,
    /** True when [onboard] was read back from the aircraft and matched item for item. */
    val verified: Boolean = false,
    /** 0-based waypoint index from MISSION_CURRENT. */
    val currentWaypoint: Int? = null,
    val reachedWaypoints: Set<Int> = emptySet(),
    val error: String? = null,
) {
    enum class Phase { IDLE, CLEARING, UPLOADING, VERIFYING, UPLOADED, DOWNLOADING, STARTING, RUNNING, PAUSED, FINISHED, ABORTED, FAILED }

    val waypointCount get() = onboard?.size ?: 0
    val isFlying get() = phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.PAUSED
}

/**
 * Mission upload/download and execution on the PowerEgg (docs/04 §3.4, OBSERVED WaypointManager):
 *  - upload: MISSION_CLEAR_ALL, MISSION_COUNT(n), answer each MISSION_REQUEST(seq) with MISSION_ITEM(seq),
 *    finished by MISSION_ACK; 2000 ms watchdog that resends COUNT (no request yet) or the last item;
 *  - download: MISSION_REQUEST_LIST -> MISSION_COUNT -> MISSION_REQUEST(seq) each -> MISSION_ACK(0);
 *  - start/resume = SET_MODE WAYPOINTS (sent AUTO main 4/sub 0, reported AUTO/MISSION) = `startWayPoints`;
 *  - progress from MISSION_CURRENT (missionRunNotify(0, seq)) and MISSION_ITEM_REACHED (missionRunNotify(1, seq)).
 *
 * Differences from the original (deliberate):
 *  - the original resent "up to +0x20" times with the same watchdog for every step; we use [maxRetries] per step;
 *  - we wait for the MISSION_CLEAR_ALL ack (best effort) so it can't be confused with the final upload ack;
 *  - we optionally read the mission back to verify it ([upload] `verify`);
 *  - pause = HOVER (AUTO/LOITER). The original used `backToLastMode` (back to POSCTL/MANUAL);
 *  - the original did NOT echo MISSION_CLEAR_ALL on MISSION_ITEM_REACHED to us — see docs/04, not copied.
 */
class MissionClient(
    private val link: MavlinkLink,
    private val commands: DroneCommands = DroneCommands(link),
    private val requestTimeoutMs: Long = 2000,
    private val maxRetries: Int = 5,
    private val encoderOptions: MissionEncoder.Options = MissionEncoder.Options(),
) {
    sealed interface Result {
        data object Ok : Result
        data class Invalid(val issues: List<MissionIssue>) : Result
        data class Rejected(val ackType: Int, val reason: String) : Result
        data class Failed(val reason: String) : Result
        data object NoVehicle : Result
    }

    private val _progress = MutableStateFlow(MissionProgress())
    val progress: StateFlow<MissionProgress> = _progress.asStateFlow()

    /** Items last uploaded/downloaded, for seq -> waypoint mapping during flight. */
    @Volatile private var onboardItems: List<MissionItem> = emptyList()
    private val transferLock = Mutex()
    private var monitorJob: Job? = null

    // --- upload -------------------------------------------------------------------------------

    suspend fun upload(
        mission: Mission,
        verify: Boolean = true,
        limits: MissionLimits = MissionLimits(),
        homeLatitude: Double? = null,
        homeLongitude: Double? = null,
    ): Result = transferLock.withLock {
        val issues = MissionValidator.validate(mission, limits, homeLatitude, homeLongitude)
        if (issues.isNotEmpty()) return@withLock Result.Invalid(issues)
        val v = link.vehicle ?: return@withLock fail(Result.NoVehicle, "No aircraft connected.")
        val items = MissionEncoder.encode(mission, encoderOptions)
        val sys = v.systemId
        val comp = v.componentId

        _progress.value = MissionProgress(MissionProgress.Phase.CLEARING, 0, items.size)
        val result = exchange(sys) { inbox -> runUpload(inbox, items, sys, comp) }
        if (result != Result.Ok) return@withLock fail(result, describe(result))

        onboardItems = items
        _progress.update { it.copy(phase = MissionProgress.Phase.UPLOADED, transferDone = items.size, onboard = mission, verified = false) }
        if (!verify) return@withLock Result.Ok

        _progress.update { it.copy(phase = MissionProgress.Phase.VERIFYING) }
        val readBack = downloadItems(sys, comp)
            ?: return@withLock fail(Result.Failed("Verification failed: could not read the mission back."), "Verification failed: could not read the mission back.")
        val mismatch = when {
            readBack.size != items.size -> "aircraft holds ${readBack.size} items, expected ${items.size}"
            else -> items.indices.firstOrNull { !items[it].sameAs(readBack[it]) }?.let { "item ${it + 1} differs" }
        }
        if (mismatch != null) return@withLock fail(Result.Failed("Verification failed: $mismatch."), "Verification failed: $mismatch.")
        _progress.update { it.copy(phase = MissionProgress.Phase.UPLOADED, onboard = mission, verified = true, error = null) }
        Result.Ok
    }

    private suspend fun runUpload(inbox: Channel<MavMessage>, items: List<MissionItem>, sys: Int, comp: Int): Result {
        // 1. MISSION_CLEAR_ALL; PX4 answers with MISSION_ACK. Proceed even without it (the original didn't wait).
        link.send("MISSION_CLEAR_ALL", mapOf("target_system" to sys, "target_component" to comp))
        withTimeoutOrNull(requestTimeoutMs) { while (inbox.receive().name != "MISSION_ACK") { /* skip */ } }

        // 2. MISSION_COUNT, then serve requests.
        _progress.update { it.copy(phase = MissionProgress.Phase.UPLOADING) }
        val sendCount: suspend () -> Boolean = {
            link.send("MISSION_COUNT", mapOf("target_system" to sys, "target_component" to comp, "count" to items.size))
        }
        var resendLast: suspend () -> Boolean = sendCount
        if (!sendCount()) return Result.Failed("Link not connected.")
        var highestSent = -1
        var retries = 0
        while (true) {
            val m = withTimeoutOrNull(requestTimeoutMs) { inbox.receive() }
            if (m == null) {
                if (++retries > maxRetries) {
                    return Result.Failed(
                        if (highestSent < 0) "Aircraft did not request any waypoint (timeout)."
                        else "Upload timed out after waypoint item ${highestSent + 1} of ${items.size}.",
                    )
                }
                resendLast()
                continue
            }
            when (m.name) {
                "MISSION_REQUEST", "MISSION_REQUEST_INT" -> {
                    val seq = m.int("seq")
                    val item = items.getOrNull(seq) ?: return Result.Failed("Aircraft requested unknown item $seq.")
                    retries = 0
                    val asInt = m.name == "MISSION_REQUEST_INT"
                    resendLast = {
                        if (asInt) link.send("MISSION_ITEM_INT", item.toMissionItemIntValues(sys, comp))
                        else link.send("MISSION_ITEM", item.toMissionItemValues(sys, comp))
                    }
                    resendLast()
                    if (seq > highestSent) highestSent = seq
                    _progress.update { it.copy(transferDone = highestSent + 1) }
                }
                "MISSION_ACK" -> {
                    val type = m.int("type")
                    if (type == MavMissionResult.MAV_MISSION_ACCEPTED && highestSent == items.lastIndex) return Result.Ok
                    if (type == MavMissionResult.MAV_MISSION_ACCEPTED) continue // stray ack (e.g. a late CLEAR_ALL ack)
                    return Result.Rejected(type, ackReason(type))
                }
            }
        }
    }

    // --- download / clear ---------------------------------------------------------------------

    /** Reads the aircraft's mission. Null on failure (see [progress] error). */
    suspend fun download(): Mission? = transferLock.withLock {
        val v = link.vehicle ?: run { fail(Result.NoVehicle, "No aircraft connected."); return@withLock null }
        _progress.update { it.copy(phase = MissionProgress.Phase.DOWNLOADING, transferDone = 0, transferTotal = 0, error = null) }
        val items = downloadItems(v.systemId, v.componentId)
            ?: run { fail(Result.Failed("Download failed."), "Download failed (timeout)."); return@withLock null }
        val mission = MissionEncoder.decode(items)
        onboardItems = items.mapIndexed { i, it -> it.copy(waypointIndex = waypointIndexOf(items, i)) }
        _progress.update { it.copy(phase = MissionProgress.Phase.UPLOADED, onboard = mission, verified = true, reachedWaypoints = emptySet(), currentWaypoint = null) }
        mission
    }

    /** MISSION_CLEAR_ALL, confirmed by MISSION_ACK. */
    suspend fun clear(): Boolean = transferLock.withLock {
        val v = link.vehicle ?: return@withLock false
        val ok = exchange(v.systemId) { inbox ->
            repeat(maxRetries) {
                link.send("MISSION_CLEAR_ALL", mapOf("target_system" to v.systemId, "target_component" to v.componentId))
                val ack = withTimeoutOrNull(requestTimeoutMs) {
                    var m: MavMessage; do { m = inbox.receive() } while (m.name != "MISSION_ACK"); m
                }
                if (ack != null) return@exchange ack.int("type") == MavMissionResult.MAV_MISSION_ACCEPTED
            }
            false
        }
        if (ok) {
            onboardItems = emptyList()
            _progress.value = MissionProgress()
        }
        ok
    }

    private suspend fun downloadItems(sys: Int, comp: Int): List<MissionItem>? = exchange(sys) { inbox ->
        var count: Int? = null
        for (attempt in 0..maxRetries) {
            link.send("MISSION_REQUEST_LIST", mapOf("target_system" to sys, "target_component" to comp))
            count = withTimeoutOrNull(requestTimeoutMs) {
                var m: MavMessage; do { m = inbox.receive() } while (m.name != "MISSION_COUNT"); m.int("count")
            }
            if (count != null) break
        }
        val n = count ?: return@exchange null
        _progress.update { it.copy(transferTotal = n, transferDone = 0) }
        val out = ArrayList<MissionItem>(n)
        var seq = 0
        var retries = 0
        while (seq < n) {
            link.send("MISSION_REQUEST", mapOf("target_system" to sys, "target_component" to comp, "seq" to seq))
            val item = withTimeoutOrNull(requestTimeoutMs) {
                var m: MavMessage; do { m = inbox.receive() } while (!(m.name == "MISSION_ITEM" || m.name == "MISSION_ITEM_INT")); m
            }
            // OBSERVED: an item with the wrong seq is re-requested.
            if (item == null || item.int("seq") != seq) {
                if (++retries > maxRetries) return@exchange null
                continue
            }
            retries = 0
            out += MissionItem.fromMessage(item)
            seq++
            _progress.update { it.copy(transferDone = seq) }
        }
        link.send("MISSION_ACK", mapOf("target_system" to sys, "target_component" to comp, "type" to MavMissionResult.MAV_MISSION_ACCEPTED))
        out
    }

    // --- execution ----------------------------------------------------------------------------

    /** Starts the uploaded mission (SET_MODE WAYPOINTS). Call [monitor] first to get progress. */
    suspend fun start(): DroneCommands.Result {
        if (progress.value.onboard == null) return DroneCommands.Result.Rejected(-1)
        val before = progress.value.phase
        // STARTING already counts as flying so early MISSION_ITEM_REACHED messages aren't lost.
        _progress.update { it.copy(phase = MissionProgress.Phase.STARTING, reachedWaypoints = emptySet(), currentWaypoint = null, error = null) }
        return commands.setMode(PowerEggMode.WAYPOINTS).also { r ->
            _progress.update {
                when {
                    it.phase != MissionProgress.Phase.STARTING -> it // e.g. already FINISHED
                    r == DroneCommands.Result.Ok -> it.copy(phase = MissionProgress.Phase.RUNNING)
                    else -> it.copy(phase = before, error = "Aircraft did not enter waypoint mode ($r).")
                }
            }
        }
    }

    /** Hover in place (AUTO/LOITER); [resume] continues from the current item. */
    suspend fun pause(): DroneCommands.Result = commands.setMode(PowerEggMode.HOVER).also { r ->
        if (r == DroneCommands.Result.Ok) _progress.update { if (it.isFlying) it.copy(phase = MissionProgress.Phase.PAUSED) else it }
    }

    suspend fun resume(): DroneCommands.Result = commands.setMode(PowerEggMode.WAYPOINTS).also { r ->
        if (r == DroneCommands.Result.Ok) _progress.update { it.copy(phase = MissionProgress.Phase.RUNNING) }
    }

    /** Abort = return home. */
    suspend fun abort(): DroneCommands.Result = commands.returnToHome().also { r ->
        if (r == DroneCommands.Result.Ok) _progress.update { if (it.isFlying) it.copy(phase = MissionProgress.Phase.ABORTED) else it }
    }

    /**
     * Follows MISSION_CURRENT / MISSION_ITEM_REACHED and the heartbeat mode. Idempotent.
     * Finished = the last waypoint item was reached.
     */
    fun monitor(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            link.messages.collect { m -> onMessage(m) }
        }
    }

    fun stopMonitoring() { monitorJob?.cancel(); monitorJob = null }

    internal fun onMessage(m: MavMessage) {
        val v = link.vehicle
        if (v != null && m.systemId != v.systemId) return
        when (m.name) {
            "MISSION_CURRENT" -> {
                val idx = MissionEncoder.waypointIndexBySeq(onboardItems)[m.int("seq")] ?: return
                _progress.update { if (it.isFlying) it.copy(currentWaypoint = idx) else it }
            }
            "MISSION_ITEM_REACHED" -> {
                val idx = MissionEncoder.waypointIndexBySeq(onboardItems)[m.int("seq")] ?: return
                _progress.update {
                    if (!it.isFlying) return@update it
                    val reached = it.reachedWaypoints + idx
                    val last = it.waypointCount - 1
                    if (idx >= last) it.copy(reachedWaypoints = reached, currentWaypoint = idx, phase = MissionProgress.Phase.FINISHED)
                    else it.copy(reachedWaypoints = reached)
                }
            }
            "HEARTBEAT" -> {
                val mode = Px4Mode.fromCustomMode(m.long("custom_mode"))
                _progress.update {
                    when {
                        // Switched out of the mission by the RC or the firmware (e.g. low battery RTL).
                        it.phase == MissionProgress.Phase.RUNNING && mode != PowerEggMode.WAYPOINTS.reported ->
                            if (mode == PowerEggMode.HOVER.reported) it.copy(phase = MissionProgress.Phase.PAUSED)
                            else it.copy(phase = MissionProgress.Phase.ABORTED, error = "Mission interrupted: aircraft switched to ${mode.label}.")
                        it.phase == MissionProgress.Phase.PAUSED && mode == PowerEggMode.WAYPOINTS.reported ->
                            it.copy(phase = MissionProgress.Phase.RUNNING)
                        else -> it
                    }
                }
            }
        }
    }

    // --- plumbing -----------------------------------------------------------------------------

    /** Subscribes to mission messages from [sys] before [block] sends anything, so fast replies aren't missed. */
    private suspend fun <T> exchange(sys: Int, block: suspend (Channel<MavMessage>) -> T): T = coroutineScope {
        val inbox = Channel<MavMessage>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            link.messages.filter { it.systemId == sys && it.name in MISSION_MESSAGES }.collect { inbox.send(it) }
        }
        try { block(inbox) } finally { collector.cancel(); inbox.close() }
    }

    private fun fail(r: Result, message: String): Result {
        _progress.update { it.copy(phase = MissionProgress.Phase.FAILED, error = message, verified = false) }
        return r
    }

    private fun describe(r: Result) = when (r) {
        is Result.Failed -> r.reason
        is Result.Rejected -> "Aircraft rejected the mission: ${r.reason}."
        is Result.Invalid -> r.issues.joinToString("\n")
        Result.NoVehicle -> "No aircraft connected."
        Result.Ok -> ""
    }

    private fun waypointIndexOf(items: List<MissionItem>, i: Int): Int? =
        if (items[i].command == MavCmd.NAV_WAYPOINT) items.take(i).count { it.command == MavCmd.NAV_WAYPOINT } else null

    companion object {
        private val MISSION_MESSAGES = setOf(
            "MISSION_ACK", "MISSION_REQUEST", "MISSION_REQUEST_INT", "MISSION_COUNT", "MISSION_ITEM", "MISSION_ITEM_INT",
        )

        fun ackReason(type: Int) = when (type) {
            MavMissionResult.MAV_MISSION_ERROR -> "generic error"
            MavMissionResult.MAV_MISSION_UNSUPPORTED_FRAME -> "unsupported frame"
            MavMissionResult.MAV_MISSION_UNSUPPORTED -> "unsupported command"
            MavMissionResult.MAV_MISSION_NO_SPACE -> "too many waypoints for the aircraft"
            MavMissionResult.MAV_MISSION_INVALID -> "invalid mission"
            MavMissionResult.MAV_MISSION_INVALID_SEQUENCE -> "items out of sequence"
            MavMissionResult.MAV_MISSION_DENIED -> "denied (aircraft not ready)"
            in 6..12 -> "invalid parameter ${type - 5}"
            else -> "code $type"
        }
    }
}
