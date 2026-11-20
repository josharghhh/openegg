# 06 - PowerEgg camera and gimbal control

Scope: how Vision+ 1.7.3 (`com.powervision.gcs`) controls the PowerEgg camera and gimbal, what the wire
format is, and what a Kotlin port has to send and parse.

Evidence tags:
- **OBSERVED**: read directly in jadx Java (`file:line`, paths relative to
  `apk\decompiled\jadx\sources\com\powervision\`) or in Ghidra pseudocode (`Function@address`, from
  `re_work\out\libpvsdk_api-release.c` unless another library is named).
- **INFERRED**: a conclusion drawn from the evidence. It has not been checked against a live aircraft.

Notes on the native evidence:
- String literals were resolved from `apk\native\libpvsdk_api-release.so` with a Ghidra image base of
  0x10000 (`ghidra_addr - 0x10000 = ELF vaddr`). The literal pool value plus the PC constant gives the
  string address.
- `libpower_sdk.c` / `.functions.tsv` were still 0 bytes when this was written, so section 4 relies on
  Java and on raw strings from `libpower_sdk.so`.

---

## 0. Summary

| Question | Answer |
|---|---|
| Camera control transport | **MAVLink v1 on the normal flight link.** There is no separate camera socket for the PowerEgg. |
| Flight link | TCP to `192.168.1.12:20002`, or `192.168.1.11:20002` through the remote controller (OBSERVED `gcs/ui/fgt/fly/FlyFragment.java:974`, `gcs/ui/fgt/fly/FlyControlFragment.java:187,200`, `gcs/config/GlobalConfig.java:17-18`). |
| Camera actions (photo, record, mode, format, reset) | Custom MAVLink message **160 `PV_CAMERA_COMMAND`**: 2-byte payload, CRC_EXTRA 133. The drone sends the result back in the same message id. |
| Camera settings (ISO/EV/WB/size/...) | Standard **PARAM_SET / PARAM_REQUEST_READ / PARAM_VALUE** with `PV_CAM_*` names. The value is an **int32 carried in the float's raw bits**. |
| Gimbal | Tuning uses `PV_GIM_*` params. App gestures send standard **MOUNT_CONTROL (157)**. The aircraft reports gimbal state with **MOUNT_STATUS (158)** and MOUNT_CONTROL (157). Pitch is normally driven by the RC wheel, not by the app. |
| JSON-over-TCP camera code (`CameraTcp*`, `CameraMessageJsonParser*`, `Hisco*`) | Present in `libpvsdk_api-release.so`, but only `RayCameraManager` (PowerRay, `192.168.1.209:3009` / `192.168.1.100:8787`) creates it. **The PowerEgg does not use it.** |
| `libpower_sdk.so` / `com.powervision.w4camera` ("PVW4CameraSdk", Hisi, hi3559, `192.168.1.10`) | Used only by the `ship` UI (PowerDolphin). **Not needed for the PowerEgg.** |

---

## 1. Camera control path and message formats

### 1.1 Call chain (OBSERVED)

```
VFCameraManager.takePhoto()                        gcs/ui/manager/VFCameraManager.java:313-316
 -> PowerSDK.stillCapture() -> JniNatives.stillCapture()     powersdk/sdk/PowerSDK.java:520
 -> [wrapper] Java_..._stillCapture -> PVSDK_stillCapture    libpvsdk_api_wrapper.c @000347ac
 -> PVSDK_stillCapture@000e73c4 : Service_Api_cmdCamera(1, 0)
 -> Service_Api_cmdCamera@000e0494 -> DroneService::cmdCamera@000e295c
 -> MavlinkDrone::cmdCamera@000ddf34 -> CameraManager::sendCameraCmd@0009c87c
 -> CameraImpl::sendCameraCmd@0009ca00 : new PvCameraCommand; payload[0]=cmd;
    MavlinkDrone::sendMavlinkMessage(); start a 5000 ms timer (PvTimer::setTimer(7,0,5000,cameraCallback))
```

The aircraft class is chosen from the HEARTBEAT. In `mavMessageHandleThread@000db990`:
- sysid 4 means Dolphin, and sysid 2 means Ray.
- compid 6 or 7 means the remote controller.
- Anything else goes to `createConcreteDrone@000dea7c`. That function builds `PixHawk` when
  `autopilot == 12` (PX4) and `ArduPilotMega` when `autopilot == 3`.

The PowerEgg is the `PixHawk` path (INFERRED: it is a PX4-based aircraft that is not Ray or Dolphin).
`PixHawk::processMessage@000d68dc`, case `0xa0`, sends incoming msg 160 to
`CameraManager::processMessage`, which then calls `CameraImpl::processMessage@0009cc9c`.

### 1.2 MAVLink framing used by the SDK (OBSERVED)

- v1 frames (`STX=0xFE`). Every `*::encode` writes `0xFE`, and the CRC is X.25 with a per-message
  CRC_EXTRA (for example `PvCameraCommand::encode@000849d4`).
- GCS header in `MavlinkDrone::sendMavlinkMessage@000dc788`:
  - sysid is **0xFF** (0xFD when an internal flag at `this+0x84` is 1).
  - compid is **0xBE (190)**.
  - seq comes from a global counter.

### 1.3 Message 160 `PV_CAMERA_COMMAND` (PowerVision custom)

| Field | Offset | Type | Notes |
|---|---|---|---|
| `cmd` | 0 | uint8 | command code, see table below |
| `arg/result` | 1 | uint8 | GCS to drone: never written. `CameraImpl::sendCameraCmd` sets only `this[0x16]`, and `MavlinkMessage()@0007f5d0` does not zero byte `0x17` (OBSERVED). Send 0. Drone to GCS: result code. |

- len = 2, CRC_EXTRA = **133 (0x85)** (OBSERVED `PvCameraCommand::encode@000849d4`, `PvCameraCommand()@00084934` msgid 0xA0).
- `PvCommand` and `ZcameraCommandResult` also claim msgid 0xA0, but they have different lengths and CRCs
  (len 2/crc 133 and len 3/crc 100). `MavlinkConnection::addMessage@000802d8` decodes incoming 0xA0 as
  `PvCameraCommand` (OBSERVED). `ZcameraCommandResult` (len 3, crc 100) and `ZcameraStatus` (msg 188,
  len 1, crc 93) look like another product's dialect (INFERRED). The port should treat 160 as len 2,
  crc 133.

Example frames (seq 0, sysid 255, compid 190, byte 1 = 0; CRC computed with extra 133):

```
stillCapture  FE 02 00 FF BE A0 01 00 B1 9E
recStart      FE 02 00 FF BE A0 02 00 D5 71
recStop       FE 02 00 FF BE A0 03 00 09 2B
photo mode    FE 02 00 FF BE A0 04 00 0C A7
video mode    FE 02 00 FF BE A0 05 00 D0 FD
format SD     FE 02 00 FF BE A0 06 00 B4 12
factory reset FE 02 00 FF BE A0 07 00 68 48
```

#### Command codes, GCS to drone (OBSERVED: PVSDK_* @000e73b0..000e7430 and the switch in `CameraImpl::sendCameraCmd@0009ca00`)

| cmd | PVSDK entry | Java API (PowerSDK) | Used for PowerEgg UI |
|---|---|---|---|
| 1 | `PVSDK_stillCapture` | `stillCapture()` | shutter (`VFCameraManager.java:314`) |
| 2 | `PVSDK_recStart` | `recStart()` | start record (`:326`) |
| 3 | `PVSDK_recStop` | `recStop()` | stop record (`:381`) |
| 4 | `PVSDK_stillCaptureMode` | `stillCaptureMode()` | switch to photo mode (`:422`) |
| 5 | `PVSDK_recMode` | `recMode()` | switch to video mode (`:427`) |
| 6 | `PVSDK_formatSD` | `formatSD(6.0f)` | format SD (`gcs/ui/fgt/new_camera_set/EggPublicFragment.java:215`). The float argument is discarded. |
| 7 | `PVSDK_resetToCameraFactory` | `resetToCameraFactory(7.0f)` | camera factory reset (`EggPublicFragment.java:197`) |
| 9 / 10 | `PVSDK_changeCaptureMode(n)` sends `cmdCamera(n,0)` | `changeCaptureMode(int)` | visible/IR (FLIR) switch. Not used by the PowerEgg UI (INFERRED). |
| 0x0B / 0x0C / 0x0D | `stillCaptureFlir` / `recStartFlir` / `recStopFlir` | | FLIR variants |
| 0x0E | `PVSDK_calibration` | | camera calibration |
| 8 | (no case) | | still sent, but `payload[0]` is never set (bug in the original) |

`CameraImpl::sendCameraCmd` keeps only `cmd`. The second argument of `Service_Api_cmdCamera` (for example
the `6.0f` / `7.0f` floats, or the int argument of `recStart`) never reaches the wire (OBSERVED: the
function has two parameters, `this` and `cmd`, and only `this[0x16]` is written).

#### Responses, drone to GCS (OBSERVED `CameraImpl::processMessage@0009cc9c`)

Incoming msg 160: `payload[0]` echoes `cmd` and `payload[1]` is the result. The SDK maps them to
`cameraNotify(status)` (callback slot 0x38, which is wrapper `FUN_000311e0` calling `cameraNotify(I)I`):

| cmd | result 1 | result 2 | result 3 | result 4 | result 5 |
|---|---|---|---|---|---|
| 1 photo | 7 StillCaptureEnd | 9 StillCaptureError | 23 SDFull | 24 SDErr | 25 SDEmpty |
| 2 rec start | 2 RecRecing | 3 RecStartError | 26 RecSDFull | 27 RecSDErr | 28 RecSDEmpty |
| 3 rec stop | 1 RecStopEnd | 4 RecStopError | | | |
| 4 photo mode | 10 StillCaptureMode | 11 StillCaptureModeError | | | |
| 5 video mode | 5 RecMode | 6 RecModeError | | | |
| 6 format | 12 FormatSDSuccess | 13 FormatSDFailed | | | |
| 7 reset | 14 ResetFactorySuccess | 15 ResetFactoryFailed | | | |
| 9 | 30 CmdChangeCapture | | | | |
| 10 | 29 CmdChangeFlir | | | | |
| 0x0B / 0x0C / 0x0D | 107 / 102 / 101 (Java ignores these) | | | | |
| 0x0E | 37 FlirSuccess | 38 FlirFailed (any other result) | | | |

These `cmd` values come from the drone and need no `result`:

| cmd | cameraNotify status |
|---|---|
| 0x50 | 16 RecSettingSuccess |
| 0x51 | 17 RecSettingFailed |
| 0x60 | 18 CaptureSettingSuccess |
| 0x61 | 19 CaptureSettingFailed |
| 0x70 | 20 PictureSettingSuccess |
| 0x71 | 21 PictureSettingFailed |
| 0x7F | 22 FeedBackTimeout |

- If no msg 160 arrives within 5000 ms, `cameraCallback@0009cc54` raises status **22**
  (FeedBackTimeout) (OBSERVED).
- `CameraImpl::processResult@0009d734` handles msgid 0xBE (190) with codes 0x20..0x7F and 0xFD. No 190
  message class exists in `MavlinkConnection::addMessage`, so this path looks dead for the PowerEgg
  (INFERRED).

The `cameraNotify` status to Java callback table is in `powersdk/jni/JniNatives.java:1105-1219`.

### 1.4 Camera settings: MAVLink parameters `PV_CAM_*`

- **Set:** `PowerSDK.setParameter(name, Float.intBitsToFloat(intValue))` (for example
  `gcs/ui/fgt/new_camera_set/EggBasicFragment.java:122`). This goes through
  `ParameterManager::setParameter@000958c8` and becomes **PARAM_SET (23)**:
  - `param_value` is the raw 4 bytes of the int.
  - target is the drone sysid/compid (`getDroneSysId` / `getDroneCompId`).
  - `param_type` (offset 0x2e) is **never written** (OBSERVED), so the original sent heap garbage.
    INFERRED: the firmware ignores it. Use MAV_PARAM_TYPE_INT32 (6), or copy the type from the
    matching PARAM_VALUE.
  - The `PVSDK_set*` helpers (`setPicSize`, `SetWhiteBlance`, ...) do the same through
    `Service_Api_setCamera` and `MavlinkDrone::setCamera@000ddf1c`.
- **Get:** `requestParameter(name)` sends **PARAM_REQUEST_READ (20)** with `param_index = -1`
  (`ParameterManager::requestParameter@0009556c`). The reply is **PARAM_VALUE (22)**. The app then reads
  the cached value with `Float.floatToRawIntBits(getParameter(name))`
  (`VFCameraManager.java:203`, `EggPublicFragment.java:66`).
- **Bulk:** `requestParameter("PV_CAM_REQ_ALL")` (`powersdk/manage/CameraManager.java:33-37`). As each
  `PV_CAM_*` PARAM_VALUE arrives, `ParameterManager::transactCameraReqAll@000959e8` calls
  `cameraParamNotify(2, name)`. The names it recognises are:
  `PV_CAM_TYPE EV ISO WB LT SM STYLE ACUT P_FMAT SS P_SIZE V_SIZE V_FMAT AT_ZOOM MAN_ZOOM V_SYS V_QUALI
  PH_MODE LONG_EXP SCENE_M TV_MODE L_FREQ FORMAT SET_T1 SET_T2 SET_T3 V_OSD P_OSD AF_MODE KP_ZOOM
  CAF_DIS CAF_LVL V_S_S P_Q P_S_S APE_V EXP_MU WB_V BN_V ACUT_V CONTRA_V AUTO_OFF D_TIME D_P_NUM
  SATUR_V SD_CAP SD_LEFT SD_VLEFT OSD_ON SD_PLEFT` (all prefixed `PV_CAM_`; OBSERVED, resolved strings).
- Ack routing in `handleGetParam@00096950` (OBSERVED):
  - `PV_GIM_*` names (MODE, PITCH, P_30, P_SMT, Y_SMT, ANGLE, AUTO_CAL, RESET_PM, STATUS, ROLL_ADJ) go to
    `gimbalParamNotify(status, name)` (slot 0x48).
  - `PV_CAM_*` names go to `cameraParamNotify(status, name)` (slot 0x3c).
  - Everything else goes to `paramSetGetNotify` (slot 0x1c).
  - status: 0 = set success, 1 = set timeout, 2 = get success, 3 = get timeout
    (`JniNatives.java:994,1244,1279`).

#### PowerEgg setting values (OBSERVED `gcs/utils/DataController.java:56-258`, `gcs/model/CameraModel.java:68-118`)

The Egg* fragments set exactly the values below. `CameraEggSettingActivity.java:63,142` uses `value - 1`
as the list index.

| Setting | Param | Values |
|---|---|---|
| ISO | `PV_CAM_ISO` | 1 Auto, 2=100, 3=200, 4=400, 5=800, 6=1600 |
| EV | `PV_CAM_EV` | 1=-2.0, 2=-1.7, 3=-1.3, 4=-1.0, 5=-0.7, 6=-0.3, **7=0.0**, 8=+0.3, 9=+0.7, 10=+1.0, 11=+1.3, 12=+1.7, 13=+2.0 |
| White balance | `PV_CAM_WB` | 1 auto, 2 sunny, 3 cloudy, 4 incandescent, 5 fluorescent, 6 underwater, 7 outdoor |
| Metering | `PV_CAM_LT` | 1 average, 2 multi-point, 3 center-weighted |
| Style | `PV_CAM_STYLE` | 1 normal, 2 painting, 3 retro, 4 negative, 5 B&W, 6 color-enhance, 7 "70 years" |
| Sharpness | `PV_CAM_ACUT` | 1 sharp, 2 normal, 3 soft |
| Scene | `PV_CAM_SCENE_M` | 1 normal, 2 flash, 3 night, 4 sport, 5 landscape, 6 portrait, 7 sunset |
| Anti-flicker | `PV_CAM_L_FREQ` | 1 auto, 2 50 Hz, 3 60 Hz |
| Photo size | `PV_CAM_P_SIZE` | 2=4254x3264, 3=4000x3000, 4=3840x2160, 5=2560x1920 |
| Long exposure / shutter | `PV_CAM_LONG_EXP` | 1 off, 2=1/30 s, 3=1 s, 4=2 s, 5=5 s, 6=10 s, 7=20 s, 8=30 s, 9=60 s |
| Video resolution | `PV_CAM_V_SIZE` | 1 4K30, 2 1080p120, 3 1080p60, 4 1080p30, 5 720p240 |
| TV system | `PV_CAM_TV_MODE` | 1 NTSC, 2 PAL |

Other settings:
- **Contrast/saturation/brightness:** the PowerEgg has no working contrast command. `JniNatives.contrastSet`
  goes to `PVSDK_contrastSet@000e74f4`, which returns 0 and does nothing, and `captureSet` returns -1
  (wrapper @00034828) (OBSERVED). `PV_CAM_CONTRA_V`, `SATUR_V`, `BN_V`, `APE_V`, `WB_V`, `EXP_MU`,
  `V_S_S` and `P_S_S` use 51+ value codes and are used only by the `Eye*` (PowerEye) fragments.
- **Camera status:** `PV_CAM_STAT`. 1 = photo mode, 3 = recording (`VFCameraManager.java:205-222`).
  It is requested 1 s after start-up (`:262-268`).
- **SD card:** `PV_CAM_SD_LEFT` for PowerEgg (aircraft type 1) and `PV_CAM_SD_PLEFT` for type 2, which is
  PowerEye (`powersdk/manage/CameraManager.java:49-57`, `gcs/ui/aty/fly/PVFlightControllerActivity.java:281,2144-2160`).
  - Values in 0..204799 are capacity; anything else means "no SD" (`EggPublicFragment.java:66-74`).
    INFERRED: the unit is MB.
  - `PV_CAM_SD_CAP` in the same range sets `SD_MONTED = true` (`:83-90`).
  - `getSDCAP()` reads `PV_V_SD_CAP` (`PVSDK_getSDCAP@000e758c`).
- `getPicSize()` reads `PV_CAM_P_SIZE`. `setCameraZoom` and `setCameraAutoZoom` set `PV_CAM_MAN_ZOOM` and
  `PV_CAM_AT_ZOOM`. `setOSDShow` sets `PV_CAM_OSD_ON`.
- **`getRecordTime`** and the file natives (`lsFile`, `getFile`, `delFile`, `cdDirectory`, `format(String)`)
  belong to the PowerRay JSON path (`powersdk/sdk/PowerSDKCamera.java:248-334`). They do not apply to the
  PowerEgg.

### 1.5 Files and media

`VFCameraManager` has no remote file list or download path (OBSERVED: the whole class). It makes phone-side
copies from the live stream:
- On `onCameraStillCaptureEnd` it saves a stream screenshot (`TakeScreenShot`, `:146,319-322`).
- On `onCameraRecRecing` it starts recording the stream to MP4 (`StartRecord`, `:95,331-340`).
- It registers both in the local `DbHelper` gallery (`:659-675`).

The full-resolution files stay on the aircraft SD card. INFERRED: the 1.7.3 app has no SD-card browser
for the PowerEgg.

- The video stream is `VFVideo.OpenVideo("")`, the vxfly library default source (`:683-699`).
- The optional FPV stream is `rtsp://192.168.1.20:8554/1.3gp` (`:45`).
- `powerManager.initVFController()` starts `VFConfigController` (vxfly battery/signal), at
  `gcs/manager/PowerManager.java:483-488`.

---

## 2. Gimbal

### 2.1 Tuning parameters (OBSERVED `PX4Gimbal::*@0009dfd8..0009e15c`; names resolved)

`GimbalManager::createGimbal@0009de04` always builds a `PX4Gimbal`. Each setter calls
`GimbalManager::setParameter`, which sends a PARAM_SET with the int value in the float's bits.

| Java (PowerSDK) | Param | UI semantics |
|---|---|---|
| `setGimbalMode(m)` / `requestGimbalMode()` | `PV_GIM_MODE` | gimbal mode |
| `setPitchAxis(v)` / `requestPitchAxis()` | `PV_GIM_PITCH` | pitch axis **speed**. The UI sends `seekbar + 6` (`gcs/ui/fgt/fly/EggAdvanceSettingFragment.java:157`). |
| `extendPitchAxisLimit(e)` | `PV_GIM_P_30` | INFERRED: enables the extended (+30 deg up) pitch limit. **No caller in the app** (OBSERVED by grep). |
| `setPitchAxisSmooth(v)` | `PV_GIM_P_SMT` | `seekbar + 1` (`:178`) |
| `setYawAxisSmooth(v)` | `PV_GIM_Y_SMT` | `seekbar + 1` (`:196`) |
| `setAngularSpeed(f)` | `PV_GIM_ANGLE` | `Float.intBitsToFloat(seekbar + 6)` (`:214`) |
| `resetGimbalParams()` | `PV_GIM_RESET_PM` = 0 | reset (`:256`) |
| `gimbalCalibrate()` | `PV_GIM_AUTO_CAL` = 0 | auto calibration |
| `rollAxisTuning(v)` / direct `setParameter` | `PV_GIM_ROLL_ADJ` | roll trim as int(0.1 deg), clamped to +/-5.0 deg (`gcs/ui/aty/fly/PVFlightControllerActivity.java:1860-1876`) |
| read only | `PV_GIM_STATUS`, `PV_GIM_VER` | status and version |

Acks arrive as `gimbalParamNotify` (section 1.4).

### 2.2 Pointing commands

- **`setGimbalOrientation(pitch, roll, yaw)`** goes to `PX4Gimbal::setGimbalOrientation@0009df4c` and
  builds a standard **MOUNT_CONTROL (157)** (OBSERVED).
  - Layout: `input_a = pitch`, `input_b = roll`, `input_c = yaw` (int32).
  - `target_system` / `target_component` come from `GimbalManager::getSysId/getCompId`. Those resolve
    through `MavlinkDrone::getSysId@000db6c8` to the SDK's own ids (`DroneService()@000e1bb0` sets 0xFF and
    0xBE), so the target is 255/190 (INFERRED; check against a capture).
  - `save_position` is not written.
  - len 15, CRC_EXTRA 21 (OBSERVED `MountControl::encode@00085028`).
