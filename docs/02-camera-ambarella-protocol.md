# 02 - Ambarella camera protocol (`com.powervision.gcs.ambasdk`)

Source: jadx output of Vision+ `com.powervision.gcs` v1.7.3, under
`apk\decompiled\jadx\sources\com\powervision\gcs\ambasdk\`, plus the classes that call it.
Every constant below is copied from the decompiled source. Where I infer something the
app does not show, I mark it **(inferred)**.

---

## 0. Read this first: the PowerEgg does not use this protocol in v1.7.3

The `ambasdk` package is pure Java and easy to port, but in this build **only the PowerRay
(underwater drone) UI uses it**:

| Caller | Product | Evidence |
|---|---|---|
| `ui/aty/water/UnderWaterActivity`, `WaterMediaActivity`, `RaySelfCheckActivity`, `ui/fgt/water/WaterGeneralAboutFragment`, `view/water/RayWaterCameraSettingView` | PowerRay | `water`/`Ray` packages, `RayCallback`, firmware file `raycamera.bin` |
| `ui/aty/water/AmbaCameraController` | PowerRay | the single `IChannelListener` sink for `RemoteCam` |
| `ui/fgt/media/MediaFragment` | PowerRay album | starts `WaterMediaActivity` |
| `ui/controller/AmbaVideoViewController` | PowerRay | plays `RAY_CAMERA_TCP_SERVER_IP` |
| `ui/manager/AmbaCameraManager` | dead code | only created inside `if (0 == 1)` in `PVFlightControllerActivity.initCameraMenuView()` |
| `ambasdk/AmbaRemoteCamera` | unused | no references outside its own file |

The **PowerEgg / PowerEye** flight screen (`ui/aty/fly/PVFlightControllerActivity.initCameraMenuView`)
takes the `else` branch and uses **`ui/manager/VFCameraManager`**:

- **Camera control:** `PowerSDK.stillCapture()`, `recStart()`, `recStop()`, `stillCaptureMode()`,
  `CameraManager.requestCameraAllParams()` → `requestParameter(CameraParams.PV_CAM_REQ_ALL)`.
  All of these go through `com.powervision.powersdk.jni.JniNatives`, which loads
  `pvsdk_api-release` and `pvsdk_api_wrapper`. That is **native code**.
- **Video:** `com.vxfly.vflibrary.video.VFVideo`, which loads native `vfvideo` / `vfvideo-neon`.
  The main feed opens with `mVideoUrl = ""` (the native library picks the source). The FPV feed is
  `private static final String FPV_URL = "rtsp://192.168.1.20:8554/1.3gp";`.

So this document is a complete spec of the Ambarella JSON protocol as the app implements it.
**Do not assume the PowerEgg gimbal camera speaks it** until you test it on the drone
(section 7). The PowerEgg camera port has to come from the PowerSDK/JNI analysis instead.

---

## 1. Transport

### 1.1 Hosts and ports

| Item | Value | Where |
|---|---|---|
| Camera host (used) | `"192.168.1.100"` | `RemoteCam()` ctor: `setWifiIP("192.168.1.100", 7878, 8787)` |
| Command channel | TCP **7878** | same |
| Data channel | TCP **8787** | same |
| Host in unused `AmbaRemoteCamera` | `GlobalConfig.DEFAULT_TCP_SERVER_IP = "192.168.1.12"`, same ports | `AmbaRemoteCamera()` |
| Live RTSP (used) | `GlobalConfig.RAY_CAMERA_TCP_SERVER_IP = "rtsp://192.168.1.100/live"` | `UnderWaterActivity`, `FlyFragment`, `AmbaVideoViewController` |
| Live RTSP (`RemoteCam`, never called) | `"rtsp://" + mWifiHostURL + "/live"` | `RemoteCam.startLiveStream()` |
| File RTSP (never called) | `"rtsp://" + mWifiHostURL + path` | `RemoteCam.streamFile()` |
| UDP alt stream (dead path) | `RAY_CAMERA_TCP_SERVER_IP1 = "udp://0.0.0.0:60430?pkt_size=65536"` | `AmbaCameraManager.initFFmpeg()` |

Nothing outside `ambasdk` calls `setWifiIP`, so the host is always `192.168.1.100`.

Other hosts in `GlobalConfig`, for reference only (they belong to other products):
`W4_CAMERA_HTTP_SERVER_IP = "http://192.168.1.10/"` and
`W4_CAMERA_TCP_SERVER_IP = "rtsp://192.168.1.10/livestream/12"` (PowerDolphin "ship" UI),
`DEFAULT_REMOTE_IP = "192.168.1.11"`, `RAY_TCP_SERVER_IP = "192.168.1.104"`:`8080`.

### 1.2 Sockets and timeouts

| Constant | Value | Applied? |
|---|---|---|
| `CmdChannelWIFI.CONN_TIME_OUT` | 3000 ms | yes, `socket.connect(addr, 3000)` |
| `CmdChannelWIFI.READ_TIME_OUT` | 5000 | **no**. `setSoTimeout` is never called, so reads block forever |
| `CmdChannelWIFI.WAKEUP_MAX_TRY` | 1 | unused |
| `CmdChannel.RX_TIMEOUT` | 8000 | yes, as a literal `mRxLock.wait(8000L)` per request |
| `DataChannelWIFI.CONN_TIME_OUT` | 3000 ms | yes |
| `DataChannelWIFI.READ_TIME_OUT` | 1000 ms | yes, `mSocket.setSoTimeout(1000)` |
| `AmbaCameraManager.CONN_TIMEOUT` | 300000 | passed to `FFmpegPlayer.setConnServer` (dead path) |

- The command socket buffer is `new byte[1024]`. The data channel uses 4096-byte buffers.
- On a command connect failure the app emits `CMD_CHANNEL_ERROR_CONNECT` (134) with
  `"Can't connect to <host>/<port>"`. On a data connect failure it emits `CMD_CHANNEL_EVENT_SHOW_ALERT` (4).
- A command read `IOException` emits `CMD_CHANNEL_ERROR_BROKEN_CHANNEL` (132), and the reader thread exits.
- **Reconnect logic** (`RemoteCam.connectToCmdWIFI`): the app connects again only when the requested
  Wi-Fi SSID differs from `mWifiSSIDConnected`. `reset()` clears `mWifiSSIDConnected`, so every caller
  does `setWifiInfo(ssid, phoneIp)`, `reset()`, `startSession()`.
- The data channel is connected once (`mfDataChannelConnected == 1`) and reused. Before the
  data socket connects, the app always sends `SET_CLNT_INFO` (261) on the command channel:
  `{"token":N,"msg_id":261,"type":"TCP","param":"<phone IPv4>"}`. The phone IP is formatted
  from `WifiManager.getConnectionInfo().getIpAddress()` as `%d.%d.%d.%d` (little-endian int:
  `ip&255, ip>>8&255, ip>>16&255, ip>>24`).

### 1.3 Framing (command channel)

- **Format:** one bare JSON object per message, UTF-8 (Java `String.getBytes()` default charset,
  which is UTF-8 on Android). There is **no length prefix and no delimiter**: no newline and no NUL.
- **Write:** `OutputStream.write(req.getBytes())`, with no flush call and no terminator.
- **Read / reassembly** (`CmdChannel.QueueRunnable.run`):
  1. `msg = readFromChannel()`: one `InputStream.read(buf[1024])`, decoded as `new String(buf,0,n)`.
  2. Loop: try `new JSONObject(msg)`. On `JSONException`, append the next `readFromChannel()` and retry.
  3. If the text **contains the substring `"rval"`**, it is a **response**: `handleResponse`, set
     `mReplyReceived = true`, `mRxLock.notify()`. Otherwise it is a **notification**: `handleNotification`.
- Quirks you must handle better in the port:
  - Android's `org.json` parses the first object and **ignores trailing text**. If two JSON
    objects arrive in one read, the second is silently dropped. Use a brace-depth or streaming
    parser that splits concatenated objects. Count braces outside string literals.
  - Decoding each 1024-byte chunk on its own can split a multi-byte UTF-8 character. Buffer bytes, not Strings.
  - A `read()` returning -1 makes `new String(buf,0,-1)` throw. The thread dies silently ("run error").
  - Responses are **not matched to requests**. Any message containing `rval` releases the single
    waiter. Real Ambarella firmware answers strictly in order, one at a time, and the app relies on that.

### 1.4 Request/response discipline

- Every command method is `synchronized` and calls `sendRequest`, which writes and then blocks up to
  8000 ms for any response. On timeout it emits `CMD_CHANNEL_ERROR_TIMEOUT` (128) and returns false.
- All `RemoteCam` methods post a `Runnable` to one `Executors.newSingleThreadExecutor()`,
  so commands are strictly **serial**. Keep this property: one in-flight command at a time.
- `RemoteCam.getMediaInfo()` is the only chained sequence. It sends 6, 6, 6, 5, 5, 11 in a row
  and accumulates replies by counting them (`mMediaInfoStep`).

### 1.5 Session and token

- **Start:** `{"token":0,"msg_id":257}` (exact string). Reply:
  `{"rval":0,"msg_id":257,"param":<token>}`. The app reads `parser.getString("param")` and extracts
  the first `\d+` match into `mSessionId`. This works whether `param` is a number or a string such as
  `"1"`. Event `CMD_CHANNEL_EVENT_START_SESSION` (23) carries the int. `AmbaCameraController` sets
  `sessionStatus = SESSION_CAMERA (0)` if the token is > 0. Photo and record are gated on `sessionStatus == 0`.
- Every later request carries `"token":<mSessionId>` as its **first** key.
- **Stop:** `{"token":N,"msg_id":258}`. The reply sets the token to 0 and emits event 24.
- `checkSessionID()`: `mCheckSessionId` is hard-wired to `false`, so it always returns true.
  The auto-start-session path never runs. Before a session exists the app sends token 0.
- **Error codes** (`rval`, mapped by `ERR_CODE[-rval]`):
  `0 OK, -1 UNKNOW, -3 SESSION_START_FAIL, -4 INVALID_SESSION, -5 REACH_MAX_CLIENT, -7 JSON_PACKAGE_ERROR,
  -8 JSON_PACKAGE_TIMEOUT, -9 JSON_SYNTAX_ERROR, -13 INVALID_OPTION_VALUE, -14 INVALID_OPERATION,
  -16 HDMI_INSERTED, -17 NO_MORE_SPACE, -18 CARD_PROTECTED, -19 NO_MORE_MEMORY, -20 PIV_NOT_ALLOWED,
  -21 SYSTEM_BUSY, -22 APP_NOT_READY, -23 OPERATION_UNSUPPORTED, -24 INVALID_TYPE, -25 INVALID_PARAM,
  -26 INVALID_PATH` (-2, -6, -10..-12 and -15 are "INVALID_ERROR"). `ERR_MAX_NUM = 26`.
- `handleResponse` maps rval -17 to 144 (NO_MORE_SPACE), -14 to 136, -4 to 129, -3 to 135, and -1 to 137.
  It forwards the raw JSON string, **and then does not dispatch the per-msg_id event**.
  Any other non-zero rval (for example -21 or -26) falls through to the per-msg_id switch as if it were a success.
  Consumers that care, such as `GET_THUMB`, re-check `rval`.
- `AmbaCameraController` treats every event `>= 80` as an error and calls `onError(10, param)`.
  The 144 dialog says "no more space". Any error while recording stops the record UI.

### 1.6 Keepalive / heartbeat

There is **no periodic heartbeat**. The only liveness exchange is the session-holder query:

- The camera pushes `{"msg_id":1793}` (no `rval`, so it is a notification). This is `AMBA_QUERY_SESSION_HOLDER`.
- `handleNotification` immediately calls `replyQuerySessionHolder()`, which sends exactly `{"msg_id":1793}`
  with **no token**.
- **Bug:** this reply is sent **on the reader thread** through `sendRequest`, which then waits 8 s for
  an `rval` message the reader thread itself can't read. The command channel stalls for 8 s and emits
  timeout 128. The port should send this reply fire-and-forget, without waiting.
- `RemoteCam.setQuerySessionFlag(true|false)` and `actionQuerySessionHolder()` exist (gated by the flag,
  triggered by event 40), but nothing emits event 40. The flag is set true while the Ray UI or album is
  open and false on exit.
- **(inferred)** On standard Ambarella firmware, not answering 1793 lets another client take the
  session, and the camera then sends a notification such as `"type":"disconnect_shutdown"`.

---

## 2. Command reference

`T` = current token. Field order is as the app emits it. Some quirks are preserved verbatim, such as the
space in `"type": "app_status" }`. **"Event"** is the `IChannelListener` code `CmdChannel` raises, and
**"UI"** is what `AmbaCameraController` / screens do with it.

43 `msg_id` constants are defined. **40 are sent** by request builders. `AMBA_CD` (1283) and
`AMBA_GET_WIFI_STATUS` (1542) have no builder, and `AMBA_NOTIFICATION` (7) is inbound only.

### 2.1 Session / system

| Name | id | Request | Response used | Event / UI |
|---|---|---|---|---|
| `AMBA_START_SESSION` | 257 | `{"token":0,"msg_id":257}` | `param` = token | 23 → `onSessionSucceed()`; Ray UI then sends `camera_clock` |
| `AMBA_STOP_SESSION` | 258 | `{"token":T,"msg_id":258}` | - | 24 → `onSessionStopSucceed()` |
| `AMBA_SET_CLINT_INFO` | 261 | `{"token":T,"msg_id":261,"type":"TCP","param":"<phoneIp>"}` | - | none; sent before every data-channel connect |
| `AMBA_QUERY_SESSION_HOLDER` | 1793 | reply `{"msg_id":1793}` | - | see 1.6 |
| `AMBA_POWER_MANAGE` | 12 | `{"token":T,"msg_id":12,"param":"cam_stb"}` (`standBy()`) | - | no handler; never called by UI |
| `AMBA_GET_DEVINFO` | 11 | `{"token":T,"msg_id":11}` | whole object; app reads `media_folder` | 17 (see below) |
| `AMBA_BATTERY_LEVEL` | 13 | `{"token":T,"msg_id":13}` | `param` | 20 with `"Battery Level: " + param`; never called |
| `AMBA_BURN_FW` | 8 | `{"token":T,"msg_id":8,"param":"<path>"}` | - | no handler; **never called** (see 4.3) |
| `AMBA_SET_BITRATE` | 16 | `{"token":T,"msg_id":16,"param":"<int>"}` | - | no handler; never called |

**GET_DEVINFO handling** (`RemoteCam.onChannelEvent case 17`):
- First call after `reset()`, while `mSDCardDirectory == null`: if the reply has `media_folder`, then
  `val = media_folder; val2 = val.substring(0, len-1); mSDCardDirectory = val2.substring(0, val2.lastIndexOf('/')+1)`.
  This drops the trailing char and then the last path component, so `/tmp/SD0/DCIM/` becomes `/tmp/SD0/`.
  Otherwise the directory is `"/tmp"` (no trailing slash, sic).
- Later calls: every key except `rval` and `msg_id` is appended as `"\n<key>: <value>"` to the media-info
  text, which is forwarded as event 17 → `updateDeviceInfo(String)`.
- `startSession()` automatically sends GET_DEVINFO after 257 when `mSDCardDirectory == null`.

### 2.2 Settings

| Name | id | Request | Response | Event / UI |
|---|---|---|---|---|
| `AMBA_GET_SETTING` | 1 | only use: `appStatus()` → `{"token":T,"msg_id":1,"type": "app_status" }` | `{"rval":0,"msg_id":1,"type":"app_status","param":"<state>"}` | 14 → `setSetting(json)` callback (sic) |
| `AMBA_SET_SETTING` | 2 | `{"token":T,"msg_id":2,"type":"<key>","param":"<value>"}`. The caller passes the inner text, so the body is `"type":"…","param":"…"` | `{"rval":0,"msg_id":2,"type":"<key>",...}` | 12 → `setSetting(json, canStopVF)` |
| `AMBA_GET_ALL` | 3 | `{"token":T,"msg_id":3}` | `{"rval":0,"msg_id":3,"param":[{"<key>":"<val>"},...]}` (list of one-entry maps) | 10 → `updateAllSetting(JSONObject)` |
| `AMBA_GET_OPTIONS` | 9 | `{"token":T,"msg_id":9,"param":"<key>"}` | (inferred) `{"rval":0,"msg_id":9,"param":"<key>","options":[...],"permission":"settable"}` | 11; never called by UI |

`app_status` values handled (`UnderWaterActivity`):
- `"record"`: the UI syncs to recording.
- `"idle"`: the UI calls `startVF()` (259).
- `"photo_mode"`: the UI calls `stopPhoto()` (770).

The app reads `app_status` right after the `camera_clock` set succeeds.

**Set flow with viewfinder stop** (`AmbaCameraController.setSetting(cmd)`, used for `video_quality`,
`video_resolution` and `default_setting`):
1. `canStopVF = true` and send `STOP_VF` 260.
2. Send `SET_SETTING` 2.
3. On the 260 reply (event 22), `canStopVF = true`. On the 2 reply (event 12), if `canStopVF`, send
   `RESET_VF` 259 (restart the viewfinder), then `canStopVF = false`.

Other settings go straight through `RemoteCam.setSetting` without stopping the viewfinder.
**Preserve this:** Ambarella firmware rejects resolution and quality changes while the viewfinder or encoder is running.

### 2.3 Capture / viewfinder

| Name | id | Request | Event / UI |
|---|---|---|---|
| `AMBA_TAKE_PHOTO` | 769 | `{"token":T,"msg_id":769}` | 48 → `takePhoto()`; toast "shot saved" |
| `AMBA_STOP_PHOTO` | 770 | `{"token":T,"msg_id":770}` | none; sent when app_status is `photo_mode` |
| `AMBA_RECORD_START` | 513 | `{"token":T,"msg_id":513}` | 51 → `startRecord()` |
| `AMBA_RECORD_STOP` | 514 | `{"token":T,"msg_id":514}` | 49 → `stopRecord()` |
| `AMBA_RECORD_TIME` | 515 | `{"token":T,"msg_id":515}` | 21 with `param` (String). **Bug:** the controller casts it to `Integer` (ClassCastException). Never called |
| `AMBA_FORCE_SPLIT` | 516 | `{"token":T,"msg_id":516}` | no handler; never called |
| `AMBA_RESETVF` | 259 | `{"token":T,"msg_id":259,"param":"none_force"}` (`startVF()`) | 9 → `reset_vf()` |
| `AMBA_STOP_VF` | 260 | `{"token":T,"msg_id":260}` | 22 → `stop_vf()`; the settings panel can't close while VF is stopped |
| `AMBA_ZOOM` | 14 | `{"token":T,"msg_id":14,"type":"<type>","param":"<level>"}` | 38 with rval; never called |
| `AMBA_ZOOM_INFO` | 15 | `{"token":T,"msg_id":15,"type":"<type>"}` | 39 with (type, `param`); never called |

The Ray UI calls photo and record from `onUnderWaterViewChange` (1 = photo, 2 = record start, 3 = record stop).
Before the first photo it sends `camera_clock` if it has not been sent yet.

### 2.4 Storage / media

| Name | id | Request | Response | Event / UI |
|---|---|---|---|---|
| `AMBA_FORMAT_SD` | 4 | `{"token":T,"msg_id":4,"param":"C"}` (`DELETE_DIRECTORY = "C"`) | - | 18 → `formatSDCard()`; toast, then `getMediaInfo()` |
| `AMBA_GET_SPACE` | 5 | `{"token":T,"msg_id":5,"type":"free"}` or `"total"` | `param` (KB) | 15 |
| `AMBA_GET_NUM_FILES` | 6 | `{"token":T,"msg_id":6,"type":"photo"}` / `"video"` / `"total"` | `param` | 16 |
| `AMBA_LS` | 1282 | `{"token":T,"msg_id":1282,"param":"<dir> -D -S"}` | `{"rval":0,"msg_id":1282,"listing":[{"<name>":"<info>"},...]}` | 33 (before send), then 5 → `fileLS` |
| `AMBA_PWD` | 1284 | `{"token":T,"msg_id":1284}` | - | no handler; never called |
| `AMBA_DEL` | 1281 | `{"token":T,"msg_id":1281,"param":"<full path>"}` | - | 6 → `deleteSuccess()` |
| `AMBA_GET_THUMB` | 1025 | `{"token":T,"msg_id":1025,"param":"<path>","type":"thumb"\|"IDR"}` | `rval`, `size` (+ (inferred) `md5sum`) | 37 → data-channel receive (section 4) |
| `AMBA_GET_MEDIAINFO` | 1026 | `{"token":T,"msg_id":1026,"param":"<path>"}` | `thumb_file`?, `duration`?, `resolution`, `size`, `date`, `media_type` | 8 as text; never called |
| `AMBA_SET_ATTRIBUTE` | 1027 | `{"token":T,"msg_id":1027,"type":<int flag>,"param":"<path>"}` (`type` unquoted) | - | 36 with rval; never called |
| `AMBA_GET_FILE` | 1285 | `{"token":T,"msg_id":1285,"param":"<path>","offset":0,"fetch_size":0}` | `size` (long) | 7 → data-channel receive |
| `AMBA_PUT_FILE` | 1286 | `{"token":T,"msg_id":1286,"param":"<dest>","size":<long>,"md5sum":"<hex>","offset":0}` | - | 19 → data-channel send |
| `AMBA_CANCLE_XFER` | 1287 | get: `{"token":T,"msg_id":1287,"param":"<path>"}`; put: `{"token":T,"msg_id":1287,"param":"<path>","sent_size":<int>}` | - | 50 → `cancelSuccess()` |

GET_THUMB `type` is `"thumb"` when the path ends in `jpg` (case-insensitive), otherwise `"IDR"` (video keyframe).

`getMediaInfo()` text format: the replies to `6 photo/video/total`, `5 free/total` and `11` are
concatenated as `"\nPhoto Files: N\nVideo Files: N\nTotal Files: N\ntotal space: XKB\nfree space: YKB\n<devinfo key: val>..."`.
**Bug:** the 4th reply (`free`) is labelled "free space" and the 5th (`total`) "total space" because the
labels go by step count. The settings view parses these lines with fixed `substring(13, len-2)` and
`substring(12, len-2)` and divides by 1024² to show GB. The port should parse the JSON directly.

### 2.5 Camera Wi-Fi (never called by UI)

| Name | id | Request |
|---|---|---|
| `AMBA_GET_WIFI_SETTING` | 1539 | `{"token":T,"msg_id":1539 }` (note the space). Reply `param` → event 41 |
| `AMBA_SET_WIFI_SETTING` | 1538 | `{"token":T,"param":"<config>","msg_id":1538}` (param before msg_id) |
| `AMBA_WIFI_STOP` | 1540 | `{"token":T,"msg_id":1540}` |
| `AMBA_WIFI_START` | 1541 | `{"token":T,"msg_id":1541}` |
| `AMBA_WIFI_RESTART` | 1537 | `{"token":T,"msg_id":1537}` |
| `AMBA_GET_WIFI_STATUS` | 1542 | (no builder) |
| `AMBA_CD` | 1283 | (no builder). Standard Ambarella form is `{"token":T,"msg_id":1283,"param":"<dir>"}` (inferred) |

`RemoteCam.sendCommand(String)` sends any raw JSON string. The UI doesn't use it.

### 2.6 Asynchronous notifications (camera → app, no `rval`)

All have `msg_id` 7 and a `type` (plus an optional `param`), except 1793.

| type | App handling |
|---|---|
| `fw_upgrade_complete`, `fw_upgrade_failed` | event 4 (SHOW_ALERT) with the type string |
| `get_file_complete` | (any other type is routed here) event **519** `DATA_CHANNEL_EVENT_GET_MD5` with the whole JSON → `getFileMd5()`. Album checks `param[1].md5sum` (section 4.1) |
| any other (for example `put_file_complete`, `get_file_fail`, `vf_start`, `vf_stop`, `video_record_complete`, `photo_taken`, `sd_card_status`, `disconnect_shutdown`, `low_battery_warning`; inferred standard Ambarella types) | also event 519. Only the album and firmware screens listen, and they act only on `get_file_complete` |
| `{"msg_id":1793}` | immediate `{"msg_id":1793}` reply (1.6) |

---

## 3. Settings keys and values

`GET_ALL` returns `param` as a list of `{key: value}` maps. The app flattens them into one map.
Keys the app reads:

| Key (`type`) | Values sent (`res/values/arrays.xml` `amba_*`) | UI label (`ray_*`, same index) |
|---|---|---|
| `video_resolution` | `3840x2160 25P 16:9`, `2560x1440 50P 16:9`, `1920x1080 60P 16:9`, `1920x1080 30P 16:9`, `1280x720 120P 16:9` | 4k(3840x2160@25), 2k(2560x1440@50), 1080p@60, 1080p@30, 720p@120 |
| `wb` | `auto`, `incandescent`, `d4000`, `d5000`, `sunny`, `cloudy`, `d9000`, `d10000`, `flash` | AUTO, Incandescent, D4000, D5000, Sunny, Cloudy, D9000, D10000, **LED** (sent as `flash`) |
| `ISO` | `auto`, `100`, `200`, `400`, `800`, `1600` | same |
| `ev_bias` | `-2.0 -1.7 -1.3 -1.0 -0.7 -0.3 0.0 +0.3 +0.7 +1.0 +1.3 +1.7 +2.0` | same. Titled "dual stream" (`ray_dual_stream`) in the UI, which is a label bug |
| `video_quality` | `sfine`, `fine`, `normal` | S.FINE, FINE, NORMAL |
| `capture_mode` | `precise quality`, `burst quality cont.` | Single, Multiple |
| `sharpness` | `std`, `hard`, `soft` | **Strong → `std`, Common → `hard`**, Soft → `soft` (index mismatch, preserved by the app) |
| `burst_num` | `3pcs`, `5pcs` | 3 / 5 continuous shooting |
| `photo_size` | `12M`, `8M`, `5M`, `3M` | 4000x3000 4:3, 3840x2160 16:9, 2560x1920 4:3, 2048x1536 4:3 |
| `slow_motion` | `Off`, `On` (capitalised) | Off, On |
| `video_split` | `off`, `1 min`, `3 min`, `5 min` | OFF, 1min, 3min, 5min |
| `digital_effect` | `off`, `art`, `sepia`, `negative`, `black_white`, `vivid`, `70film` | Normal, Art, Sepia, Negative, B&W, VIVID, 70Film |
| `ver` | read-only firmware version, for example `v1.2.3`. The app strips `v` and compares it with the local `raycamera.bin` version | - |
| `camera_clock` | set only: `"yyyy-MM-dd HH:mm:ss"` (`CalendarUtils.DATE_TIME_FORMAT`). Sent on every session start as `"type":"camera_clock","param": "<time>"` (space after the colon) | - |
| `default_setting` | set only: `"on"`. Factory reset, sent through the VF-stop flow | - |
| `app_status` | get only (msg 1): `record` / `idle` / `photo_mode` (others possible) | - |

The arrays `amba_scene_mode` (`normal flash night sports landscape portrait sunset`) and
`amba_video_system` (`NTSC PAL`) exist, but no code sends them. **(inferred)** The keys would be
`scene_mode` / `video_standard`. Confirm with `GET_OPTIONS` (9) on real hardware.
The UI has a "more settings" entry (`more_setting`) that isn't sent. Its items run Format SD (`"C"`) and factory reset.

Unknown keys: send `GET_ALL` and then `GET_OPTIONS <key>` for each key to discover the camera's real vocabulary.
Treat the tables above as the Ray camera's values, not as guaranteed for any other body.

---

## 4. Data channel (TCP 8787): file transfer

The data channel carries **raw bytes only**. There are no headers and no framing. Sizes, offsets and
completion all travel on the command channel. Before the first transfer: send 261 `SET_CLNT_INFO`,
then connect the 8787 socket (3 s connect timeout, 1 s read timeout). Keep the socket open.

### 4.1 Download (camera → phone)

1. `GET_FILE` 1285: `{"token":T,"msg_id":1285,"param":"<abs path>","offset":0,"fetch_size":0}`.
   The app always sends offset 0 and fetch_size 0 ("whole file"). Resume is possible in the protocol
   (`offset` > 0), but the app doesn't use it.
2. The reply `{"rval":0,"msg_id":1285,"size":<bytes>,...}` gives event 7 with the size. If `rval != 0` there
   is no `size`, `getLong` throws, and nothing happens.
3. `DataChannel.rxStream(dst, size)`: emit 512 (GET_START), then loop `read(buf[4096])` until `total >= size`.
   Each read is written to the file. Progress event 513 fires at every ≥1 % step.
   - A `SocketTimeoutException` (1 s) increments `mCatchCount`. After **> 10 consecutive** timeouts the
     transfer fails (event 520). Any successful read resets the count.
   - `read() == -1` gives event 520.
   - Cancel: `mContinueRx = false`, which only takes effect at the next timeout.
4. Event 514 (GET_FINISH) when byte count is reached.
5. The camera then pushes `{"msg_id":7,"type":"get_file_complete","param":[{...},{"md5sum":"<hex>"}]}`.
   The app requires `param` to be a 2-element array and takes `param[1].md5sum`, then compares it with
   lowercase hex `MD5Utils.encode(file)`. Only on a match does it insert the file into MediaStore.
   **(inferred)** `param[0]` is `{"path": "..."}`.
6. Local paths: `/sdcard/GCS/Ray/download/<dest>/` (download) or `/sdcard/GCS/Ray/temp/<dest>/` (thumb or temp).
   The file name is `<md5>.<basename>`, and thumbnails get `.thumb` appended. `<md5>` is the MD5 of the listing entry text (4.4).
7. Cancel: `CANCLE_XFER` 1287 `{"param":"<path>"}`, then stop the receive loop. Reply → event 50.
   **Port note:** after a cancel, bytes may still be in flight on 8787. Drain or reconnect the data socket before the next transfer.

**Thumbnail:** same flow with `GET_THUMB` 1025 (`type` `thumb`/`IDR`). The reply needs `rval == 0` and `size`,
and the app then receives `size` bytes into `<md5>.<basename>.thumb`.

### 4.2 Upload (phone → camera)

1. Emit 518 (PUT_MD5), compute the MD5 of the local file (4096-byte reads), and format it as lowercase hex,
   two digits per byte. jadx shows `b & SocksProxyConstants.NO_ACCEPTABLE_AUTH_METHOD`, which is a
   constant-inlining artifact for `& 0xFF`. Use a normal 32-char lowercase hex string.
2. `PUT_FILE` 1286: `{"token":T,"msg_id":1286,"param":"<dest abs path>","size":<len>,"md5sum":"<hex>","offset":0}`.
3. The reply (any non-error rval) gives event 19 and `DataChannel.txStream(src)`: emit 515, write 4096-byte chunks,
   and emit 516 progress at ≥1 % steps. A 5 s watchdog (`TimerRunnable`, re-armed after each chunk) emits 521 PUT_FAILED if a write stalls.
4. At EOF the app emits 517 PUT_FINISH **locally**, without waiting for the camera. **(inferred)** The camera then pushes
   msg 7 `put_file_complete` (with md5sum), which the app routes to 519 and ignores. The port should wait for it.
5. Cancel: stop sending, then `CANCLE_XFER` 1287 with `"sent_size":<bytes sent>`.

### 4.3 Firmware update (Ray camera)

`WaterGeneralAboutFragment` uploads `/sdcard/GCS/firmware/raycamera.bin` (`Constant.firmwarePath = "/GCS/firmware/"`,
`Constant.ware.CAMERA_RAY_FIRMWARE = "raycamera.bin"`) through PUT_FILE to `sdCardDirectory() + "raycamera.bin"`
(for example `/tmp/SD0/raycamera.bin`). On PUT_FINISH it only shows "upload success". **`BURN_FW` (8) is never sent.**
Presumably the camera flashes the image itself from the SD root at reboot. `burnFW(path)` exists as
`{"token":T,"msg_id":8,"param":"<path>"}`, and the camera reports through msg 7 `fw_upgrade_complete` / `fw_upgrade_failed`.
The firmware binary was downloaded from PowerVision's server. That server is gone, so the port needs a locally supplied file.

### 4.4 Directory listing format

- Album root: `sdCardDirectory() + "AMBA/"` (for example `/tmp/SD0/AMBA/`).
  LS sends `"<dir> -D -S"` (with details and sizes).
- Each `listing[i]` is a one-entry object. The app parses it crudely: it strips `{}"` from the JSON text,
  splits at the first `:`, and treats the left side as the name and the right side as the content.
  - If the content contains `|`, it reads `"<size> bytes|<yyyy-MM-dd HH:mm:ss>"`. The size is the text
    up to the first space, and the time is the text after `|`.
  - Else if the content contains `bytes`, it reads a size only. (The app parses this from the whole
    descriptor, which is a bug. Parse the value.)
  - Otherwise the content is a time only (directories).
