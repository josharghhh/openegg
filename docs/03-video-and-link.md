# 03 - PowerEgg video feed and phone-to-station link

Source: jadx output of Vision+ `com.powervision.gcs` v1.7.3
(`apk\decompiled\jadx\sources\`), plus printable strings pulled from the 32-bit libs in
`apk\native\` with a Python string scanner. Nothing was executed or disassembled.

Labels:

- **OBSERVED** means it is in the decompiled Java (path:line, relative to `sources\`) or is a literal string in a `.so` (`lib:string`).
- **INFERRED** means it is my reading of the evidence. Test it on the drone before you rely on it.

Path abbreviations: `gcs/` = `com/powervision/gcs/`, `psdk/` = `com/powervision/powersdk/`,
`vf/` = `com/vxfly/vflibrary/`, `vfsdk/` = `com/powervision/vfsdk/`.

---

## 0. Summary

| Item | Value | Label |
|---|---|---|
| Phone joins | Base-station 5 GHz Wi-Fi AP. The manual and the app help text say `Power_Ground…` / `PowerVision_GroundX` (pw `1234567890`). The code only matches `PowerEgg_Station_` explicitly; any other SSID falls through to the PowerSDK connect (section 1.1) | OBSERVED `gcs/config/GlobalConfig.java:22`, `gcs/ui/fgt/fly/FlyFragment.java:1674`, `1708-1711`, `apktool/res/values/strings.xml:823` |
| USB/OTG | Dormant Vxfly AOA tunnel; not used by the PowerEgg (section 3.1) | OBSERVED `vf/usb/VFUSBManager.java:232-234` |
| Link setting (channel, bitrate) | `VFConfig.SetDlChannel`+`ApplyDlConfig` (ground), `SetVideoRate(1-4 Mbps)` (air) | OBSERVED `gcs/ui/fgt/fly/FlyStationFragment.java:164-185` |
| Flight-control link (MAVLink, inside native PowerSDK) | TCP client to **192.168.1.12:20002** | OBSERVED `FlyFragment.java:973-974`, `GlobalConfig.java:18-19` |
| Remote-controller / station MAVLink (RC firmware-upgrade path only) | TCP **192.168.1.11:20002** | OBSERVED `FlyFragment.java:1413`, `gcs/ui/fgt/fly/FlyControlFragment.java:200` |
| Station/datalink config ("VF" ground/air units) | native `libvfconfig` over the network (USB is hard-disabled) | OBSERVED `vf/usb/VFUSBManager.java:232-234` |
| Live video | `VFVideo.OpenVideo("", 2000, false)`. Native `libvfvideo` = live555 RTSP client + RTP/H.264 depacketizer, then FFmpeg software decode **or** Java `MediaCodec` (`video/avc`) | OBSERVED (sections 2.1-2.3) |
| Default video URL when the URL is `""` | **not visible**: no IP, port or path string for it exists in `libvfvideo.so` | OBSERVED (absence); this is the main open item |
| `udp://0.0.0.0:60430?///pkt_size=655360` | only in the legacy `VideoViewController` "switch camera" toggle. Not on the v1.7.3 PowerEgg screen | OBSERVED `gcs/ui/controller/VideoViewController.java:904-926` |
| `rtsp://192.168.1.20:8554/1.3gp` | FPV toggle, shown only for `aircraftType == 2` (PowerEye) | OBSERVED `gcs/ui/manager/VFCameraManager.java:45`, `gcs/ui/aty/fly/PVFlightControllerActivity.java:1899-1906` |

---

## 1. Connection sequence from app start

### 1.1 Wi-Fi SSID detection

1. `FlyFragment.processLogic()` registers `WifiSsidutil` with a `WifiSSIDCallBack` on API < 26 (OBSERVED `FlyFragment.java:965`). On API >= 26 it registers in `onStart()`, and only if the GPS provider is enabled. Otherwise it shows a "turn on location" dialog (OBSERVED `FlyFragment.java:1078-1090`). Android hides the SSID without location, which is why.
2. `WifiSsidutil` is a `BroadcastReceiver` for `RSSI_CHANGED`, `STATE_CHANGE` and `WIFI_STATE_CHANGED`. On `NetworkInfo.State.CONNECTED` it reads `WifiManager.getConnectionInfo().getSSID()`, strips the quotes and calls `onSsidCallBack(ssid)` once per connection. On DISCONNECTED, or Wi-Fi off, it calls `onWifiDisconnect()` (OBSERVED `gcs/utils/WifiSsidutil.java:45-119`).
3. `WifiSSIDCallBack.onSsidCallBack` checks prefixes in this order (OBSERVED `FlyFragment.java:1656-1721`):

| Order | Prefix | Product | Action |
|---|---|---|---|
| 1 | `PSE_` (length <= 10) | sonar | `connectSonarDevice()` |
| 2 | `PRA_Station_`, `PSE_Station_` | PowerRay | `initPowerSDK()` |
| 3 | **`PowerEgg_Station_`** (if `pvSdk == null && vfConfig == null`) | **PowerEgg** | `initPowerSDK()`, then `initVFConfig()` if `NetUtils.isConnected()`, then `disConnectPVSonar()` |
| 4 | `PowerEye_Station_` | PowerEye | same as PowerEgg |
| 5 | sonar SSIDs | PV sonar | `connectPVSonar()` |
| 6 | `Atheros_XSpan_` | test station | same as PowerEgg |
| 7 | `pvw4`, `PDO_BOAT_`, `PowerEgg_` | boat / PowerDolphin | `initPowerSDK()` |

| 8 | **anything else** (no boat prefix), or a boat SSID when `pvSdk != null` | default | `disConnectPVSonar()` + `initPowerSDK()`. **No `initVFConfig()`** |

   `PowerEgg_Station_` is tested before the boat prefix `PowerEgg_`, so a station SSID never takes the boat branch (OBSERVED, same lines). Your Kotlin matcher needs the same order.

   **SSID mismatch with the manual.**
   - The PowerEgg manual (`docs/reference/PowerEgg-user-manual.txt:1848-1856`, `1981`) says the base-station AP is `Power_Groundxxx` / `PowerVision_GroundX`, password `1234567890`.
   - The app's own help string says the same: `how_connect_third` = "Connect to wifi: Power_Groundxxxx with password: 1234567890" (OBSERVED `apktool/res/values/strings.xml:823`).
   - **No Java code matches `Power_Ground` or `PowerVision_Ground`** (OBSERVED by grep of `gcs/`).

   So the prefixes the app recognises for a PowerEgg are:
   - `PowerEgg_Station_` (explicit),
   - `Atheros_XSpan_` (test station, same handling),
   - `PowerEye_Station_` (same handling),
   - and, for a `Power_Ground…` SSID, the **catch-all row 8**. Any unrecognised SSID still starts the PowerSDK TCP link to 192.168.1.12:20002. The PowerEgg identity then comes from `PV_V_TYPE`.

   With a `Power_Ground…` SSID, `FlyFragment` never calls `initVFConfig()`. The VF station link only starts on the flight screen (`PowerManager.initVFController()`) or in station settings (`FlyStationFragment`).

   `NetUtils.isConnectingPlane()` (used to decide "is the phone on the drone Wi-Fi, so don't try the cloud") also does not list `Power_Ground` (OBSERVED `NetUtils.java:150-155`).

   **New app:** match `Power_Ground`, `PowerVision_Ground`, `PowerEgg_Station_`, `PowerEye_Station_` and `Atheros_XSpan_`, and fall back to probing 192.168.1.12:20002 on any Wi-Fi with a 192.168.1.0/24 address.
4. `NetUtils.isConnected()` only checks `getActiveNetworkInfo().isConnectedOrConnecting()` (OBSERVED `gcs/utils/NetUtils.java:30-34`). `NetUtils.isConnectingPlane()` lists all the drone prefixes (OBSERVED `NetUtils.java:150-155`).
5. The product type is not known from the SSID alone. After the drone connects, the app reads MAVLink param `PV_V_TYPE`. Values 1 and 3 map to PowerEgg (banner index 0) and 2 maps to PowerEye (index 1) (OBSERVED `FlyFragment.java:385-392`, `1584-1628`, banner list `1226-1232`). Type 3 shows an "incompatible" warning (OBSERVED `FlyFragment.java:851-852`). That value is passed to the flight screen as `flyType` (OBSERVED `FlyFragment.java:1238`, `PVFlightControllerActivity.java:869`).

### 1.2 Which sockets open, in which order

**A. PowerSDK / MAVLink (native `libpvsdk_api-release.so` + `libpvsdk_api_wrapper.so`)**

- `initPowerSDK()` calls `PVSdk.instance().initSDK("192.168.1.12", 20002)`. `connType` is hard-coded `true`, so the `RAY_TCP_SERVER_IP` branch is dead (OBSERVED `FlyFragment.java:219`, `970-979`).
- `PVSdk` is a state machine driven by `Handler.postDelayed`. Each step retries every **3100 ms** (OBSERVED `psdk/PVSdk.java:187-234`):
  1. `INIT`: `PowerSDK.initSDK(ip, port)` → `JniNatives.initSDK(String,int)`. On success (0): `initRayUuid(Build.SERIAL×2)`, `setRayBaseStationAddress("192.168.1.11", 8080)`, `registerCallback()`, `setConnectionListener(this)`, then state CONN (`PVSdk.java:196-205`).
  2. `CONN`: `PowerSDK.connect()` → `JniNatives.connect()` (`PVSdk.java:208-233`). The native side reports `onConnectSuccess`, which sets state DRONE (`PVSdk.java:295-301`).
  3. `DRONE`: `PowerSDK.connectDrone()` → `JniNatives.connectDrone()` (`PVSdk.java:216-223`). The native side reports `onDroneConnected` and the loop stops (`PVSdk.java:375-386`, `connSuccess()` at `236-243`).
- Native transport (OBSERVED in the Ghidra pseudocode `<ghidra-out>/libpvsdk_api-release.c`):
  - `MavlinkConnectionManager::createMavlinkConnection` (@0x8d674) picks the link type from the init params: **0 = `SerialConnection`, 2 = `UdpConnection`, 3 = `TcpConnection`**.
  - `TcpConnection::openConnection` (@0x8260c) copies the IP string, takes the port from offset 0x10 of the same init struct (byte-swapped to network order), calls `socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)`, sets `O_NONBLOCK`, `inet_aton`, ignores `SIGPIPE`, and starts a reader thread.
  - The JNI `initSDK(String,int)` (`libpvsdk_api_wrapper.c` @0x2f2d0) builds that struct (ip @0, port @0x10, and the rest from a constant block) and calls `PVSDK_initSdk`. **INFERRED:** that constant selects type 3 (TCP), because TcpConnection reads ip and port from exactly that layout.
  - `SerialConnection::openPort` (@0x80df0) does `open(path, O_RDWR|O_NOCTTY|O_NONBLOCK)` and sets 8N1 at the given baud. That is a kernel tty path (for example `/dev/ttyS*`). Java never uses it (`initSDKSerial` has no callers).
  - `SerialConnection::sendMessage` writes a **raw MAVLink v1 frame** (`len + 8` bytes). **INFERRED:** TCP uses the same raw MAVLink v1 stream, because both subclasses share `MavlinkConnection`.
- **INFERRED:**
  - `connect()` opens the TCP link to `192.168.1.12:20002` (the "chain").
  - `connectDrone()` starts the MAVLink session and heartbeat over that link. Evidence: `libpvsdk_api-release` strings `MavlinkClient.cpp`, `MavlinkMessageProcessor`, `heartBeatThreadId`, `stopHeartBeatAPM`, `stopHeartBeatPX4`, and the literal `192.168.1.12` next to the `MavlinkMessage` strings.
  - **Check the TCP-vs-UDP split on the wire.** The lib also contains `stopUdpRead`/`stopUdpWrite`, and `GlobalConfig.DEFAULT_UDP_SERVER_PORT = "6009"` (OBSERVED `GlobalConfig.java:20`) is never referenced from Java. A UDP side channel on 6009 is possible but not proven.
- `connectControlChannel()` / `connectDataChannel()` (`psdk/sdk/PowerSDKCamera.java:248-258`) are **never called from Java** (OBSERVED by grep). Native strings name them `cameraStopTcpReadControl` / `cameraStopTcpReadData` and `controlConnectionDoc`. **INFERRED:** they are the camera's TCP command and data channels (the Ambarella-style 7878/8787 pair from doc 02, or the PowerRay `RayCameraManager` at `192.168.1.100`). The PowerEgg app path does not use them.
- `PowerSDK.initSDKSerial(name, baud)` exists (`psdk/sdk/PowerSDK.java:306-308`) with `DEFAULT_USB_BAUD_RATE = "115200"` (`GlobalConfig.java:21`), but nothing calls it (OBSERVED by grep). No USB-serial link is used for the PowerEgg.

**B. VF (Vxfly) datalink config: `libvfconfig.so`**

- `initVFConfig()` (OBSERVED `FlyFragment.java:993-1020`) creates `VFConfigController` → `VFConfig.defaultConfig()`, which is `new VFConfig(false)` (usesUSB = false) (OBSERVED `vf/config/VFConfig.java:65-70`). If the ground unit is not connected, it calls `TryConnectGround(1000)`, which is `execute(ptr, ground=true, CMD_Connect=81, 1000*1000, null)` (OBSERVED `VFConfig.java:531-536`).
- On `didGroundConnect`, the callback type 1 path calls `GetGroundSWVersion()` and shows the "connecting" view (msg 500). On `didGroundConnectFailed`, type 2 hides it (msg 600) (OBSERVED `vfsdk/VFCallback.java:73-92`, `FlyFragment.java:996-1013`, `809-814`). The ground SW version string `PV-FTA-S-V…` drives the station-upgrade prompt (OBSERVED `FlyFragment.java:1005-1009`, `1590-1611`).
- On the flight screen, `PowerManager.initVFController()` starts a timer: first run after 1 s, then every **5 s**. If the ground unit is not connected it calls `TryConnectGround(2000)`. If it is connected it calls `GetGroundBatteryQuantity()`, `GetGroundDlRadioStatus()` (RSSI/SNR/noise) and `GetGroundSWVersion()` once (OBSERVED `gcs/manager/PowerManager.java:483-488`, `vfsdk/VFConfigController.java:46-104`).
- Native evidence (OBSERVED `libvfconfig.so` strings):
  - Command names: `Heartbeat Connect Connected Close GetApSSID SetApSSID … GetVideoResolution SetVideoResolution GetVideoRate SetVideoRate … UpdateFirmware UpdateDlFirmware`.
  - IPs: `192.168.1.11`, `192.168.1.12`, `192.168.1.2`, `192.168.1.240`, `0.0.0.0`, `255.255.255.255`.
  - Classes: `VFNetSocket`, `VFUSBSocket`, `VFAsyncSocket`, `VFProtocolPacket`, `VFProtocolSocket`, `VFTelnetParser`, `VFConsoleParser`, `VFPddlPacket`.
  - A factory telnet login exists (credentials redacted; OpenEgg never uses it).
  - Microhard-style AT commands: `at+mwfreq`, `at+mwvmode`, `at+mwnetworkid`, `at+mwvencrypt`, `at+mwstatus`, `at+mssysi`.
  - An embedded FTP server (`VFFTP`, `ftp://%d.%d.%d.%d:52222/%s`).
- **INFERRED:**
  - Ground unit (the station with the Wi-Fi AP) = **192.168.1.11**. Air unit (on the drone) = **192.168.1.12**.
  - The two are bridged by a Microhard pDDL-class IP radio. `CMD_Connect` / `Heartbeat` is a Vxfly binary protocol (`VFProtocolPacket`) over UDP (broadcast-capable, hence `255.255.255.255`) or TCP to those IPs.
  - Some settings are pushed by telnet + AT commands.
  - Firmware update makes the **phone** serve the image over FTP on port 52222, and the station fetches it. `UpdateFirmware(path, phoneIp)` passes the phone's Wi-Fi IP as an int (OBSERVED `FlyFragment.java:1609`, `gcs/utils/StationUpgradeManager.java:180-186`).
  - Port numbers for the VF protocol are not in the strings. Capture them on the wire.

**C. Video (`libvfvideo.so`)**: see section 2. It opens after the flight screen starts, 500 ms after `initVF()` (OBSERVED `VFCameraManager.java:684-690`).

**Order (OBSERVED):** SSID match → PowerSDK INIT/CONN/DRONE loop (192.168.1.12:20002) → VFConfig `TryConnectGround` (same callback) → user taps Start → `PVFlightControllerActivity` → `VFCameraManager.initVF()` + `OpenVideo("")` → `PowerManager.initVFController()` 5 s poll.

### 1.3 Disconnect and heartbeat detection

- **Native-driven callbacks** (`SystemStatusCallback.ConnectionListener` → `PVSdk` → `PowerSdkHelper.ConnectListener`): `onConnecting/onConnectSuccess/onDisConnected/onConnectTimeout/onConnectFailed/onHeartbeatTimeout/onDroneConnecting/onDroneConnected/onDroneDisConnected/onDroneConnectTimeout/onDroneConnectFailed/onDroneConnectedReplay(=heartbeat recovered)` (OBSERVED `PVSdk.java:287-449`, dispatch `105-164`). Timeout values are inside the native code (not visible).
- `onConnectTimeout` re-posts the `Conn` task after 3100 ms (`PVSdk.java:313-319`).
- `onHeartbeatTimeout` marks the drone disconnected, schedules a full `connDrone()` restart after **10 s** and starts a seconds counter (`PVSdk.java:322-332`). `onDroneConnectedReplay` cancels that restart (`PVSdk.java:420-428`).
- `FlyFragment` reacts to `onChainDisconnected` / `onHeartbeatTimeout` by posting `ConnectStatusEvent(false)` and resetting the UI. On heartbeat recovery it re-requests the parameters (`FlyFragment.java:441-545`).
- Wi-Fi loss: `WifiSsidutil.onWifiDisconnect()` (`FlyFragment.java:1724-1732`).
- Video loss: `VFVideo.SetCloseAfterLostFrame(60)` (OBSERVED `VFCameraManager.java:279`). **INFERRED:** native closes the stream after 60 lost frames and reports `didClose(VF_LostFrame = -10)`, and `VFCameraManager` then reopens it (`VFCameraManager.java:570-597`). `didOpen` results other than 1 (success) trigger reopen loops (`VFCameraManager.java:508-551`).

---

## 2. Live video (PowerEgg)

### 2.1 Which class and player

- v1.7.3 PowerEgg flight screen = `PVFlightControllerActivity`. `initCameraMenuView()` has `if (0 == 1) { AmbaCameraManager } else { VFCameraManager }`, so it always uses `VFCameraManager` (OBSERVED `PVFlightControllerActivity.java:1268-1289`).
- `FlyControllerActivity` + `VideoViewController` (the 60430 UDP toggle) is never started: there is no `FlyControllerActivity.class` reference outside its own files (OBSERVED by grep). Treat it as legacy.
- Renderer: `VFSurfaceView extends TextureView`. Its `Surface` is passed to native through `glSetWindow(surface, recreate, w, h)` (OBSERVED `vf/video/VFSurfaceView.java`, `VFVideo.java:387-404`).
- ijkplayer and `com.appunite.ffmpeg.FFmpegPlayer` are **not** used for the PowerEgg. ijk only shows up as a URL-extra constant in web views, and FFmpegPlayer is PowerRay/Dolphin only (OBSERVED by grep, consistent with doc 02).

### 2.2 Transport

- Main feed: `mVideoUrl = ""` → `VFVideo.OpenVideo("", 2000 /*MapConstants.ROUTE_START_SEARCH*/, false)` (OBSERVED `VFCameraManager.java:68`, `590`, `687`, `696`; `gcs/poly/MapConstants.java:20`). The `2000` is **INFERRED** to be a timeout in ms (`VFCameraManager.DEFAULT_TIMEOUT = 5000` is unused).
- `libvfvideo.so` native classes (OBSERVED strings): `VFPlayer`, `VFDataSource`, **`VFRtspDataSource`** (live555: `RTSPClient`, `MediaSession`, `H264VideoRTPSource`, `sprop-parameter-sets`, `Get SDP description`, `Start play session`, `Transport: RTP/AVP…`, `/TCP;unicast`), `VFUSBDataSource` / `VFUSBProtocol` / `VFUSBProtocolRtpReceiver`, `VFRtpSink`, `VFRtpPacker`, `VFDecoder` / **`VFFFMpegDecoder`** / **`VFCustomDecoder`**, `VFOpenGLView` (YUV420p → RGB GLSL shader).
- USB tunnel protocol strings, shared by vfvideo and vfconfig (OBSERVED): `Send RTSP connect command, tag = %d, url = %s`, `Send TCP connect command, tag = %d, host = %s, port = %d`, `Receive RTP packet, length = %d`, `Receive feedback, tag = %d, result = 0x%02x, error = 0x%02x, last skip = %d bytes`. **INFERRED:** this framing only exists for the Android Open Accessory path to a `Vxfly, Inc.` / `VTS Ground` accessory (OBSERVED `VFUSBManager.java:105-107`). That path is dead because `isUSBAvailable()` returns `false`, `initUSB()` is never called and the manifest has no accessory filter (OBSERVED `VFUSBManager.java:232-234`, grep, `resources/AndroidManifest.xml`).
- **No default URL, IP or port for the `""` case exists as a string in `libvfvideo.so`.** It has no `192.168.*` and no `:554` / `:8554`. The only `rtsp://` is live555's own parser. **INFERRED:**
  - The native code builds the URL from numbers (`%u.%u.%u.%u` exists), or gets it through VFConfig.
  - It is most likely an **RTSP session to the air unit / ground unit (192.168.1.12 or 192.168.1.11) carrying RTP/H.264**. RTSP/RTP is what live555 is linked for, and `VFConfig` has `Get/SetVideoResolution` and `Get/SetVideoRate` for the air unit (`VFConfig.java:431-437`, `516-522`). Together this suggests the Vxfly air unit is the H.264 encoder/streamer.
- `udp://0.0.0.0:60430?///pkt_size=655360` (legacy screen) and `GlobalConfig.RAY_CAMERA_TCP_SERVER_IP1 = "udp://0.0.0.0:60430?pkt_size=65536"` (`GlobalConfig.java:31`, unused) are FFmpeg-style URLs. **INFERRED:** the drone can also push raw MPEG-TS or H.264 over UDP to the phone on 60430. Probe for it, but it is not the v1.7.3 default.

### 2.3 Codec, decode path, custom headers

- Codec: **H.264 (AVC)**. `openVideoHw` hard-codes `"video/avc"`, `csd-0` = SPS, `csd-1` = PPS, width and height from native, and `MediaFormat` key `"profile"` = 2 (OBSERVED `vf/video/VFVideo.java:249-273`). An unused debug path dumps `.264` Annex-B frames (`VFVideo.java:90-101`).
- **Hardware path (already Java):** native calls back `openVideoHw(byte[] sps, byte[] pps, int w, int h)`, then `decodeFrameHw(byte[] frame, int len)` for each access unit (queued with pts = 0, flags = 0), then `displayFrameHw()` (render to Surface) and `closeVideoHw()` (OBSERVED `VFVideo.java:105-216`, JNI signatures `([B[BII)I`, `([BI)I` in `libvfvideo.so`). So the native side only **demuxes** RTSP/RTP into H.264 NAL units and extracts SPS/PPS. It does not decode them in this mode.
- **Software path:** FFmpeg decode → `VFOpenGLView` GLES2 YUV shader. Toggled by the user; `VFVideo.init(mUsesHwDecoder)` (OBSERVED `VFVideo.java:70-88`, `VFCameraManager.java:272-280`, `441`).
- Resolution: only from SPS (`getWidth/getHeight` native). Native has SDP `a=x-dimensions:%d,%d` and `a=x-framerate` parsing. **INFERRED:** the default is 1280×720 or 1920×1080 at the air-unit `VideoRate`. Read it from SPS rather than hard-coding it.
- Custom header before the NALs: **none seen on the network path.** live555's standard `H264VideoRTPSource` (RFC 6184 FU-A/STAP-A) is used. **INFERRED:** the only custom framing (`tag/result/error/last skip`) is the dead USB tunnel. Confirm with a packet capture: if RTP payload type 96 with standard FU-A shows up, it is plain RFC 6184.
- Recording and screenshot are native: `StartRecord(path.mp4)` (FFmpeg mux) and `TakeScreenShot(path.jpg)` (FFmpeg `mjpeg` encode, or `TextureView.getBitmap()` in HW mode) (OBSERVED `VFVideo.java:297-325`, `457-467`, strings `mjpeg`, `Couldn't open output file`). The camera's own SD-card recording goes through PowerSDK (`recStart` etc.; see doc 02 section 0).

**Native-only piece to reimplement:** RTSP client + RTP/H.264 depacketization + SPS/PPS extraction, plus whatever `OpenVideo("")` resolves to. In Kotlin that is ~600 lines, or use Media3 `RtspMediaSource`. The decode itself is already MediaCodec.

---

## 3. The vfsdk / Vxfly layer

| Java | Native lib | Purpose | PowerEgg use |
|---|---|---|---|
| `vf/video/VFVideo`, `VFSurfaceView`, `NeonDetector` | `libvfvideo(-neon)`, `libneondetector` | RTSP/RTP H.264 player, record, snapshot | **Yes**: main live view (OBSERVED) |
| `vf/config/VFConfig` + `vfsdk/VFConfigController`, `VFCallback` | `libvfconfig` | Ground/air datalink unit config: connect/heartbeat, AP SSID/password, country code, DL channel/power/SQ threshold, channel scan, video resolution/bitrate, battery voltage, RSSI/SNR, versions, reset/reboot/repair (pairing), firmware upload | **Yes**: station battery and signal bars, station SW version and upgrade, station Wi-Fi settings (`gcs/adapter/StationSetAdapter.java`, `FlyStationFragment.java`, `DialogManager.java`) (OBSERVED by grep) |
| `vf/serial/VFSerial` | `libvfserial` | Serial byte pipe (`TryConnect(name, baud, ?)`) | **No**: no callers (OBSERVED by grep) |
| `vf/usb/VFUSBManager` | `libvfusbmanager` | Android Open Accessory link to "Vxfly, Inc." / "VTS Ground" | **No**: `isUSBAvailable()` returns `false`, `initUSB()` never called (OBSERVED) |
| `vfsdk/VFPlayController`, `VFPlayCallback`, `VFModel` | n/a | Thin wrappers (singleton listener adapter) | `VFPlayController` unused, listener done directly in `VFCameraManager` |

The PowerEgg does **not** use USB/OTG in this build. Everything goes over the base-station 5 GHz Wi-Fi:

- OBSERVED: `isUSBAvailable()` returns `false`, nothing calls `initUSB()`/`VFSerial`, and there is no `usb-accessory`/`usb-device` intent filter or `res/xml` device filter. `apktool/AndroidManifest.xml` has no "usb" at all, and `apktool/res/xml/` only holds `filepaths.xml`, `map_geo_selector.xml` and `preferences.xml`.
- This matches the manual: the remote controller connects to the base station by data cable, and the phone connects to the base station over Wi-Fi (`docs/reference/PowerEgg-user-manual.txt:1848-1856`).
- The MAVLink path (`libpvsdk_api`) has no USB transport at all, only serial-tty/UDP/TCP (section 1.2A).

### 3.1 What the dormant USB path is (Android Open Accessory, not USB host)

Section 3.1 is here for completeness. Treat it as Vxfly SDK code that PowerVision linked in but switched off.

- **Mode:** Android **accessory** mode (AOA). The ground box is the USB host and the phone is the device. The Java side uses `UsbManager.getAccessoryList()` and `openAccessory()`, not `UsbDevice` endpoints. So there is **no VID/PID filter and no interface, endpoint or baud setting** on the Java side: AOA gives a single bidirectional bulk stream as a file descriptor (OBSERVED `vf/usb/VFUSBManager.java:88-140`).
- **Accessory match:** manufacturer `"Vxfly, Inc."`, model `"VTS Ground"` (OBSERVED `VFUSBManager.java:105-107`).
- **Permission:** broadcast action `"com.vxfly.usb.USB_PERMISSION"`, requested with a busy-wait (`VFUSBManager.java:20`, `161-186`).
- **I/O:**
  - A thread `VFAccessoryThread` polls `available()`, reads up to **16384** bytes and passes them to native `onUSBReceive(ptr, buf, len)`, sleeping 1 ms when idle (OBSERVED `VFUSBManager.java:21`, `236-251`).
  - Native writes through the Java `writeData(byte[], len)` callback. Native also calls `openUSB` and `closeUSB` (OBSERVED JNI names `openUSBFunc`/`writeUSBFunc`/`closeUSBFunc` in `libvfusbmanager.so`).
- **Tunnel** (`VFUSBProtocol`, the same code is linked into vfvideo, vfconfig, vfserial and vfusbmanager). OBSERVED log strings:
  - `Send RTSP connect command, tag = %d, url = %s`
  - `Send RTSP close command, tag = %d`
  - `Send TCP connect command, tag = %d, host = %s, port = %d`
  - `Send TCP close command, tag = %d, endPoint = %d`
  - `Send TCP send command, tag = %d, endPoint = %d, size = %zu`
  - `Receive RTP packet, length = %d`
  - `Receive TCP packet, endPoint = %d, size = %d`
  - `Receive feedback, tag = %d, result = 0x%02x, error = 0x%02x, last skip = %d bytes`
  - Classes: `VFUSBSocket`, `VFUSBProtocolTcpReceiver` (vfconfig, vfserial), `VFUSBDataSource`, `VFUSBProtocolRtpReceiver` (vfvideo).
- **INFERRED semantics:**
  - The phone asks the ground box to open TCP connections (host, port) and RTSP sessions (url) on its behalf. Each request carries a `tag`. The box answers with a feedback frame (tag, result, error) and an `endPoint` id. TCP payloads are multiplexed by endPoint, and video arrives as RTP packets that the box already depacketised from its RTSP session.
  - "last skip = N bytes" means the receiver re-syncs by scanning for a magic/header, so the frames carry a sync marker.
  - In USB mode VFConfig / VFSerial would use `VFUSBSocket` instead of `VFNetSocket`, and VFVideo would use `VFUSBDataSource` instead of `VFRtspDataSource`.
  - `VFSerial.TryConnect(String, int, int)` most likely means (host, port, timeout): a TCP "serial bridge" to the radio's serial-over-IP port. The `at+mcts2` "Local Listening Port" in vfconfig supports this.
- **Byte layout:** not visible in the strings. The Ghidra output for the vf libs was not available when this was written. Take it from `VFUSBProtocol::*` in `<ghidra-out>/libvfusbmanager.c` once that file exists. Only do this if a future base station firmware turns out to need USB.

### 3.2 "Link setting" (manual) → APIs

The manual's Link setting screen (`PowerEgg-user-manual.txt:1753-1758`: channel selection, automatic channel, video code rate) is `FlyStationFragment` (OBSERVED `gcs/ui/fgt/fly/FlyStationFragment.java`):

| UI item | Values (OBSERVED `apktool/res/values/arrays.xml`) | Call | VFConfig cmd | Target |
|---|---|---|---|---|
| Channel | `R.array.channel` = 6, 7, …, 19 (from line 277) | `VFConfig.SetDlChannel(ch)` then `ApplyDlConfig()` (`FlyStationFragment.java:168-170`) | 20, then 27 | ground (`execute(…, true, …)`, `VFConfig.java:500-502`) |
| Video code rate | `R.array.VideoRate` = 1M, 2M, 3M, 4M (`arrays.xml:266-271`) | `VFConfig.SetVideoRate(n)` with n = 1-4 Mbps (`FlyStationFragment.java:175-183`) | 26 | air (`execute(…, false, …)`, `VFConfig.java:516-518`) |
| Air / ground country code | `airCode` 0/44/86, `groundCode` | `SetAirCountryCode` / `SetGroundCountryCode` (`FlyStationFragment.java:189-201`) | 33 | air / ground |
| AP SSID / password | free text | `SetApSSID` / `SetApPassword` (`gcs/adapter/StationSetAdapter.java:37-40`), then `ApplyApConfig` (reboots the ground unit, `VFConfig.java:107-110`) | 4 / 6, then 9 | ground |
| Tx power | fixed 24 | `SetDlPower(24)` on callback type 103 (`gcs/manager/PowerManager.java:659-660`) | 22 | ground |
| Read-back on open | n/a | `GetApSSID`, `GetApPassword`, `GetDlChannel`, `GetGroundDlPower`, `GetVideoRate`, `GetVideoResolution`, versions, SN, RSSI (`FlyStationFragment.java:98-131`) | various | both |

"Automatic channel" maps to `ScanDlChannels()` (cmd 36) and `didScanDlChannels` (INFERRED; I found no Java caller apart from the listener in `gcs/sdk/ConfigDelegate.java:66`).

`gcs/sdk/PVStationManager.java` duplicates the whole API through `PowerSDKBaseStation` (native `PVSDK_BaseStation_*` in libpvsdk), but nothing instantiates it (OBSERVED by grep).

**Implication:** bitrate and channel are **VF config protocol** commands to the ground or air unit, not MAVLink. Reverse that protocol (open item 2) before you can offer Link settings.

---

## 4. Android modernisation issues

1. **Wi-Fi without internet.** The station AP has no internet. On Android 10+ the default network stays cellular, and sockets may be routed there or dropped. The original only targets SDK 26 (OBSERVED `AndroidManifest.xml:9-11`). It does the inverse binding: `RequestNetService` runs in a **separate process `:network_power`** and calls `bindProcessToNetwork(cellular)` for maps/activation (OBSERVED `gcs/RequestNetService.java:107-116`, `577-585`; manifest `359-360`). The main process stays on the default network.
   - **New app:** `requestNetwork(TRANSPORT_WIFI, no INTERNET capability)` or `WifiNetworkSpecifier` (prefix `PowerEgg_Station_`). Then bind **only the drone sockets** with `network.bindSocket(...)`, `network.socketFactory` or `DatagramSocket` + `bindSocket`. Leave the process default on cellular for tiles and other cloud traffic.
   - Do not use `bindProcessToNetwork(wifi)` if you want maps to load.
2. **SSID read** needs `ACCESS_FINE_LOCATION`, location services on, and on 13+ `NEARBY_WIFI_DEVICES`. Prefer `ConnectivityManager.NetworkCallback` + `WifiInfo` from `NetworkCapabilities.transportInfo` (API 31+) instead of the deprecated `WifiManager.getConnectionInfo()` / `NetworkInfo` broadcasts the original uses (OBSERVED `WifiSsidutil.java:111-119`).
3. **Multicast / broadcast:** the manifest has `CHANGE_WIFI_MULTICAST_STATE` (OBSERVED `AndroidManifest.xml:29`), but no Java `createMulticastLock` exists (OBSERVED by grep). **INFERRED:** native code used broadcast/multicast (`255.255.255.255`, live555 groupsock). Acquire a `WifiManager.MulticastLock` while connected, or some phones filter broadcast replies from the ground unit.
4. **Cleartext:** there is no `usesCleartextTraffic` attribute, and target 26 allows cleartext by default. Raw sockets/RTSP are not affected by the cleartext policy, but any HTTP (for example the `http://192.168.1.10/` W4 camera, or Media3 RTSP-over-HTTP) needs a `network_security_config` allowing `192.168.1.0/24`.
5. **Storage:** paths are hard-coded `Environment.getExternalStorageDirectory() + "/GCS"`, `/GCS/camera/`, `/GCSplus/video/`, `/GCS/firmware/`, `/GCS/video/thumbnails/`, `/GCS/SonarHistory` (OBSERVED `GlobalConfig.java:41-46`, `gcs/config/Constant.java:109-110`), plus a media-scanner broadcast (`VFCameraManager.java:676-680`). Scoped storage breaks these: use `MediaStore` (Pictures/Movies) for media, `getExternalFilesDir()` for firmware and logs, and the Storage Access Framework for user-picked firmware.
6. **Permissions to drop:** `WRITE_SETTINGS`, `GET_TASKS`, `KILL_BACKGROUND_PROCESSES`, `MOUNT_UNMOUNT_FILESYSTEMS`, `ACCESS_MOCK_LOCATION`, `READ_PHONE_STATE` (`Build.SERIAL` is unavailable now, so generate a UUID for `initRayUuid`). Keep: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS/CHANGE_WIFI_STATE`, `CHANGE_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_FINE_LOCATION`, `NEARBY_WIFI_DEVICES`, `WAKE_LOCK`, `POST_NOTIFICATIONS` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` if the link runs in a foreground service.
7. **Main-thread / lifecycle:** `OpenVideo` runs from `Handler.postDelayed` / ad-hoc `Thread`s, and `requestPermission` busy-waits (OBSERVED `VFUSBManager.java:171-179`). Replace these with coroutines and a `Lifecycle`-scoped service.
8. **Phone-hosted FTP for station firmware (port 52222)** (INFERRED): Android 10+ allows listening on high ports. Bind the server socket to the Wi-Fi `Network`'s address.

---

## 5. Replacement plan

### 5.1 First, on the hardware: close the open item

Connect a laptop (or a second phone running PCAPdroid) to `PowerEgg_Station_*` and:

1. `nmap -sT -sU -p 1-65535 192.168.1.11 192.168.1.12` (the original app is not needed).
2. `ffprobe rtsp://192.168.1.12:554/` and `:8554`, plus common paths (`/live`, `/1.264`, `/stream0`). Repeat for `.11`.
3. If possible, capture the original app on a 32-bit-capable old phone. Record the RTSP DESCRIBE URL, the RTP ports, the VF config packets (ports, framing, heartbeat period) and anything arriving on UDP 60430 / 6009.

### 5.2 Socket layout (Kotlin, all bound to the station `Network`)

```
LinkService (foreground, connectedDevice)
 ├─ StationNetwork      requestNetwork(WIFI, ssid prefix "PowerEgg_Station_") → Network
 ├─ MavlinkLink         TCP 192.168.1.12:20002  (retry 3.1 s; heartbeat watchdog; 10 s full reconnect)
 │                      MAVLink v1/v2 parser (see the PowerSDK/JNI doc for PV_* params)
 ├─ RcLink (optional)   TCP 192.168.1.11:20002  (RC firmware path only)
 ├─ VfConfigClient      ports and framing TBD from capture; 5 s poll: battery, DL radio status, SW version
 ├─ VideoClient         RTSP (TCP 554/8554?) + RTP/RTCP UDP pair  → H264 depacketizer → Decoder
 └─ (probe) UdpVideo    DatagramSocket :60430  (only if the capture shows a push stream)
```

Connection states to expose as a `StateFlow`: `NoWifi → StationWifi → ChainConnected → DroneConnected → HeartbeatLost(→ reconnect at 10 s)`. They mirror `PVSdk` (OBSERVED section 1.3).

### 5.3 Decoder design (MediaCodec)

- **Option A (fastest):** Media3 ExoPlayer `RtspMediaSource` (+ `setForceUseRtpTcp(true)` if UDP is filtered) into a `SurfaceView`. For low latency set `LoadControl` buffers to ~100-200 ms and `setLiveTargetOffsetMs`. Works if the server is standard RTSP/RFC 6184.
- **Option B (full control, matches the original):**
  1. Minimal RTSP client: OPTIONS / DESCRIBE (parse `sprop-parameter-sets`, `a=control`), SETUP (`RTP/AVP;unicast;client_port=x-y` or `RTP/AVP/TCP;interleaved=0-1`), PLAY, and a keep-alive `GET_PARAMETER` every `timeout/2`.
  2. RTP receiver on a bound `DatagramSocket`: reorder by seq (small jitter buffer), depacketize single NAL, STAP-A (24) and FU-A (28) into Annex-B (`00 00 00 01`), and group by RTP timestamp / marker bit into access units.
  3. `MediaCodec.createDecoderByType("video/avc")`, `MediaFormat` w×h from SPS, `csd-0` = SPS, `csd-1` = PPS (same as `VFVideo.openVideoHw`), `KEY_LOW_LATENCY = 1` (API 30+), and configure with the `SurfaceView` surface. Use async callbacks. pts = RTP ts × 1e6 / 90000. Drop to the next IDR on a seq gap (original: `_hwExpectKeyFrame`, `SetBlockErrorFrame`). Release output with `render = true`. Close and reopen after ~60 missing frames (original `SetCloseAfterLostFrame(60)`).
  4. Record: feed the same access units into `MediaMuxer` (MP4, no re-encode). Snapshot: `PixelCopy` from the `SurfaceView`.
- Fallback if the capture shows raw UDP on 60430: if it is MPEG-TS, parse PES/PAT/PMT (or Media3 `UdpDataSource` + `TsExtractor`). If it is Annex-B, split on start codes and feed the same decoder.

### 5.4 What can be dropped

`libvfusbmanager`, `libvfserial`, `libneondetector`, ijkplayer, appunite FFmpeg (PowerEgg-only scope), and the legacy `VideoViewController` / `FlyControllerActivity`.

---

## 6. Open items (must reverse further)

1. **What `VFVideo.OpenVideo("")` connects to** (URL, IP, port). It is not in strings. Get it from a capture, or by disassembling `VFPlayer::openVideo` / `VFRtspDataSource` in `libvfvideo.so`.
2. **The VF config protocol** (`VFProtocolPacket`): ports, framing, the `CMD_*` ids (listed in `VFConfig.java:7-46`) and heartbeat timing. Needed for station battery/signal, AP settings and the manual's Link settings (channel, video bitrate). Not needed for flying. It may be needed for video if `OpenVideo("")` is negotiated through it.
4. (Low priority) `VFUSBProtocol` byte layout, from the pending Ghidra output of `libvfusbmanager`/`libvfconfig`. It only matters if some hardware turns out to use the AOA path.
3. **PowerSDK native link details**: whether 20002 carries raw MAVLink or a PowerVision wrapper, the heartbeat timeout, and whether UDP 6009 is used. These are in `libpvsdk_api-release.so` (`MavlinkClient.cpp`, `PvCmd_ParamNone_Heartbeat_Send`).