- The **only caller** is the on-video gesture joystick, `gcs/view/CustomGestureView.java:133-174`, which
  `PVFlightControllerActivity` shows only for the VF camera path (`:1276`). On every scroll event held for
  at least 200 ms it sends:
  - drag right: yaw -400 (small drag) or -800 (drag over 60 px)
  - drag left: yaw +400 or +800
  - drag up: pitch -400 or -800
  - drag down: pitch +500 or +1000

  INFERRED: these fixed magnitudes, sent repeatedly, are **rate** commands (likely cdeg/s), not
  absolute angles.
- **`gimbalControl(mode, stab_pitch, stab_roll, stab_yaw)`** goes to `MavlinkDrone::gimbalControl@000de434`
  and builds **MOUNT_CONFIGURE (156)**: target 1/1, `mount_mode`, then three stab bytes; len 6, crc 19
  (OBSERVED). The pseudocode stores the second Java argument in the `stab_roll` slot, so the argument order
  through the wrapper is unverified. **No caller in the app.**
- `setGimbalPitch(int)`: `PixHawk` has no override, and `Drone::setGimbalPitch@000d5ae0` is a 4-byte stub.
  Only `Ray` and `Dolphin` implement it, so **it does not work on the PowerEgg** (OBSERVED via tsv).