- A name ending in `/` is a directory. **Bug:** the app strips 2 characters, not 1.
- `.JPG` means photo and `.MP4` means video. The check is case-sensitive.
- The local cache key is `md5 = MD5(content)`, lowercase hex of the UTF-8 bytes.
- Delete: `DEL` 1281 with `mPWD + name`.

---

## 5. Live video

| Product | URL | Player |
|---|---|---|
| PowerRay (Ambarella) | `rtsp://192.168.1.100/live` (`GlobalConfig.RAY_CAMERA_TCP_SERVER_IP`) | `com.appunite.ffmpeg.FFmpegPlayer` (`connServer(url)` → `setDataSource(url, null, -1, -1, -2)`). Native: `ffmpeg`/`ffmpeg-jni` (`-neon` variants). Rendered to an `MGLSurfaceView`; `setVrmode(1)` for VR split |
| Ambarella SDK's own path (unused) | `rtsp://192.168.1.100/live` | `com.ambarella.streamview.AmbaStreamSource.startWifi(url)`, native `libAwcrcNative` + `avcodec/avformat/avutil`, `AmbaYuvRenderer` GL. Events: 0 BUFFERING, 1 PLAYING, -1 INVALID_URL, -2 INVALID_STREAM, mapped to 1024/1025/1026 |
| AmbaCameraManager (dead) | `udp://0.0.0.0:60430?pkt_size=65536` | FFmpegPlayer |
| **PowerEgg** main feed | `""` (source chosen inside native `libvfvideo`) | `com.vxfly.vflibrary.video.VFVideo` (`OpenVideo(url, …, false)`), native |
| **PowerEgg** FPV | `rtsp://192.168.1.20:8554/1.3gp` | VFVideo |
| PowerDolphin (W4) | `rtsp://192.168.1.10/livestream/12` | FFmpegPlayer |

