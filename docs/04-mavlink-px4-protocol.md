# 04 - PowerEgg drone-side MAVLink / PX4 protocol (libpvsdk_api-release.so, Vision+ 1.7.3)

Source of truth: the Ghidra pseudo-C `<ghidra-out>/libpvsdk_api-release.c` (function
headers `// ==== name @ addr`), the JNI glue `libpvsdk_api_wrapper.c`, the raw `.so` (constants, tables and
GOT relocations read with Python), and the jadx Java sources under `com/powervision/powersdk/`.

Tags: **OBSERVED** = read directly from code or bytes (function name and Ghidra address cited).
**INFERRED** = a conclusion drawn from observed facts, not seen literally. Nothing under `apk\` or `re_work\`
was modified. Helper scripts live in the session scratchpad (`mav04\*.py`).

Address conventions (OBSERVED, ELF program headers): Ghidra address = file VA + 0x10000. Text: file offset =
VA. Data segment: file offset = VA - 0x1000 (VA >= 0x16a9b0). PIC string references in the pseudo-C look
like `(DAT_X + 0xY)`; the target is `u32(DAT_X) + 0xY`. GOT slots are zero in the file and were resolved
from `.rel.dyn` + `.dynsym` (for example `g_callBack`, `px4ModeIds`, `px4VerifyModeIds`).

Related documents: 01 (JNI surface and connection state machine), 06 (camera msg 160 and gimbal, not repeated
here in full).

---

## 0. Summary

| Topic | Finding |
|---|---|
| Transport (PowerEgg) | **TCP client**. The app connects to **192.168.1.12:20002** (or 192.168.1.11:20002), and MAVLink v1 frames are streamed over that socket. A UDP mode (bind and send 14556 to 192.168.1.12) and a serial mode exist, but `initSDK(String,int)` hard-wires TCP. |
| MAVLink version | **v1 only** (STX 0xFE). There is no 0xFD v2 framing and no signing anywhere. |
| Our ids | sysid **255** (0xFF), compid **190** (0xBE). sysid 0xFD only when init flag `+0xa4` = 1, which is never set for the Egg. seq comes from a global counter. |
| Drone ids | Taken from the first HEARTBEAT with `autopilot == 12` (MAV_AUTOPILOT_PX4). Some calls hard-code target 1/1, so the Egg is almost certainly sysid 1 / compid 1 (INFERRED). |
| GCS heartbeat | 1 Hz. type=6 (GCS), autopilot=0, base_mode=0x81, mavlink_version=3. custom_mode and system_status are uninitialised heap bytes (send 0). It only starts after the drone's first heartbeat. |
| Link loss | A 3000 ms watchdog is re-armed on every PX4 heartbeat. Expiry calls `connectionNotify(5)`; the next heartbeat calls `connectionNotify(14)`. Separately, no drone heartbeat within 3 s of `connectDrone()` calls `connectionNotify(11)`. |
| Flight modes | All mode changes, **including arm, disarm, takeoff, land and RTL**, are sent as **SET_MODE (11)** with PX4-style `custom_mode = main<<16 \| sub<<24`. PowerVision added extra main modes 8-14 (TAKEOFF, LAND, RTL, SIMPLE, CIRCLE, FOLLOW, LOITER). There is no COMMAND_LONG 400/22/21/20. |
| Custom messages | 150, 152-155, 159 `PV_INFOR`, 160 `PV_CAMERA_COMMAND`, 161, 163 `GENERIC_PERIPHERAL`, 170, 171, 188, 189 `HELM_CONTROL` (plus PowerVision semantics on standard 110/201/OPTICAL_FLOW_RAD). The PowerEgg uses **159, 160, 189** (and 163 for payloads). The rest are PowerRay/Dolphin. |
| Activation | Native code only relays it. It reads `PV_RO_KEY_0..15` / `PV_RO_PSN_0..3` via PARAM_REQUEST_READ and writes `PV_V_KEY_FLAG` via PARAM_SET. **No server, crypto or region logic lives in the native lib.** |
| Safety locks in native | None for the PowerEgg: no NFZ, no region or firmware gating. Geofence is the plain SAFETY_SET_ALLOWED_AREA, and PARAM_SET is restricted to a whitelist of names. |

---

## 1. Transport

### 1.1 Socket selection (OBSERVED)

- `Java_..._JniNatives_initSDK__Ljava_lang_String_2I@0002f2d0` (wrapper) builds `_PVSDK_INIT_PARAM`:
  - `ip[16]` at +0x00 and `port` at +0x10.
  - +0x98 = 0, +0x9c = 0, **+0xa0 = 3**, +0xa4 = 0 (constants at wrapper 0x2f380/0x2f388).
  - It then calls `PVSDK_initSdk@000e68b0`, which calls `Service_Api_initService@000dfd00`, which calls
    `DroneService::create@000e1bc4`.
- +0x98 is the log-to-file flag and +0x9c is the log level (`Service_Api_initService`).
- `MavlinkConnectionManager::createMavlinkConnection@0008d674` switches on param +0xa0:

| +0xa0 | Class | Notes |
|---|---|---|
| 0 | `SerialConnection` (0x4c4 bytes) | `initSDK(String name, int baud, int, int)` path (USB/UART) |
| 2 | `UdpConnection` | `UdpConnection::openConnection@00081a30`: `socket(AF_INET, SOCK_DGRAM)`, bind 0.0.0.0:**14556** (sin_port bytes 38 DC), send to hard-coded **"192.168.1.12":14556** (string @0x172730). Not used by the Egg. |
| **3** | **`TcpConnection`** | **PowerEgg path.** |

- `TcpConnection::openConnection@0008260c`:
  - IP string and port come from the init param.
  - `socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)`, non-blocking.
  - SIGPIPE handler installed.
  - Spawns `connectTcpThread`.
- `connectTcpThread@00082930`:
  - Retries `connect()` for up to 3 s.
  - EISCONN calls `connectionNotify(1)` (onConnectSuccess).
  - Any other error calls `connectionNotify(4)` (failed).
  - 3 s elapsed calls `connectionNotify(3)` (timeout).
- `TcpConnection::readMessage@00082af0`:
  - `recv()` up to 1024 bytes, then runs the inline mavlink_parse_char state machine (jump table not
    recovered by Ghidra).
  - `recv()==0` calls `connectionNotify(2)` (disconnected).
- Java chooses the endpoint (see doc 01):
  - `FlyFragment.java:974` / `FlyControlFragment.java:187`: `initSDK("192.168.1.12", 20002)`.
  - Fallback `"192.168.1.11"` (`FlyFragment.java:1413`).
  - INFERRED: .12 is the RC/ground PVLink unit, which bridges MAVLink to the aircraft. **The app is the TCP
    client and the RC/ground unit is the server.**

### 1.2 Framing (OBSERVED)

- Every `X::encode` builds a v1 frame:
  - header `FE len seq sysid compid msgid`
  - payload
  - X.25 CRC over len..payload plus CRC_EXTRA, little-endian.
- The magic and length are written as one u16 (for example `0x09fe` in `HeartBeat::encode@00085c50` and
  `0x21fe` in `CommandLong::encode@00087f14`).
- Frame size = len + 8 (the `this+0x14` assignment).
- `Tcp/UdpConnection::sendMessage@00082a70/00081c8c` copy `magic..payload` and append the 2 CRC bytes.
- There is no 0xFD STX, no incompat/compat flags, no 24-bit msgid and no signature. Every msgid is a `byte`
  (`MavlinkMessage+8`).
- In-memory `__mavlink_message` (v1 C library layout):

| Offset | Field |
|---|---|
| +0 | checksum (u16) |
| +2 | magic |
| +3 | len |
| +4 | seq |
| +5 | sysid |
| +6 | compid |
| +7 | msgid |
| +8 | payload |

- SDK `MavlinkMessage` object: +8 msgid, +9 sysid, +10 compid, +11 seq, +0xc raw pointer
  (`MavlinkMessage::MavlinkMessage(__mavlink_message*)@0007f658`).

### 1.3 Ids and sequence (OBSERVED)

- `DroneService::DroneService@000e1bb0` sets own sysid=0xFF and compid=0xBE.
- `MavlinkDrone::sendMavlinkMessage@000dc788` overwrites every outgoing message:
  - sysid = 0xFF, or 0xFD if `MavlinkDrone+0x84 == 1`. +0x84 is init param +0xa4 (`MavlinkDrone::MavlinkDrone@000db720`), which is 0 for the Egg.
  - compid = 0xBE.
  - seq = a global counter, incremented per message.
- The target ids are the drone's heartbeat sysid/compid (`MavlinkDrone::getDroneSysId/CompId@000db6f0/04`,
  stored by `PixHawk::PixHawk(this, drone, sysid, compid)@000d6138`).
- **Exceptions that target our own 255/190** (`MavlinkDrone::getSysId/getCompId` resolve to DroneService):
  - MOUNT_CONTROL (`PX4Gimbal::setGimbalOrientation@0009df4c`)
  - the MISSION_CLEAR_ALL sent on MISSION_ITEM_REACHED (see 3.4)
- INFERRED: PX4's target check probably drops the MISSION_CLEAR_ALL. The gimbal one is evidently honoured by the Egg (doc 06).
- Hard-coded targets:

| Message | Target sysid/compid | Function |
|---|---|---|
| MAV_CMD_PREFLIGHT_CALIBRATION (mag) | 1/1 | `MavlinkDrone::magnetometerCalibrate@000de5d0` |
| MOUNT_CONFIGURE | 1/1 | `MavlinkDrone::gimbalControl@000de434` |
| RC calibration | 1/6 (standard RC) | `StandardRemoteControl::operateCalibrate@0009e824` |
| RC calibration | 1/7 (motion-sensing RC) | `MotionSensingRemoteControl::operateCalibrate@0009f154` |

- INFERRED from these: the aircraft is **sysid 1, compid 1**.

### 1.4 Heartbeat out (OBSERVED `heartbeatThreadPX4@000d64a8`)

- Loop: `new HeartBeat()`, `type(+0x1c)=6`, `autopilot(+0x1d)=0`, `base_mode(+0x1e)=0x81`, send, `Sleep(1000)`.
- `HeartBeat::encode@00085c50` forces `mavlink_version=3`.
- custom_mode (+0x18) and system_status (+0x1f) are never written. They are uninitialised heap from
  `operator new`. **Send 0** in the port.
- Example frame (seq 0): `FE 09 00 FF BE 00 00 00 00 00 06 00 81 00 03 DD 29`.
- Started by `PixHawk::startHeartbeat@000d6490`. It is called from the `PixHawk` constructor, which runs in
  `MavlinkDrone::createConcreteDrone@000dea7c` on the first heartbeat with autopilot==12. **The GCS stays
  silent until the drone speaks.**
- Separate RC heartbeat threads (`heartbeatThreadSTRC/MSRC`) run for the RC objects.

### 1.5 Connection and link-loss state machine (OBSERVED `mavMessageHandleThread@000db990`)

- `PVSDK_connect` opens the socket.
- `connectDrone()` (`MavlinkDrone::connectDrone@000dc86c`):
  - sets the "drone wanted" flag `+0x51`
  - arms a 3000 ms timer `connectionTimeOutFunc@000dc8d4`, which on expiry calls `connectionNotify(11)` (onDroneConnectTimeout)
- Until a drone exists (`+0x50`), every non-heartbeat message is dropped.
- HEARTBEAT routing by sysid/compid/autopilot (`HeartBeat` object: +0x18 custom_mode, +0x1c type,
  +0x1d autopilot, +0x1e base_mode, +0x1f system_status, +0x20 version):

| Condition | Result | Callback | Watchdog |
|---|---|---|---|
| sysid 4 | Dolphin | (out of scope) | 10 s |
| sysid 2 | PowerRay | (out of scope) | 10 s |
| compid 6 | Standard RC | `connectionNotify(6)` | 3 s |
| compid 7 | Motion-sensing RC | `connectionNotify(7)` | 3 s |
| autopilot 12 | `PixHawk` + `PX4Mode` created | `connectionNotify(9)` (onDroneConnected) | 3 s |
| autopilot 3 | `ArduPilotMega` | - | - |
| any other autopilot | ignored | - | - |

- Every later PX4 heartbeat:
  - re-arms a 3000 ms watchdog. Expiry runs `heartbeatTimeOutFunc@000ded7c`, which sets the lost flag and
    calls `connectionNotify(5)` (onHeartbeatTimeout).
  - if the lost flag is set, calls `connectionNotify(14)` (onDroneConnectedReplay = recovered).
  - is passed to `MissionAction::processMessage` (mode/arm verification, section 3.1).
  - is passed to `MavlinkDrone::processLocatingType@000ded18`: if the heartbeat **`type` byte** is 6 or 7,
    it calls `locatingTypeChangedNotify(6|7)` (INFERRED: the PowerVision firmware reuses `type` to signal
    the GPS/vision positioning source).
- PVSdk.java reconnects 10 s after `connectionNotify(5)` (doc 01).

---

## 2. Inbound messages

### 2.1 Accept list and CRC table

- `MavlinkConnection::addMessage@000802d8` builds a typed object only for the msgids below. **All others are
  freed and dropped.**
- Each kept object is queued to `MavlinkClient`. `mavMessageHandleThread` pops one every 3 ms.

**CRC_EXTRA table** (OBSERVED):
- A 256-byte `MAVLINK_MESSAGE_CRCS` copy sits before each connection class's typeinfo string:
  - file 0x162873 (Serial), 0x162983 (Udp), 0x162a93 (Tcp), 0x163304 (Mt03), 0x163e00
  - all five copies are identical
- Non-zero entries:

```
0:50 1:124 2:137 4:237 5:217 6:104 7:119 11:89 20:214 21:159 22:220 23:168 24:24 25:23 26:170 27:144
28:67 29:115 30:39 31:246 32:185 33:104 34:237 35:244 36:222 37:212 38:9 39:254 40:230 41:28 42:28
43:132 44:221 45:232 46:11 47:153 48:41 49:39 50:78 54:15 55:3 61:153 62:183 63:51 64:59 65:118 66:148
67:21 69:243 70:124 73:38 74:20 75:158 76:152 77:143 81:106 82:49 83:22 84:143 85:140 86:5 87:150 89:231
90:183 91:63 92:54 100:175 101:102 102:158 103:208 104:56 105:93 106:138 107:108 108:32 109:185 110:84
111:34 112:174 113:124 114:237 115:4 116:76 117:128 118:56 119:116 120:134 121:237 122:203 123:250
124:87 125:203 126:220 127:25 128:226 129:46 130:29 131:223 132:85 133:6 134:229 135:203 136:1 137:195
138:109 139:168 140:181 141:47 147:154 148:178 150:70 152:154 153:170 154:30 155:38 156:19 157:21
158:134 159:174 160:133 161:12 163:213 170:55 171:140 189:108 245:245 248:8 249:204 250:49 251:170
252:44 253:83 254:46
```

**Validation against upstream common.xml / ardupilotmega.xml (v1 CRC_EXTRA):**
- These all match: 0=50, 1=124, 11=89, 20=214, 21=159, 22=220, 23=168, 24=24, 27=144, 30=39, 33=104,
  39=254, 40=230, 44=221, 47=153, 54=15, 69=243, 73=38, 74=20, 75=158, 76=152, 77=143, 105=93, 106=138,
  109=185, 110=84, 132=85, 141=47, 147=154, 148=178, 156=19, 157=21, 158=134, 253=83.
- The per-message `encode()` constants agree with the table (table in section 5).
- So the reading is sound, and this is a ~2015-2016 PX4 common set plus a PowerVision block at 150-171/189.

**Anomalies** (OBSERVED; impact on the receiver unverified because the parser's jump table was not recovered):
- `HOME_POSITION` 242 is **0** in the table, but `HomePosition::encode@000854c4` uses 104 and `addMessage`
  accepts 242.
- `ZcameraStatus` 188 is 0 in the table, but its encode uses 93.
- `EXTENDED_SYS_STATE` 245 reads **245**, but its encode uses 130.
- 160 and 161 are redefined (FENCE_POINT 78 became 133, FENCE_FETCH_POINT 68 became 12).
- **Port advice:** accept a frame if its CRC matches *either* the upstream or the SDK value for these ids.
  Log which one matched on the first real flight.

### 2.2 Dispatch (OBSERVED)

**Level 1** is `mavMessageHandleThread@000db990`. **Level 2** is `PixHawk::processMessage@000d68dc`, the
drone's vtable +0x1c, which receives everything level 1 does not consume.

**Callback slots.**
- "Slot" = offset in the C callback struct `g_callBack` (GOT 0x17f70c).
- The struct is filled by `Java_..._registerCallback@0002f4ec` (wrapper). It calls the Java static
  `JniNatives.<name>Notify`.
- The slot to name map was resolved from the wrapper thunks' `GetStaticMethodID` strings:

| Slot | Callback | Slot | Callback |
|---|---|---|---|
| 0x00 | connectionNotify | 0x04 | modeChangedNotify |
| 0x08 | missionNotify | 0x0c | missionActionNotify |
| 0x10 | startWaypointNotify | 0x14 | missionRunNotify(II) |
| 0x18 | alarmNotify(J) | 0x1c | paramSetGetNotify(I,String) |
| 0x20 | takeoffLandNotify | 0x24 | gpsRawIntNotify |
| 0x28 | sysStatusNotify | 0x2c | positionChangedNotify |
| 0x30 | homePositionNotify(III) | 0x34 | backToLastModeNotify |
| 0x38 | cameraNotify | 0x3c | cameraParamNotify |
| 0x40 | selfCheckNotify | 0x44 | attitudeAndGroundspeedChangedNotify |
| 0x48 | gimbalParamNotify | 0x4c | remoteControlParamNotify |
| 0x50 | requestOperationalSensitivityNotify | 0x54 | magnetometerCalibrateNotify |
| 0x58 | firmwareUpgradeNotify | 0x5c | remoteControlCalibrateNotify |
| 0x60 | manualControlDataNotify | 0x64 | mountControlDataNotify |
| 0x68 | batteryStatusNotify | 0x6c | remoteControlLocationNotify |
| 0x70 | helmControlStatusNotify | 0x74 | sysDoNotify |
| 0x78 | distanceSensorNotify | 0x80 | locatingTypeChangedNotify |
| 0x8c | altitudeNotify | 0x94 | baseStationBatteryNotify |
| 0xe4 | deviceConnectNotify | 0xf8 | setMountHookStateNotify |
| 0x100 | setSafetyAllowedAreaNotify | | |

Slots 0x04, 0x0c, 0x24 and 0x2c are packed by NEON stores in the wrapper. They were named from the
Java callback order, which matches the resolved slots.

**Store keys.**
- Many messages are not pushed to Java. They are stored under a string key in `PX4Parameters`
  (`PX4Parameters::setParam@00092620` / `getParam@00093ae0`).
- A *changed* notification is fired, and Java then pulls the value through a JNI getter (`getAttitude()`,
  `getVfrHudParam()`, `getGpsRawIntParam()`, ...).

| msgid | Name (std?) | Level | Handling, notify slot, Java | Field use |
|---|---|---|---|---|
| 0 | HEARTBEAT (std) | 1 | §1.5 plus §3.1 mode tracking. Stores `sys_custom_mode_param`, `sys_base_mode_param`. `modeChangedNotify(0x04)` on change. | custom_mode, base_mode (bit7 = armed), type (locating) |
| 1 | SYS_STATUS (std) | 1 | Store `system_status_params`. `sysStatusNotify(0)` (0x28) when voltage_battery, current_battery or battery_remaining change. Java: `getSystemStatusParam()`. | obj +0x26 voltage, +0x28 current, +0x36 remaining |
| 22 | PARAM_VALUE (std) | 1 | `ParameterManager::processMessage@00096844`, then worker `handleGetParam@00096950`. See §4. | param_id, param_value (raw bits) |
| 23 | PARAM_SET (std, inbound) | 2 | Log only. | - |
| 24 | GPS_RAW_INT (std) | 2 | Store `gps_row_in_params`. `gpsRawIntNotify(0)` (0x24) **only when satellites_visible changes**. Java `getGpsRawIntParam()`. | all fields |
| 30 | ATTITUDE (std) | 1 | Store `attitude_params`. `attitudeAndGroundspeedChangedNotify(0)` (0x44) if \|delta roll/pitch/yaw\| > 0.005 (double @0xdbd30). Java `getAttitude()`. | roll, pitch, yaw |
| 33 | GLOBAL_POSITION_INT (std) | 2 | Store `global_position_int_params`. `positionChangedNotify(0)` (0x2c) when lat, lon or relative_alt change. Java `getGlobalPositionIntParam()`. | lat, lon, relative_alt (+ the rest) |
| 39 | MISSION_ITEM (std) | 2 | to WaypointManager only in state 3 (download). §3.4 | |
| 40 | MISSION_REQUEST (std) | 2 | WaypointManager states 4/5 (upload). §3.4 | seq |
| 42 | MISSION_CURRENT (std) | 2 | `missionRunNotify(0, seq)` (0x14) and store `waypoint_number_param`. | seq |
| 43 | MISSION_REQUEST_LIST (std) | 2 | If idle, WaypointManager goes to state 4 and answers with MISSION_COUNT (the SDK can serve its stored mission). | |
| 44 | MISSION_COUNT (std) | 2 | Download start, state 1 to 3. §3.4 | count |
| 46 | MISSION_ITEM_REACHED (std) | 2 | **Sends MISSION_CLEAR_ALL (target 255/190)**, then `missionRunNotify(1, seq)`. | seq |
| 47 | MISSION_ACK (std) | 2 | type==MAV_MISSION_ERROR(1) in upload: `missionNotify(4)` (send failed). In state 5: state 6, `missionNotify(2)` (send success). | type |
| 49 | GPS_GLOBAL_ORIGIN (std) | 2 | decode plus log only | |
| 69 | MANUAL_CONTROL (std, inbound from RC) | 2 | `manualControlDataNotify(x,y,z,r,buttons,target)` (0x60) | |
| 74 | VFR_HUD (std) | 1 | Store `vfr_hud_params`. Slot 0x44 notify if airspeed, groundspeed or alt changed. Java `getVfrHudParam()`. | |
| 76 | COMMAND_LONG (std, inbound) | 2 | Forwarded to the RC object (`MavlinkDrone+0x2c`, vtable +0x14). INFERRED: RC calibration replies, giving `remoteControlCalibrateNotify` (0x5c). | |
| 77 | COMMAND_ACK (std) | - | accepted by addMessage, **no handler in the PX4 path** (falls through) | |
| 106 | OPTICAL_FLOW_RAD (std id) | 2 | int16 at obj +0x40 (wire offset 40 = `temperature`) is stored as `PV_V_FLOW_VER`. INFERRED: PowerVision repurposes `temperature` as the optical-flow firmware version. | temperature |
| 110 | FILE_TRANSFER_PROTOCOL layout (`FirmwarePacket`) | 1 | only while a firmware upload is active (to the uploader object `+0x30`). PowerVision upgrade protocol, out of scope. | |
| 132 | DISTANCE_SENSOR (std) | 2 | `distanceSensorNotify` (0x78) | current_distance |
| 141 | ALTITUDE (std) | 1 | if \|delta altitude_amsl\| > 0.01: `altitudeNotify(time_usec_lo, monotonic, amsl, local, relative, terrain, bottom_clearance)` (0x8c) | |
| 147 | BATTERY_STATUS (std) | 1 | sysid 0xFC (base station): `baseStationBatteryNotify` (0x94). Otherwise `batteryStatusNotify(voltages[], temperature, remaining)` (0x68). | |
| 150, 152-155 | custom (Ray/Dolphin) | - | accepted, no PX4 handler | §5 |
| 157 | MOUNT_CONTROL (apm std) | 2 | `mountControlDataNotify(pitch, roll, yaw)` (0x64) (doc 06) | |
| 158 | MOUNT_STATUS (apm std) | 2 | pointing_a/b/c to float, stored as key `mount_status` (doc 06) | |
| **159** | **PV_INFOR (custom)** | 2 | see §5.1. Bit 0x40: `remoteControlLocationNotify(lat, lon, alt, status&0xff)` (0x6c). Bit 0x80 and lat/lon changed: store `takeoffland_params`, then `takeoffLandNotify(1)` (0x20). Always `alarmNotify(status64)` (0x18). | |
| **160** | **PV_CAMERA_COMMAND (custom)** | 2 | to `CameraManager` unless `upgradeProcessing` (doc 06) | |
| 163 | GENERIC_PERIPHERAL (custom) | 1 | `MountCore::recvMount(payload)`, payload API (mountApi_*Notify) | 255-byte blob |
| 188 | ZCAMERA_STATUS (custom) | - | accepted, no PX4 handler | |
| **189** | **HELM_CONTROL (custom)** | 2 | `helmControlStatusNotify(mode, expect)` (0x70) | |
| 242 | HOME_POSITION (std) | 2 | `homePositionNotify(lat, lon, alt)` (0x30), int32 1e7 / mm | |
| 253 | STATUSTEXT (std) | 2 | `PixHawk::processStatusText@000d6568`, see below | text |

- Present as classes but **not accepted** by `addMessage`, so never received:
  - RAW_IMU, LOCAL_POSITION_NED, RC_CHANNELS_RAW, SERVO_OUTPUT_RAW, NAV_CONTROLLER_OUTPUT, HIGHRES_IMU,
    RADIO_STATUS, POWER_STATUS, ACTUATOR_CONTROL_TARGET, AUTOPILOT_VERSION
  - MAG_CAL_PROGRESS/REPORT, EKF_STATUS_REPORT, CAMERA_FEEDBACK, EXTENDED_SYS_STATE, NAMED_VALUE_INT,
    FENCE_STATUS, RADIO (166)
  - A rebuild may still want to decode them.

**STATUSTEXT parsing** (OBSERVED `PixHawk::processStatusText@000d6568`). Text is obj +0x17 and severity is +0x16.

- **`"[cal]"` messages** call `magnetometerCalibrateNotify(code)` (slot 0x54). These are PX4's standard
  mag-cal strings:

| Code | Text contains |
|---|---|
| 1 | "left orientation detected" |
| 2 | "left side done" |
| 3 | "right orientation detected" |
| 4 | "right side done" |
| 5 | "up orientation detected" |
| 6 | "up side done" |
| 7 | "down orientation detected" |
| 8 | "down side done" |
| 9 | "front orientation detected" |
| 10 | "front side done" |
| 11 | "back orientation detected" |
| 12 | "back side done" |
| 13 | "calibration done: mag" |
| 14 | "calibration failed: mag" |

- **`"MANUAL CONTROL"`** calls `deviceConnectNotify(1 if "LOST" else 2)` (slot 0xe4). This is RC link lost or regained.
- **`"PV_V_HOOK_STA"`** calls `setMountHookStateNotify("Put tick" ? 0 : 1)` (slot 0xf8).
- **`"geofence set"`** calls `setSafetyAllowedAreaNotify("not ok" ? false : true)` (slot 0x100). This is the geofence ack.

---

## 3. Outbound messages and commands

### 3.1 Flight modes, arm/disarm, takeoff, land, RTL: SET_MODE (11)

**Encoding** (OBSERVED `PX4Mode::changeMode@0009bea8`, `SetMode::encode@0008a2c4`):
- SET_MODE fields: `custom_mode` u32, `target_system` = drone sysid, `base_mode` u8.
- len 6, crc 89.
- `base_mode` is the last heartbeat base_mode ANDed with 0x81 (SAFETY_ARMED \| CUSTOM_MODE_ENABLED). It is
  **forced to 0 for ARM and DISARM**.

**Mode tables** (OBSERVED, exported symbols `px4ModeIds` @VA 0x173008, 11 x {id, value, char* desc}, and
`px4VerifyModeIds` @VA 0x17308c, 11 x {id, value}):

| idx | Sent custom_mode | main/sub | Verified against heartbeat custom_mode | Chinese desc (UTF-8 in .rodata) | Meaning |
|---|---|---|---|---|---|
| 0 | 0x00010000 | 1/0 | 0x00010000 | 手动模式 | MANUAL |
| 1 | 0x00020000 | 2/0 | 0x00020000 | 定高模式 | ALTCTL (never sent) |
| 2 | 0x00030000 | 3/0 | 0x00030000 | 定点模式 | POSCTL |
| 3 | 0x00040000 | 4/0 | **0x04040000** (AUTO.MISSION) | 航线飞行 | waypoint mission |
| 4 | 0x00080000 | **8**/0 | 0x00080000 | 自动起飞 | auto TAKEOFF (PV custom main 8) |
| 5 | 0x00090000 | **9**/0 | 0x00090000 | 自动降落 | auto LAND (PV custom) |
| 6 | 0x000A0000 | **10**/0 | **0x05040000** (AUTO.RTL) | 自动返航 | RTL (PV custom main 10) |
| 7 | 0x000B0000 | **11**/0 | 0x000B0000 | 极简模式 | "super simple" (headless) |
| 8 | 0x000C0000 | **12**/0 | 0x000C0000 | 环绕模式 | CIRCLE / orbit |
| 9 | 0x000D0000 | **13**/0 | 0x000D0000 | 跟随模式 | FOLLOW-ME |
| 10 | 0x000E0000 | **14**/0 | **0x03040000** (AUTO.LOITER) | 悬停模式 | LOITER / hover |

- Stock PX4 only defines main modes 1-8, and stock main 8 = RATTITUDE.
- **The PowerEgg firmware is a PX4 fork with PowerVision main modes 8-14.**
- It reports RTL and LOITER back as the standard AUTO sub-modes (INFERRED from the verify table).

**SDK mode type to table index** (`PX4Mode::changeMode`; enum `ModeParam.java`):

| SDK type | Java constant | idx | Sent custom_mode | base_mode |
|---|---|---|---|---|
| 0 | ARM | 0 | 0x00010000 | **0** |
| 1 | DISARM | 0 | 0x00010000 | **0** |
| 2 | TAKEOFF | 4 | 0x00080000 | last & 0x81 |
| 3 | FOLLOWME | 9 | 0x000D0000 | last & 0x81 |
| 4 | AUTOMISSION | 3 | 0x00040000 | last & 0x81 |
| 5 | AUTOLOITER | 10 | 0x000E0000 | last & 0x81 |
| 6 | LAND | 5 | 0x00090000 | last & 0x81 |
| 7 | RTL | 6 | 0x000A0000 | last & 0x81 |
| 8 | CIRCLE | 8 | 0x000C0000 | last & 0x81 |
| 9 | SUPERSIMPLE | 7 | 0x000B0000 | last & 0x81 |
| 10 | MANUAL | 0 | 0x00010000 | last & 0x81 |
| 11 | POSCTL | 2 | 0x00030000 | last & 0x81 |

**ARM and DISARM are the same frame.**
- Both send SET_MODE(custom 0x00010000, base 0). Only the expected result differs:
  - arm waits for heartbeat `base_mode & 0x80` (`PX4Mode::isArm@0009c174`)
  - disarm waits for the bit to clear
- INFERRED: the PowerVision firmware treats this as an arm/disarm toggle. Verify on hardware before
  relying on it.
- Doc 01 notes the app never calls arm/disarm/takeoff/land: the RC does them.

**Example frames** (sysid 255, comp 190, seq 0, target 1; base 0x81 assumed armed+custom):

```
ARM/DISARM FE 06 00 FF BE 0B 00 00 01 00 01 00 45 96
TAKEOFF    FE 06 00 FF BE 0B 00 00 08 00 01 81 35 52
LAND       FE 06 00 FF BE 0B 00 00 09 00 01 81 71 59
RTL        FE 06 00 FF BE 0B 00 00 0A 00 01 81 BD 44
POSCTL     FE 06 00 FF BE 0B 00 00 03 00 01 81 D9 15
MISSION    FE 06 00 FF BE 0B 00 00 04 00 01 81 05 25
CIRCLE     FE 06 00 FF BE 0B 00 00 0C 00 01 81 25 7F
FOLLOW     FE 06 00 FF BE 0B 00 00 0D 00 01 81 61 74
```

**Verification and timeouts** (OBSERVED):
- **`MissionAction::setModeCmd@0008d9bc`** handles arm, disArm, takeOff, followMe, land, rtl, posCtl, circle
  and `setMode(n)` (the last one expects `n+1`).
  - It allows **one action at a time** (`+0xa4`).
  - It calls changeMode and arms a 5000 ms timer, `missionActionTimeOutFunc@0008dbac`. On expiry:
    `missionActionNotify(-1)`.
  - `MissionAction::isMissionActionChangedSuccess@0008df10` checks each heartbeat with the `isXxx`
    predicates. On success it fires one of:
    - `missionActionNotify(0)` arm / `missionActionNotify(1)` disarm
    - `modeChangedNotify(code)`: takeoff=4 (note: Java maps 4 to `onModeChangedAutomission`, an SDK
      quirk), follow=10, loiter=11, land=6, rtl=7, circle=9, posctl=3
    - unknown expected state: `missionActionNotify(-2)`
- **`PX4Mode::switchToWayPointsMode@0009c074`** (`startWayPoints`) sends idx3 and waits for 0x04040000.
  Results: `startWaypointNotify(0)`, or `-1` after 5 s (`modeTimeOutFunc@0009be0c`).
- **`PX4Mode::processMessage@0009c3b4`** handles an unsolicited mode change. It maps heartbeat
  `custom_mode` to `modeChangedNotify(code)`:

| Code | Heartbeat mode |
|---|---|
| 1 | MANUAL |
| 3 | POSCTL |
| 4 | MISSION |
| 5 | TAKEOFF |
| 6 | LAND |
| 7 | RTL |
| 8 | SIMPLE |
| 9 | CIRCLE |
| 10 | FOLLOW |
| 11 | LOITER |
| 0 | anything else, including ALTCTL |

- **`backToLastMode`** (`PX4Mode::backupCurrentMode@0009bcc4` / `restoreLastMode@0009bd60`):
  - only remembers MANUAL, POSCTL or SIMPLE
  - restores with changeMode(10 / 11 / 9)
  - results: `backToLastModeNotify(0)`, or `-1` after 5 s
- `setCustomMode`, `setPosition` and `setGimbalPitch` are **no-ops on PixHawk**. The `PixHawk` vtable keeps
  the `Drone::` 4-byte stubs (OBSERVED vtable dump `_ZTV7PixHawk` @VA 0x16df2c).

### 3.2 COMMAND_LONG (76) and COMMAND_INT (75)

These are all the call sites of the `CommandLong` constructor (OBSERVED).

| Command | Sender | Target | params |
|---|---|---|---|
| **201** (std = MAV_CMD_DO_SET_ROI, PV semantics: **orbit setup**) | `MissionAction::circle@0008dd5c`, then SET_MODE CIRCLE | drone | p1 = radius (float), p2 = (float)speed, p3 = isClockwise ? **1.0 : 2.0**, p4 = isPause ? 1 : 0, p5 = x (lat, float deg), p6 = y (lon), p7 / confirmation not written (garbage, send 0). `CircleParam.z` is ignored. |
| **241** MAV_CMD_PREFLIGHT_CALIBRATION | `MavlinkDrone::magnetometerCalibrate@000de5d0` | **1/1** | p2 = 1 (mag), rest 0. Progress via STATUSTEXT "[cal]" (section 2.2). |
| 241 (all params 0 = cancel) | `MavlinkDrone::magnetometerCalibrateClose@000de668` | 1/1 | all 0 |
| 241 (RC stick calibration) | `StandardRemoteControl::operateCalibrate@0009e824` / `MotionSensingRemoteControl::operateCalibrate@0009f154` | **1/6** / **1/7** | p1 = 1 start, 2 finish. 3 s timer. |
| **411** (0x19b, **non-standard**, PowerVision "self check") | `PixHawk::selfCheck@000d5f1c` | drone | all params uninitialised (send 0). 5 s timer. INFERRED: results come back as `PV_*` PARAM_VALUEs routed to `selfCheckNotify` (see `tranctSelfCheck`, §4). |
| COMMAND_INT | only `Dolphin::suspendAndRecoveryMission` | - | not PowerEgg |

- **Not present anywhere:**
  - MAV_CMD_COMPONENT_ARM_DISARM (400), NAV_TAKEOFF (22), NAV_LAND (21), NAV_RETURN_TO_LAUNCH (20),
    DO_SET_MODE (176)
  - PREFLIGHT_REBOOT_SHUTDOWN (246), DO_DIGICAM_CONTROL / camera trigger, DO_MOUNT_CONTROL
- Camera control goes through msg 160 (doc 06), and the gimbal goes through MOUNT_CONTROL and
  MOUNT_CONFIGURE plus `PV_GIM_*` params.
- COMMAND_ACK is not consumed on the PX4 path.

### 3.3 Other outbound messages

| msgid | Name | Sender | Layout / notes |
|---|---|---|---|
| 0 | HEARTBEAT | `heartbeatThreadPX4@000d64a8` | §1.4 |
| 11 | SET_MODE | `PX4Mode::*` | §3.1 |
| 20 | PARAM_REQUEST_READ | `ParameterManager::requestParameter@0009556c`, `requestPvKey@00095794` | param_index = -1, by name. Per-request 1000 ms retry timer. Example `PV_RO_KEY_0`: `FE 14 00 FF BE 14 FF FF 01 01 50 56 5F 52 4F 5F 4B 45 59 5F 30 00 00 00 00 00 E3 15` |
| 21 | PARAM_REQUEST_LIST | `ParameterManager::requestParametersList@00099270` | only from the retry path when mode == 1 (`processTimeOut@000992d8`) |
| 23 | PARAM_SET | `ParameterManager::setParameter@000958c8` | value = raw 4 bytes from the caller (int bits carried in float). **`param_type` (obj +0x2e) is never written** (garbage). Send `MAV_PARAM_TYPE_INT32` (6) for int-style PV_* and REAL32 (9) for floats; PX4 ignores the type for known params (INFERRED). 1000 ms retry/timeout. |
| 39/40/41/43/44/45/47 | mission protocol | `WaypointManager`, `MavlinkDrone::setWayPoints` | §3.4 |
| 54 | SAFETY_SET_ALLOWED_AREA | `PixHawk::setSafetyAllowedArea@000d63c8` | p1x, p1y, p1z, p2x, p2y, p2z (float), target = drone, frame (u8). Also stored as `safety_allowed_area`. Ack is STATUSTEXT "geofence set"/"not ok". This is the **only geofence**. |
| 156 | MOUNT_CONFIGURE | `MavlinkDrone::gimbalControl@000de434` | target 1/1, mount_mode, stab_roll, stab_pitch, stab_yaw (doc 06) |
| 156 | MOUNT_CONFIGURE | `MavlinkDrone::startAutodyne@000deea0` | 1/1, **mount_mode = 6**, stabs 0. INFERRED: "autodyne" = selfie, a PowerVision meaning of mode 6. |
| 157 | MOUNT_CONTROL | `PX4Gimbal::setGimbalOrientation@0009df4c` | input_a = pitch, input_b = roll, input_c = yaw (int32), target 255/190 (doc 06) |
| **159** | PV_INFOR | `MavlinkDrone::setTakeoffLand@000dcd98` | `setTakeoffLand(land_lat, land_lon, land_alt, status)` sets the home / landing point. §5.1 |
| **160** | PV_CAMERA_COMMAND | `CameraImpl::sendCameraCmd` | doc 06 |
| 163 | GENERIC_PERIPHERAL | `MavlinkDrone::sendMountMsg@000df868` | 255-byte opaque payload-API frame (MountCore) |
| **189** | HELM_CONTROL | `PixHawk::hlemChangeMode/ControlOpen/Close@000d601c/6080/60dc` | {mode, expect}: change mode = {mode, 3}, open = {0, 1}, close = {0, 2} |
| 110 | FirmwarePacket | `FirmwareUpload/Ap01FirmwareUpload/DowloadFile::sendRequest` | upgrade/download transport (out of scope) |

- **MANUAL_CONTROL / virtual sticks: none for the PowerEgg.**
  - `throttleThreadPX4@000d7148` would send MANUAL_CONTROL {x=0, y=0, z=500, r=0, buttons=0} at 5 Hz.
  - It has **no references**. There is no GOT slot, no PIC reference and no thread creation (OBSERVED:
    exhaustive PIC scan). It is dead code.
  - Ray and Dolphin start their own throttle threads.
  - RC_CHANNELS_OVERRIDE (70) is never built. The sticks are on the physical RC.

### 3.4 Mission (waypoint) protocol (OBSERVED `WaypointManager::*@0008e268..0008ef14`)

**Item building.** `MavlinkDrone::setWayPoints@000dca58` (from Java `setWayPoints(float[] p1..p4, x, y, z, int[] seq)`):
- Each `_PVSDK_WAYPOINT_PARAM` (32 bytes) becomes a **MISSION_ITEM (39)** (float lat/lon, not MISSION_ITEM_INT):
  - param1-4 = p1..p4, x = lat (deg, float), y = lon, z = alt (m)
  - seq from the array
  - **command = 16 (MAV_CMD_NAV_WAYPOINT)**, **frame = 3 (MAV_FRAME_GLOBAL_RELATIVE_ALT)**, autocontinue = 1
  - `current = (seq == 0)`, set at send time
  - target = drone ids
- Then it sends **MISSION_CLEAR_ALL (45)** to the drone, and then **MISSION_COUNT(n)**.

**Upload** (WaypointManager state):

| Step | What happens |
|---|---|
| 1 | state 4 after MISSION_COUNT |
| 2 | each MISSION_REQUEST(seq) gives state 5, `sendWaypoint`, which looks up the queued item with that seq and sends it |
| 3 | MISSION_ACK gives `missionNotify(2)` (success). In PixHawk, `type == 1` while sending gives `missionNotify(4)` (failed). |
| Retries | 2000 ms watchdog (`startWatchdog`): resend COUNT (state 4) or the last item (state 5/6), up to `+0x20` retries. Then `missionError(-1)`, which gives `missionNotify(-1)`. |

**Download** (`loadWayPoints`):

| Step | What happens |
|---|---|
| 1 | `requestWaypointsList@0008e2f4` sends MISSION_REQUEST_LIST, state 1, 2 s timer |
| 2 | MISSION_COUNT sets n, then MISSION_REQUEST(0) (`requestWayPoint@0008eb58`), state 3 |
| 3 | each MISSION_ITEM with the expected seq is stored (`pullParam`), then MISSION_REQUEST(seq+1). A wrong seq re-requests. |
| 4 | after the last item, **MISSION_ACK(type 0)** (`sendAck@0008ec60`), store `download_wayPoint_params`, `readWaypoint(0)` |
| Result | INFERRED: `missionNotify(5)` (download success). Timeout gives `readWaypoint(1)`, INFERRED `missionNotify(6)`. Java reads via `getDownloadPoint()`. |

**Notes:**
- `sendSetCurrentWaypoint@0008e4b0` (MISSION_SET_CURRENT 41) exists, but no caller was found on the PX4 path.
- **Start mission** = SET_MODE 0x00040000 (§3.1).
- **Quirk:** PixHawk answers every MISSION_ITEM_REACHED with MISSION_CLEAR_ALL addressed to 255/190
  (`PixHawk::processMessage` case 0x2e).
  - INFERRED: PX4 ignores it because of the target mismatch.
  - Do **not** copy this in the port unless a capture shows the Egg expects it.
- **No fence upload protocol:**
  - FENCE_POINT/FETCH_POINT classes exist but are unused, and msgids 160/161 are reused.
  - `setFence`, `getFence`, `setHome` and `getHome` are empty stubs (`DroneService::setFence@000e3e0c`
    etc. just log "setFense()").
  - The rally/home point is set with PV_INFOR (159). Home is read from HOME_POSITION (242).

---

## 4. Parameters

### 4.1 Protocol (OBSERVED)

- **Read** (`requestParameter(name)`):
  - Sends PARAM_REQUEST_READ by id and records a send-list entry (kind 2) with a 1 s timer (`addSendList@00095658`).
  - PARAM_VALUE is queued to the worker `handleGetParam`.
  - The worker stores the value in `PX4Parameters` and fires one of:
    - `gimbalParamNotify(0, id)` for `PV_GIM_*`
    - `cameraParamNotify(0, id, val)` for `PV_CAM_*`
    - `remoteControlParamNotify(0, id)` for `PV_RC_*` / `PV_V_RC_MODE` / `PV_RC_CTRL` / `PV_RC_UPDATE`
    - `paramSetGetNotify(2, id)` (get success) for the rest
  - Timeout (`paramCallback@0009528c`) gives `paramSetGetNotify(3, id)`.
- **Write** (`setParameter(name, float)`):
  - `MavlinkDrone::setParameter@000dcf60` **rejects (-1) any name not in its whitelist** (4.2).
  - `ParameterManager::setParameter` sends PARAM_SET, updates the local cache, and records a kind-3 entry.
  - The echoed PARAM_VALUE gives `paramSetGetNotify(0, id)` (set success). Timeout gives
    `paramSetGetNotify(1, id)`. For `PV_V_KEY_FLAG` the results are `sysDoNotify(4)` (set ok) or `(5)` (set
    timeout).
- **Self-check / status interception** (`ParameterManager::tranctSelfCheck@00095fc8`, run on every PARAM_VALUE first):

| Params | Callback |
|---|---|
| `PV_GIM_STATUS`, `PV_V_HOOK_STA`, `PV_GIM_VER` | `gimbalParamNotify(2, id)` |
| RC type/batt/version/`PV_V_RC_UPDATE` | `remoteControlParamNotify(0 or 2, id)` |
| `PV_V_STAT`, `PV_V_VER`, `PV_V_BATT_VER`, `DEEP_SENSOR_ID`, `PV_RO_VER_PDO`, `PV_V_SURFACE`, `PV_V_LEAKAGE`, `PV_V_TYPE` | `selfCheckNotify(2, id)` |
| `PV_CAM_SD_LEFT`, `PV_CAM_SD_PLEFT`, `PV_V_SD_CAP`, `PV_CAM_VER`, `PV_CAM_STAT` | `cameraParamNotify(2, id)` |
| `PV_RO_KEY_n` | collect 16 keys, then `sysDoNotify(0)` |
| `PV_RO_PSN_n` | collect 4, then `sysDoNotify(6)` |
| misc versions / geo params | `paramSetGetNotify(2)` |

- `PV_CAM_REQ_ALL` is special-cased. It arms a 3 s timer that collects the whole camera set
  (`transactCameraReqAll@000959e8`).

### 4.2 Parameter names referenced (OBSERVED string literals; resolved per function)

**SDK-local struct keys** (not drone params; stored or read in `PX4Parameters::set/getParam`):
- `gps_row_in_params`, `attitude_params`, `vfr_hud_params`, `system_status_params`
- `waypoint_number_param`, `missionitem_reached_param`, `global_position_int_params`, `home_position_params`
- `takeoffland_params`, `safety_allowed_area`, `mount_status`, `sys_custom_mode_param`, `sys_base_mode_param`
- `download_wayPoint_params`

**PARAM_SET whitelist** (`MavlinkDrone::setParameter@000dcf60`). Only these can be written:

| Group | Names |
|---|---|
| Stock PX4 | `PWM_MAIN_REV7`, `RTL_DESCEND_ALT`, `RTL_LAND_DELAY`, `RTL_RETURN_ALT`, `GF_MAX_VER_DIST`, `MC_PITCH_TC`, `MC_ROLL_TC`, `INAV_LIDAR_EST`, `NAV_RCL_OBC` |
| PV status / identity | `PV_STATUS_GPS`, `PV_V_TYPE`, `PV_V_STAT`, `DEEP_SENSOR_ID`, `PV_V_VER`, `PV_V_UDID`, `PV_V_SD_CAP`, `PV_V_SD_LEFT`, `PV_STATUS_GIMBAL`, `PV_STATUS_RC_BATT`, `PV_STATUS_SD_CAPACITY`, `PV_STATUS_REMOTE_MODE`, `PV_VEHICLE_TYPE`, `PV_VEHICLE_VERSION`, `PV_VEHICLE_UDID`, `PV_GIMBAL`, `PV_GIMBAL_TYPE`, `PV_CAMERA_TYPE`, `PV_CAMERA_VERSION`, `PV_REMOTE_VERSION`, `PV_REMOTE_TYPE` |
| Camera (doc 06) | `PV_CAM_TYPE`, `PV_CAM_VER`, `PV_CAM_STAT`, `PV_CAM_EV`, `PV_CAM_ISO`, `PV_CAM_WB`, `PV_CAM_LT`, `PV_CAM_SM`, `PV_CAM_STYLE`, `PV_CAM_ACUT`, `PV_CAM_P_FMAT`, `PV_CAM_SS`, `PV_CAM_P_SIZE`, `PV_CAM_V_SIZE`, `PV_CAM_V_FMAT`, `PV_CAM_V_SYS`, `PV_CAM_MAN_ZOOM`, `PV_CAM_AT_ZOOM`, `PV_CAM_V_QUALI`, `PV_CAM_PH_MODE`, `PV_CAM_LONG_EXP`, `PV_CAM_SCENE_M`, `PV_CAM_TV_MODE`, `PV_CAM_L_FREQ`, `PV_CAM_FORMAT`, `PV_CAM_SET_T1..T3`, `PV_CAM_V_OSD`, `PV_CAM_P_OSD`, `PV_CAM_AF_MODE`, `PV_CAM_KP_ZOOM`, `PV_CAM_CAF_DIS`, `PV_CAM_CAF_LVL`, `PV_CAM_V_S_S`, `PV_CAM_P_Q`, `PV_CAM_P_S_S`, `PV_CAM_APE_V`, `PV_CAM_EXP_MU`, `PV_CAM_WB_V`, `PV_CAM_BN_V`, `PV_CAM_ACUT_V`, `PV_CAM_CONTRA_V`, `PV_CAM_AUTO_OFF`, `PV_CAM_D_TIME`, `PV_CAM_D_P_NUM`, `PV_CAM_SATUR_V`, `PV_CAM_SD_CAP`, `PV_CAM_SD_LEFT`, `PV_CAM_SD_VLEFT`, `PV_CAM_OSD_ON`, `PV_CAM_SD_PLEFT` |
| Gimbal (doc 06) | `PV_GIM_MODE`, `PV_GIM_PITCH`, `PV_GIM_ROLL_ADJ`, `PV_GIM_P_30`, `PV_GIM_P_SMT`, `PV_GIM_Y_SMT`, `PV_GIM_ANGLE`, `PV_GIM_AUTO_CAL`, `PV_GIM_RESET_PM` |
| RC | `PV_RC_TYPE`, `PV_RC_VER_TG`, `PV_RC_VER_STD`, `PV_RC_VER_APP`, `PV_RC_BATT`, `PV_RC_MODE`, `PV_RC_K1_FUNC`, `PV_RC_HTR_EXP`, `PV_RC_YAW_EXP`, `PV_RC_PITCH_EXP`, `PV_RC_ROLL_EXP`, `PV_RC_SWITCH`, `PV_RC_TG_BATT`, `PV_RC_TG_EXP`, `PV_V_RC_MODE`, `PV_RC_CTRL` |
| Battery / misc | `PV_BAT_CRITICAL`, `NEW_USER`, `PV_V_FLY_TIME`, `PV_V_ARM_TIME`, `RC_TRAN_USED`, `ANTENA_CHANGE` |
| Activation | `PV_V_KEY_0..PV_V_KEY_15`, `PV_V_KEY_FLAG` |
| Ray/Dolphin mission (also accepted) | `PV_V_FISHING`, `PV_INTOWATER`, `PV_MISSION_TYPE`, `PV_GEOFENCE`, `PV_MISSION_MODE`, `PV_V_YEILDTEST`, `PV_SCAN_SHAPE` |

**Read-only / status names** (seen in `PX4Parameters`, `tranctSelfCheck`, `handleGetParam` and the `ParameterManager` ctor):
- `PV_RO_KEY_0..15`, `PV_RO_PSN_0..3`, `PV_RO_VER_PDO`, `PV_V_OF_VER`, `PV_V_FLOW_VER`, `PV_V_SON_VER`, `PV_V_SONBL_VER`
- `PV_V_HOOK_STA`, `PV_V_MOUNT_TYPE`, `PV_V_BATT_VER`, `PV_V_SURFACE`, `PV_V_LEAKAGE`, `PV_V_MOTOR0..2`, `PV_V_RC_UPDATE`, `PV_RC_UPDATE`
- `PV_GEO_P1X`, `PV_GEO_P1Y`, `PV_GEO_P2X`, `PV_GEO_P2Y`, `PV_GIM_VER`, `PV_GIM_STATUS`, `PV_GIM_ANGEL` (sic)
- `PV_CAM_REQ_ALL` (pseudo), `PV_KEY_CRC` (Ray/Dolphin only)

**What they control** (INFERRED from names and Java usage, see doc 01 §4 and doc 06):

| Param(s) | Meaning |
|---|---|
| `RTL_RETURN_ALT` / `RTL_DESCEND_ALT` / `RTL_LAND_DELAY` | stock PX4 RTL height and behaviour |
| `GF_MAX_VER_DIST` | max altitude geofence |
| `NAV_RCL_OBC` | RC-loss action |
| `MC_PITCH_TC` / `MC_ROLL_TC` | attitude time constants. "Operational sensitivity": `operationalSensitivityConfig(orientation, f)` sets `MC_ROLL_TC` or `MC_PITCH_TC`, and `requestOperationalSensitivity` reads `MC_PITCH_TC` (`@000de4e4/000de548`). |
| `INAV_LIDAR_EST` | visual/lidar positioning on/off (`enableVisualLocating@000de58c`) |
| `PWM_MAIN_REV7` | output channel 7 reverse (INFERRED: hook/payload servo) |
| `PV_RC_*_EXP` / `ST_*_EXP` | stick expo |
| `PV_RC_MODE` | stick mode 1/2 |
| `PV_RC_K1_FUNC` | custom button |
| `PV_BAT_CRITICAL` | low-battery threshold |
| `PV_V_FLY_TIME` / `PV_V_ARM_TIME` | counters |
| `NEW_USER` | beginner mode (distance/height limit) |
| `PV_GEO_P1X..P2Y` | a stored rectangular geofence echo |
| `ANTENA_CHANGE` | antenna switch |
| `RC_TRAN_USED` | RC trainer / transmit usage flag |

### 4.3 Activation (OBSERVED; Java side per doc 01)

1. **Read the keys.** `getValiteKey(0)` (`MavlinkDrone::getValiteKey@000dc954`) calls
   `ParameterManager::requestActive@0009586c`:
   - sequential PARAM_REQUEST_READ of `PV_RO_KEY_0..15`
   - all 16 received: `sysDoNotify(0)`
   - retries exhausted: `sysDoNotify(1)`
2. **Read the serial.** `getPSN(0)` reads `PV_RO_PSN_0..3`:
   - results: `sysDoNotify(6)`, or `(8)` on timeout
   - Java concatenates `floatToIntBits` of the 4 values into ASCII (`FlyFragment.java:354`)
3. **Read the flag.** Reading `PV_V_KEY_FLAG` gives:
   - `sysDoNotify(2)`: Java checks `floatToIntBits == 0` (not activated)
   - `sysDoNotify(9)`: timeout
4. **Activate.** `PowerSDK.activate(f)` does `setParameter("PV_V_KEY_FLAG", f)`. The ack gives
   `sysDoNotify(4)`, and a timeout gives `(5)`. The caller writes `intBitsToFloat(1)`.
   - Frame: `FE 17 00 FF BE 17 01 00 00 00 01 01 50 56 5F 56 5F 4B 45 59 5F 46 4C 41 47 00 00 00 06 DB 15`
     (value bits 0x00000001, target 1/1, type 6).
   - `PV_V_KEY_0..15` are whitelisted for writing. INFERRED: the server-issued key words go there in a
     full online activation. The native lib never computes or checks anything.

---

## 5. Custom / non-standard messages: payload layouts

- Layouts come from each class's `encode()` (payload writes) and `decode()` (OBSERVED). Offsets are into the v1 payload.
- CRC_EXTRA is OBSERVED from the `x ^ CRC_EXTRA` step in each `encode()` and from the table.
- Field *names* are INFERRED from the Java signatures.
- For 159 and 189 the guessed XML (name + fields below) **reproduces the observed CRC_EXTRA exactly** when
  computed with the MAVLink crc_extra algorithm. That strongly suggests the names are right: 8-bit match,
  using the Java-derived names.

### 5.1 159 `PV_INFOR`: len 20, CRC_EXTRA 174 (PowerEgg)

`PvInfor::encode@0008439c` / `decode@000844fc`.

| Off | Type | Name | Use |
|---|---|---|---|
| 0 | uint64 | status | bit flags. Bit 6 (0x40): RC/phone location valid. Bit 7 (0x80): take-off/land point. The whole value goes to `alarmNotify(long)`. Low byte is passed as `status` to `remoteControlLocationNotify`. |
| 8 | float | land_lat | deg |
| 12 | float | land_lon | deg |
| 16 | float | land_alt | m |

- GCS to drone (`setTakeoffLand(land_lat, land_lon, land_alt, status)`) sets the landing/return point.
- Drone to GCS: RC location (follow-me source, INFERRED), the confirmed landing point and alarm bits.
- XML `PV_INFOR {uint64_t status; float land_lat; float land_lon; float land_alt}` gives crc_extra 174 (verified).

### 5.2 160 `PV_CAMERA_COMMAND`: len 2, CRC_EXTRA 133

See doc 06 §1.3: {uint8 cmd, uint8 arg/result}. `PvCommand` (RC upgrade) uses the same id/len/crc.

### 5.3 189 `HELM_CONTROL`: len 2, CRC_EXTRA 108

`HelmControl::encode@00084e20`. Fields: uint8 `mode` @0, uint8 `expect` @1.
- XML `HELM_CONTROL {uint8_t mode; uint8_t expect}` gives 108 (verified).
- GCS sends:
  - expect = 1 open
  - expect = 2 close
  - expect = 3 change to `mode`
- Drone replies via `helmControlStatusNotify(mode, expect)`.
- INFERRED: "helm" = a PowerVision control-handover/remote "helm" feature, probably shared with the boat products.

### 5.4 163 `GENERIC_PERIPHERAL`: len 255, CRC_EXTRA 213

Opaque `uint8[255]` (`GenericPeripheral::encode@0008d138`). It carries the payload-mount sub-protocol
(`MountCore`/`*ConcrateProtocol`, UART/CAN/I2C/SPI/GPIO/motor). Out of scope; see the mountApi section of doc 01.

### 5.5 Other custom ids (Ray/Dolphin; accepted by the parser, no PX4 handler)

| id | Class | len | CRC | Payload (offset:type) |
|---|---|---|---|---|
| 150 | IdentityT | 21 | 70 | 0:u8[13]? (bytes 0-12 as one block), 13:u8, 14-20: one u32 + three bytes (the decode copies 8 bytes then single bytes) |
| 152 | EscInfo | 40 | 154 | 0..27: 7 x u32/float, 28..37: 5 x u16, 38: u8, 39: u8 |
| 153 | CurState | 36 | 170 | 9 x 32-bit. Fields 5 and 6 are converted uint-to-float on send. |
| 154 | SonarInfo | 20 | 30 | 4 x float, 4 x u8 |
| 155 | SysInfo | 8 | 38 | u16, u32, u16 (byte-wise) |
| 161 | (unknown) | ? | 12 | not parsed. The FENCE_FETCH_POINT class (crc 68) is stale. |
| 170 | PvLightControl | 4 | 55 | u32 (light increment) |
| 171 | GimbalCameraControl | 4 | 140 | u32/float (Dolphin gimbal pitch) |
| 188 | ZcameraStatus | 1 | 93 (table 0) | u8 |

**PowerVision semantics on standard ids** (OBSERVED usage, INFERRED meaning):
- COMMAND_LONG **201** = orbit parameters (3.2)
- COMMAND_LONG **411** = self-check
- MOUNT_CONFIGURE mount_mode 6 = selfie
- OPTICAL_FLOW_RAD.temperature = flow firmware version
- HEARTBEAT.type 6/7 = locating type
- msg **110** carries PowerVision firmware upload packets in the FILE_TRANSFER_PROTOCOL layout:
  u16 + u8 + u8[251], which is byte-compatible with target_network/system/component + payload

---

## 6. Safety / lock logic in native code

- **Activation:** only transport (4.3). Native code never blocks commands when not activated.
  - The `"NOT ACTIVATED"` string is only parsed in `Ray::processStatusText@000d78d4`.
  - INFERRED: any flight lock for a non-activated Egg lives in the **aircraft firmware**, which is keyed by
    `PV_V_KEY_FLAG`. Writing `PV_V_KEY_FLAG = int 1` is all the app does.
- **No server dependency in this lib for flight.** The only network code besides the MAVLink socket is:
  - FTP, telnet and TFTP for upgrades and logs
  - the RC/ground PVLink protocol (out of scope)
  - There are no HTTP, cloud or license strings (OBSERVED string sweep).
- **No-fly zones / region / country:**
  - None in the MAVLink path.
  - `Get/SetAir/GroundCountryCode` belong to the PVLink RF radio regulatory setting (out of scope).
- **Firmware version gating:** none in native. Versions (`PV_V_VER`, `PV_CAM_VER`, `PV_RC_VER_*`,
  `PV_GIM_VER`) are just reported to Java. Doc 01 notes the Java side does version checks.
- **Geofence:**
  - SAFETY_SET_ALLOWED_AREA (user-defined box), `GF_MAX_VER_DIST`, `NEW_USER` and the RTL params, all set by the user.
  - `setFence` / `getFence` are empty stubs.
  - `Dolphin::processStatusText` handles "Geofence violations" (not Egg).
- **Built-in guards:**
  - the PARAM_SET whitelist (4.2)
  - one MissionAction at a time, with 5 s verification timeouts
  - camera commands suppressed while `upgradeProcessing` is set

---

## 7. Kotlin reimplementation plan

### 7.1 Library choice

**Recommendation:** use **dronefleet `io.dronefleet.mavlink`** (pure Java, JVM 8+, works on Android/arm64)
for the v1 codec, common messages and enums. Add a small **hand-written PowerVision dialect**. Do not try to
regenerate PowerVision CRCs from XML. Reasons:

- dronefleet's `MavlinkConnection` speaks v1 (`Mavlink1Message`). It lets us send v1 only and provide our
  own sysid/compid (`send1(255, 190, payload)`). Custom messages are plain annotated classes:
  `@MavlinkMessageInfo(id = 159, crc = 174, description = ...)` with `@MavlinkFieldInfo(position, unitSize, ...)`.
  - So we can hard-code the **observed** CRC_EXTRA values even where the true XML names are unknown
    (150-155, 161, 170, 171).
  - The CRC table anomalies (242, 245, 188) need a custom dialect anyway.
- Alternative: generate both common and dialect from XML with `mavgen` (Java target) or dronefleet's
  generator.
  - Only valid for 159, 160 and 189, where the guessed XML reproduces the CRC (5.1-5.3).
  - Ids that keep upstream definitions but whose SDK table entry differs (160/161 vs FENCE_*, 242, 245)
    must be overridden by hand.
- A ~300-line hand-rolled v1 codec is also reasonable. The SDK only uses about 35 message types, and
  owning the parser makes the "accept either CRC" rule (2.1) trivial. Either way, put the CRC_EXTRA table
  from 2.1 in one place.

### 7.2 Suggested module layout

1. **`PvLink` (transport):**
   - TCP client to `192.168.1.12:20002` (fallback .11).
   - Coroutine read loop with a v1 parser and a single-writer send channel. Stamp sysid 255, compid 190 and
     seq++ on every send.
   - Optional UDP 14556 mode for bench testing against PX4 SITL.
2. **`HeartbeatService`:**
   - On the first heartbeat with autopilot=12, record drone sysid/compid and start the 1 Hz GCS heartbeat
     (type 6, autopilot 0, base_mode 0x81, custom_mode 0, system_status 0, version 3).
   - Keep the 3 s connect watchdog and the 3 s link-loss watchdog, and emit Connected / Lost / Recovered.
   - Expose `locatingType` from heartbeat.type 6/7.
3. **`ModeController`:**
   - Holds the two tables from 3.1. `setMode(SdkMode)` sends SET_MODE and awaits a heartbeat match with a 5 s timeout.
   - arm/disarm = SET_MODE(0x00010000, base 0) and await base_mode bit 7.
   - Keep the one-action-at-a-time rule.
   - `orbit(lat, lon, radius, speed, cw, pause)` = COMMAND_LONG 201 (3.2), then SET_MODE 0x000C0000.
4. **`MissionClient`:** the upload/download state machine from 3.4 (MISSION_ITEM float, cmd 16, frame 3).
   - Recommended improvement: also handle MISSION_REQUEST_INT / MISSION_ITEM_INT if the firmware ever sends them.
5. **`ParamClient`:**
   - Read and write by name, with a 1 s retry and 3 attempts. Keep a cache of last-known values.
   - Bit-cast ints into the float.
   - Apply the whitelist from 4.2 as a guard. Fill `param_type` deterministically (6 for PV_* ints, 9 for floats).
6. **`Telemetry`:** typed `StateFlow`s for ATTITUDE, VFR_HUD, SYS_STATUS, GPS_RAW_INT, GLOBAL_POSITION_INT,
   ALTITUDE, BATTERY_STATUS, HOME_POSITION, DISTANCE_SENSOR, MOUNT_STATUS/CONTROL, PV_INFOR and STATUSTEXT
   (mag-cal parser from 2.2). Drop the SDK's change thresholds, or keep them only for UI throttling.
7. **`Calibration`:**
   - Mag cal: COMMAND_LONG 241 p2=1 to 1/1, progress from STATUSTEXT, cancel with all zeros.
   - RC cal: 241 p1=1/2 to 1/6.
8. **`Activation`:** read `PV_RO_KEY_*` and `PV_RO_PSN_*` for display, read `PV_V_KEY_FLAG`, and optionally
   write `PV_V_KEY_FLAG` = intBitsToFloat(1) (serverless).
9. Camera / gimbal per doc 06.

### 7.3 Draft `powervision.xml` dialect

```xml
<?xml version="1.0"?>
<!-- PowerVision (PowerEgg / Vision+ 1.7.3) MAVLink v1 dialect, reconstructed.
     CRC_EXTRA values are OBSERVED in libpvsdk_api-release.so; messages marked crc-verified
     reproduce that CRC_EXTRA from this XML. For the others, hard-code crc_extra in the codec. -->