- The physical pitch wheel on the RC drives the gimbal directly. The app only displays the result
  (section 3).

### 2.3 Gimbal telemetry (OBSERVED `PixHawk::processMessage@000d68dc`)

- **MOUNT_STATUS (158)** (len 14, crc 134): `pointing_a/b/c` are converted to float and stored in the
  SDK's local key `"mount_status"` (`MavlinkDrone::setParameter`). No Java callback fires. The Java
  side has no accessor for it in 1.7.3 (OBSERVED: no `mount_status` reader in JniNatives).
- **MOUNT_CONTROL (157) from the aircraft or RC** calls `mountControlDataNotify(pitch, roll, yaw)` (slot
  0x64), which reaches `RemoteControlCallback.MountControlDataListener` (`powersdk/jni/JniNatives.java:1460`).
  It is shown /10 in `gcs/ui/fgt/fly/ControlCalibrationFragment.java:95-135` (RC wheel calibration).

---

## 3. Java callbacks: when they fire and what they carry

Native-to-Java slot map: the wrapper `JniNatives_registerCallback@0002f4ec` fills the struct and
`PVSDK_registerCallBack@000e6908` copies index i to offset 4*i. Each slot was resolved to the wrapper
function and the Java method name it calls.

| Slot | Java static (JniNatives) | Trigger | Data |
|---|---|---|---|
| 0x38 | `cameraNotify(int)` :1105 | msg 160 reply, or 5 s timeout | status table in section 1.3. Routes to `CameraBase` and then `CameraType` listeners (`powersdk/base/CameraBase.java:61-229`). |
| 0x3c | `cameraParamNotify(int,String)` :1244 | PARAM_VALUE for `PV_CAM_*`, or REQ_ALL fan-out | 0/1/2/3 + name. `VFCameraManager` reads the value with `getParameter` (`:196-232`). |
| 0x48 | `gimbalParamNotify(int,String)` :1279 | PARAM_VALUE for `PV_GIM_*` | 0/1/2/3 + name |
| 0x1c | `paramSetGetNotify(int,String)` :994 | other params | 0/1/2/3 + name |
| 0x64 | `mountControlDataNotify(pitch,roll,yaw)` :1460 | MOUNT_CONTROL (157) received | int32 x3 |
| 0x50 | `requestOperationalSensitivityNotify(int,float)` :1324 | (not traced) | 0 = pitch, 1 = roll |
| 0x70 | `helmControlStatusNotify(mode,expect)` :1502 | msg 189 HelmControl | not camera related |