`ijkplayer` libs (`libijkplayer.so`, `libijkffmpeg.so`) are also bundled. Every player path in the app is
native, but none of them is needed by the port: Media3/ExoPlayer's `RtspMediaSource` (or libVLC) can play the RTSP URLs.
Ambarella `/live` is usually H.264 over RTP/UDP. If the camera refuses UDP, force TCP interleaving (`setForceUseRtpTcp(true)`).
`RESET_VF` (259) must have been sent, or the viewfinder must be running, for `/live` to deliver frames.

---

## 6. Kotlin port outline

```
camera/amba/
  AmbaMsg.kt             // const val START_SESSION = 257 ... (all 43), AmbaError enum from ERR_CODE
  AmbaJsonFramer.kt      // byte buffer -> complete JSON objects (brace depth, string/escape aware)
  AmbaCmdChannel.kt      // TCP 7878, reader coroutine, request/response mutex
  AmbaDataChannel.kt     // TCP 8787, receive(size, sink) / send(source) with progress Flow
  AmbaCamera.kt          // high-level API: session, settings, capture, files, firmware upload
  AmbaSettings.kt        // key/value tables from section 3 + label mapping
  AmbaListing.kt         // parse LS entries properly
  AmbaEvent.kt           // sealed class: Notification(type, param), SessionLost, Error(rval, msgId)
```