<mavlink>
  <include>common.xml</include>
  <version>3</version>
  <dialect>0</dialect>
  <enums>
    <enum name="PV_PX4_MAIN_MODE">
      <entry value="1" name="PV_MAIN_MANUAL"/>
      <entry value="2" name="PV_MAIN_ALTCTL"/>
      <entry value="3" name="PV_MAIN_POSCTL"/>
      <entry value="4" name="PV_MAIN_AUTO"><description>sub 3 LOITER, 4 MISSION, 5 RTL as reported</description></entry>
      <entry value="8" name="PV_MAIN_TAKEOFF"/>
      <entry value="9" name="PV_MAIN_LAND"/>
      <entry value="10" name="PV_MAIN_RTL"/>
      <entry value="11" name="PV_MAIN_SUPER_SIMPLE"/>
      <entry value="12" name="PV_MAIN_CIRCLE"/>
      <entry value="13" name="PV_MAIN_FOLLOW"/>
      <entry value="14" name="PV_MAIN_LOITER"/>
    </enum>
    <enum name="PV_INFOR_STATUS_FLAGS" bitmask="true">
      <entry value="64" name="PV_INFOR_RC_LOCATION"/>
      <entry value="128" name="PV_INFOR_TAKEOFF_LAND_POINT"/>
    </enum>
  </enums>
  <messages>
    <message id="159" name="PV_INFOR"><!-- len 20, crc 174, crc-verified -->
      <description>Take-off/landing point, RC location and alarm bits.</description>
      <field type="uint64_t" name="status" enum="PV_INFOR_STATUS_FLAGS">flags / alarm word</field>
      <field type="float" name="land_lat" units="deg">latitude</field>
      <field type="float" name="land_lon" units="deg">longitude</field>
      <field type="float" name="land_alt" units="m">altitude</field>
    </message>
    <message id="160" name="PV_CAMERA_COMMAND"><!-- len 2, crc 133: hard-code; see doc 06 -->
      <field type="uint8_t" name="cmd">camera command code</field>
      <field type="uint8_t" name="result">0 from GCS; result code from aircraft</field>
    </message>
    <message id="163" name="PV_GENERIC_PERIPHERAL"><!-- len 255, crc 213: hard-code -->
      <field type="uint8_t[255]" name="data">payload-mount sub-protocol frame</field>
    </message>
    <message id="189" name="HELM_CONTROL"><!-- len 2, crc 108, crc-verified -->
      <field type="uint8_t" name="mode">target/current mode</field>
      <field type="uint8_t" name="expect">1 open, 2 close, 3 change mode</field>
    </message>
    <!-- Ray/Dolphin ids, hard-code crc: 150 IDENTITY_T len21 crc70; 152 ESC_INFO len40 crc154;
         153 CUR_STATE len36 crc170; 154 SONAR_INFO len20 crc30; 155 SYS_INFO len8 crc38;
         161 ? crc12; 170 PV_LIGHT_CONTROL {uint32_t increment} crc55; 171 GIMBAL_CAMERA_CONTROL
         {uint32_t value} crc140; 188 ZCAMERA_STATUS {uint8_t status} crc93 -->
  </messages>
</mavlink>
```

- Note on the include: `common.xml` defines 160/161 differently in ardupilotmega only (FENCE_*). Common has
  no 160/161, so the include is clean.
- **Do not** include ardupilotmega.xml. If MOUNT_CONFIGURE/CONTROL/STATUS (156-158) are needed, copy them
  from ardupilotmega with crc 19/21/134.

### 7.4 Open items to confirm with a live capture (tcpdump on the phone, or a TCP proxy between app and 192.168.1.12:20002)

1. The drone sysid/compid (expected 1/1), and whether the RC also emits heartbeats with compid 6 on the same socket.
2. Whether HOME_POSITION (242) frames arrive with crc_extra 104 or 0 (table anomaly), and the same for 245.
3. Whether SET_MODE(0x00010000, base 0) really toggles arming on this firmware.
4. Whether MOUNT_CONTROL to target 255/190 is accepted (doc 06 suggests yes) and whether the firmware wants `save_position`.
5. Exact meanings of PV_INFOR status bits other than 0x40/0x80 (alarm word), and of COMMAND 411 replies.