`VFCameraManager` reacts to these `CameraType` values (`gcs/ui/manager/VFCameraManager.java:72-193`):

| CameraType | VFCameraManager action |
|---|---|
| RecMode | UI goes to video mode |
| RecRecing | `isRecording = true`, starts the local MP4 |
| RecStopEnd | stops the local MP4, then `requestCameraSdCard()` |
| RecStartError / RecStopError | toasts |
| StillCaptureMode | UI goes to photo mode |
| StillCaptureEnd | local screenshot + sound |
| StillCaptureing | "taking photo" UI |
| FeedBackTimeout | camera timeout toast |
| SDFull / SDEmpty | toasts |
| Format success / fail | toasts |
| ResetFactory success | switches to video mode after 4 s |
| ParamGetSuccess / ParamSetSuccess | forwards the param name |

---

## 4. Does W4 / `libpower_sdk.so` apply to the PowerEgg? **No.**

- `com.powervision.w4camera.jni.JniNative` (61 natives, `System.loadLibrary("power_sdk")`, `:134`) is used
  only from `gcs/ui/aty/ship/*`, `gcs/ui/fgt/ship/*` and `gcs/view/ship/ShipMenuView.java` (OBSERVED by
  grep). `ShipMainActivity.java:614-623` calls `PowerCameraSDK.initCamera(0)` and plays
  `rtsp://192.168.1.10/livestream/12`.