**AmbaCmdChannel**
- `suspend fun connect(host = "192.168.1.100", port = 7878)`: `withTimeout(3000)`, then
  `Socket().connect(addr, 3000)` on `Dispatchers.IO`. Bind the socket to the Wi-Fi `Network`
  (`network.socketFactory`). Android routes traffic over cellular otherwise, because the camera AP has no internet.
- Reader coroutine: `input.read(ByteArray(4096))` → `AmbaJsonFramer.feed()` → for each object:
  `if (obj.has("rval"))` complete the pending `CompletableDeferred<JSONObject>`. Else, if `msg_id == 1793`,
  write `{"msg_id":1793}` immediately without awaiting. Else emit `AmbaEvent.Notification` on a `SharedFlow`.
  Keep the app's rule: "has `rval`" means response.
- `suspend fun request(json: String): JSONObject` is guarded by a `Mutex` (one in-flight command),
  with `withTimeout(8000)`. Check that the reply's `msg_id` equals the request's, and log a mismatch instead of
  accepting it blindly. Map `rval < 0` to `AmbaException(rval, msgId)`.
- Build requests in the same key order and with the same quirks (`token` first; `type` before `param` for 2/261;
  `"param"` before `"msg_id"` in 1538; `"type"` unquoted int in 1027; `" -D -S"` suffix in 1282; `"param":"none_force"`
  in 259; `"C"` in format). Some Ambarella firmwares are order- or whitespace-sensitive. Byte-identical output is the safe default.