- The W4 HTTP base `http://192.168.1.10/` is used only by ship adapters and activities.
  `gcs/utils/ship/ShipCameraGetParamsHttputils.java:12-14` calls `.../cgi-bin/hi3559/...cgi`, which is a
  HiSilicon Hi3559 camera.
- Strings in `libpower_sdk.so` include `192.168.1.10`, `Hisi*`, `CameraHttpCommandConnection`,
  `CameraTcpStatusConnection`, `CameraTcpUpdateConnection`, `Common*Strategy.cpp` and rapidjson
  (OBSERVED, raw strings). That is the PowerDolphin ("ship") camera stack. INFERRED: "W4" is the Dolphin
  camera module.
- The similar JSON/TCP classes inside `libpvsdk_api-release.so` (`CameraTcpControlConnection`,
  `CameraTcpDataConnection`, `CameraMessageJsonParser[Ex]`, `HiscoCameraCommandSender`, `Hisco*Command`)
  are built only by `RayCameraManager()@000af754`. It connects control to `192.168.1.209:3009` and data to
  `192.168.1.100:8787`, and only `MavlinkDrone::createRay` (HEARTBEAT sysid 2) creates it (OBSERVED). That
  is PowerRay, not PowerEgg, so the JSON key layout was not documented here.

**The Kotlin port for the PowerEgg needs neither `libpower_sdk.so` nor the JSON camera channel.**

---

## 5. Kotlin port plan

1. **MAVLink core:** reuse the flight-link codec (v1, X.25 CRC). The GCS should send as sysid 255 /
   compid 190 to match the original. Register CRC_EXTRA for these messages:

   | Message | CRC_EXTRA | Payload length |
   |---|---|---|
   | 160 | 133 | 2 |
   | 156 | 19 | 6 |
   | 157 | 21 | 15 |
   | 158 | 134 | 14 |
   | 20 | 214 | |
   | 22 | 220 | |
   | 23 | 168 | |

   Reject or ignore msg 160 frames that do not have length 2.
2. **`CameraController`:**
   - Provide `takePhoto()=cmd(1)`, `startRec()=cmd(2)`, `stopRec()=cmd(3)`, `photoMode()=cmd(4)`,
     `videoMode()=cmd(5)`, `formatSd()=cmd(6)`, `factoryReset()=cmd(7)`. Each sends
     `[cmd, 0]` on msg 160.
   - Keep one pending command with a 5 s timeout, then emit `Timeout`.
   - Parse incoming msg 160 `(cmd, result)` into a sealed `CameraEvent` using the table in section 1.3,
     including the unsolicited 0x50..0x7F codes.
3. **`CameraSettings`:**
   - Write a typed enum per `PV_CAM_*` param with the value tables from section 1.4.
   - `set(param, v)` sends PARAM_SET with `java.lang.Float.intBitsToFloat(v)`, `param_type = 6`
     (verify on hardware), and the target set to the drone sysid/compid from the HEARTBEAT.
   - `refreshAll()` sends PARAM_REQUEST_READ `"PV_CAM_REQ_ALL"` with index -1, then collects PARAM_VALUE
     for the names listed in section 1.4.
   - On start-up read `PV_CAM_STAT` (1 = photo, 3 = recording) and `PV_CAM_SD_LEFT`.
   - Retry and timeout like the SDK: there is a send list plus a watchdog; the timeout value was not
     extracted.