- Write UTF-8 and `flush()`. Don't add a newline.
- Session: `token = Regex("\\d+").find(reply.get("param").toString())`. Re-run 257 if a reply has rval `-4`.
  On connect: 257, then 11 (derive `sdRoot` from `media_folder`), then `camera_clock` set, then `app_status` get, then react.

**AmbaDataChannel**
- On the first transfer: `request(261 SET_CLNT_INFO "TCP", phoneIp)`, then connect 8787 (3 s) with `soTimeout = 1000`.
- `receive(size, sink)`: read until `size` bytes. Treat 11 consecutive 1 s timeouts as failure. Emit progress.
  Then await the msg 7 `get_file_complete` notification (with a timeout, about 10 s) and verify `param[1].md5sum`.
- `send(file)`: MD5 first, then 1286, then stream 4096-byte chunks. A 5 s per-chunk stall means failure.
  Then await `put_file_complete` (inferred) before reporting success.
- After any cancel or failure, close and reopen 8787 to discard stale bytes.

**Quirks to keep or fix**
- Keep: strict serial commands; viewfinder stop → set → reset for `video_resolution`/`video_quality`/`default_setting`;
  `camera_clock` after each session; data socket only after 261; `offset`/`fetch_size` fields present (0/0).
- Fix: concatenated-JSON loss; UTF-8 split across reads; the 1793 reply blocking the reader; unmatched responses;
  `free`/`total` label swap; directory-name 2-char strip; `RECORD_TIME` String→Integer cast; unhandled rvals such as -21/-26;
  blocking command read with no timeout (add a socket read timeout or rely on the coroutine timeout and a reconnect).
- Wi-Fi: request the camera AP with `WifiNetworkSpecifier` / `ConnectivityManager.requestNetwork` and bind sockets to it.
  The old app assumed the phone was already joined.
- Everything stays local. No cloud or server dependency exists in this protocol. Only the firmware image download used PowerVision's server.

---

## 7. Verifying on the PowerEgg

The PowerEgg app path is native PowerSDK, so first check whether anything on the PowerEgg network answers on 7878:
1. Join the PowerEgg Wi-Fi (`PowerEgg_Station_*` / `PowerEgg_*`). Scan `192.168.1.0/24` for TCP 7878/8787 and RTSP 554/8554.
2. If 7878 is open: send `{"token":0,"msg_id":257}` and expect `{"rval":0,"msg_id":257,"param":N}`.
   Then send `{"token":N,"msg_id":3}` to dump settings.
3. If it isn't open, the gimbal camera is reached only through the flight-controller link (`libpvsdk_api*`,
   `CameraParams.PV_CAM_REQ_ALL`). The camera control must then come from the PowerSDK/JNI protocol analysis, not from this document.