4. **`GimbalController`:**
   - Provide setters for `PV_GIM_PITCH`, `P_SMT`, `Y_SMT`, `ANGLE`, `MODE`, `ROLL_ADJ` (0.1 deg, +/-50),
     `RESET_PM`, `AUTO_CAL`, `P_30`.
   - `nudge(pitchRate, yawRate)` sends MOUNT_CONTROL at about 5 Hz while the user drags, using the
     original magnitudes (+/-400/800, +500/+1000).
   - The target ids are the one open question. Start with 255/190 to copy the original, and fall back to
     the drone sysid/compid if the gimbal ignores it.
   - Show MOUNT_STATUS (158) `pointing_a` (pitch) on the HUD; the original only cached it. Keep the
     MOUNT_CONTROL (157) RC-wheel echo for calibration.
5. **Video and media:**
   - Keep the vxfly/VF stream path for preview (separate task).
   - Photo or record locally from the stream on the `StillCaptureEnd` / `RecRecing` events, as the
     original did.
   - No SD download for the PowerEgg.
6. **Leave out:** `libpower_sdk.so`/W4 (Dolphin), `PowerSDKCamera`/`RayCameraManager` JSON TCP (Ray),
   `setGimbalPitch`, `captureSet`, `contrastSet`.
7. **Verify on hardware (open items):**
   - the `param_type` the firmware expects in PARAM_SET
   - the MOUNT_CONTROL target ids and units
   - whether `PV_CAM_SD_LEFT` is in MB
   - whether msg 190 is ever sent
   - the `PV_GIM_P_30` semantics
