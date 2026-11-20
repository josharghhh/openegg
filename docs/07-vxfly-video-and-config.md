# 07 - VxFly video source and VF config protocol (libvfvideo / libvfconfig)

This doc closes open items 1 and 2 from `03-video-and-link.md`, section 6. Read doc 03 first. This doc does not repeat it.

Sources:

- Ghidra pseudocode: `<ghidra-out>/libvfvideo.c` and `libvfconfig.c`, located through the `.functions.tsv` files.
- Raw bytes: `apk\native\libvfvideo.so`, `libvfvideo-neon.so` and `libvfconfig.so`, read with Python.
- Java: `apk\decompiled\jadx\sources\`. In this doc, `vf/` = `com/vxfly/vflibrary/`, `vfsdk/` = `com/powervision/vfsdk/` and `gcs/` = `com/powervision/gcs/`.

Nothing was executed on hardware.

Labels:

- **OBSERVED** is in the pseudocode (`function @ Ghidra address`), the raw bytes (`lib @ address`), or the Java (`path:line`).
- **INFERRED** is my reading of the evidence.

Address convention (OBSERVED): both libs have their first `PT_LOAD` at file offset 0 / vaddr 0. Ghidra's image base is 0x10000, so **file offset = Ghidra address - 0x10000**. Pseudocode strings appear as `DAT_x + pc`, a Thumb PC-relative literal. I resolved each one with `ptr = u32(DAT_x) + pc` and then read the bytes at `ptr - 0x10000`. Every decoded string came out as clean ASCII, which confirms the method.

---

## 0. Summary

| Item | Value | Label |
|---|---|---|
| Default video URL (`OpenVideo("")`) | **`rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264`** | OBSERVED: XOR-obfuscated in `.rodata`, decoded below |
| RTSP control | TCP to 192.168.1.240:8557, live555 2017.01.26 client, app name `VFLive` | OBSERVED |
| RTP transport | **RTP/AVP over UDP unicast** (`streamUsingTCP = False`), audio subsessions skipped | OBSERVED (disassembly) |
| Payload | Standard RFC 6184 H.264 (`H264VideoRTPSource`). No vendor header. SPS/PPS from SDP `sprop-parameter-sets` | OBSERVED |
| Who serves video | 192.168.1.240 = the **VxFly air unit** (also its config host) | OBSERVED IP; the role is INFERRED |
| VF config transport | **TCP**, port **1234**. Ground unit **192.168.1.2**, air unit **192.168.1.240** | OBSERVED |
| VF frame | `[cmd u8][len u8][payload len bytes]`. **No sync byte, no checksum**. Payload is ASCII | OBSERVED |
| Keepalive | `00 00` sent every recv-timeout/2: 2.5 s after connect, then 1 s after the login reply. The socket closes if nothing arrives within the timeout (5 s, then 2 s) | OBSERVED |
| Login | `1C 00` sent automatically on connect | OBSERVED |
| Battery | `10 00` → reply `10 nn "3.912"` (volts). Native maps it to percent ×100 through a table | OBSERVED |
| Radio status | `1F 00` → reply `1F nn "rssi,snr,noise"`. The UI bars use **SNR** | OBSERVED |
| Set channel / rate | `14 nn "<ch>"` (ground) then `1B 00` (ground). `1A nn "<Mbps>"` (air) | OBSERVED |
| Reply result | Any payload other than `FAILED` (case-insensitive) counts as success | OBSERVED |

---

## 1. Task A: video source

### 1.1 The default URL is obfuscated, not absent

- `VFRtspDataSource::connect(char const*, double) @ 0011b320` copies the caller's URL **only if it is non-NULL and non-empty**. Otherwise it calls `VFDataSource::_getRtspAddress(buf)`, then `RTSPClient::setBaseURL(this, buf)`. OBSERVED.
- `VFDataSource::_getRtspAddress @ 0011b180` works like this (OBSERVED):
  1. `memcpy(buf, <rodata @ 0x37e2e0>, 0x49)`
  2. `buf[0] = 'r'`
  3. for `i` in `1..strlen-1`: `buf[i] ^= key[i % 5]`, where `key` is the 5 dwords at 0x387690 = `{9, 1, 5, 2, 3}`
- Raw bytes at file offset 0x36e2e0 (OBSERVED): `7b75767239262e343b312730333a2d382f37363333393037342651564b422652717066686c6c6c6426626d636d676469712c3b3e736b676c6e466d676c62517b736c3c4d2c313f35 00`
- Decoded (OBSERVED; Python is in section 4):

```
rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264
```

- The same 72 ciphertext bytes and the same key are in `libvfvideo-neon.so` (file offsets 0x2d7cf0 and 0x2e10a0). That variant is the one loaded on NEON phones (OBSERVED, `vf/video/VFVideo.java:59-62`). Both variants therefore use the same URL.
- This is why doc 03 found no IP or port by strings search.
- `/PSIA/Streaming/channels/N` is the PSIA IP-camera URL scheme, so the air unit is a PSIA-style H.264 encoder. INFERRED. Channel `2` is usually the sub-stream (lower resolution or bitrate). INFERRED. Probe `channels/1` too (section 5).

### 1.2 Call chain (all OBSERVED)

1. `VFCameraManager` calls `VFVideo.OpenVideo("", 2000, false)` (doc 03 §2.2). `VFVideo.OpenVideo(String,int,boolean)` → native `openVideo(long, String, int, boolean)` (`vf/video/VFVideo.java:247`, `422-424`).
2. `jni_vfvideo_openVideo @ 0011fbd8` gets the UTF chars and converts the int timeout to a double. It then calls `VFPlayer::openVideo @ 0011d674`, which copies the URL into a 256-byte buffer and queues a task on the player's `TaskQueue`. The task runs `VFPlayer::openVideoSync @ 0011dac4` → `decoder->openVideo(url, timeout, useUSB)`.
3. Decoder choice comes from `VFVideo.init(useHw)`. `jni_vfvideo_init @ 0011f6b8` works like this:
   - `param_3 == 0` creates `VFFFMpegDecoder` (software decode, `avcodec_find_decoder(0x1c = AV_CODEC_ID_H264)`) plus `VFOpenGLView`.
   - Otherwise it creates `VFCustomDecoder`, the Java MediaCodec path, with a 200000-byte frame buffer whose first word is `0x01000000` (bytes `00 00 00 01`).
4. Both `VFFFMpegDecoder::openVideo @ 0011c9f4` and `VFCustomDecoder::openVideo @ 0011d0f0` create a data source. The last argument (`useUSB`) selects it:
   - `useUSB == 1` creates `VFUSBDataSource`.
   - Anything else creates `BasicTaskScheduler::createNew(10000)` + `VFUsageEnvironment` + **`VFRtspDataSource`**.
   - Java passes `false`, so the data source is RTSP.
5. `VFDecoder::_openDataSource @ 0011c710` calls `dataSource->connect(url, timeout)` and waits in 50 ms steps until the data source reports SPS+PPS (`didCatchBasicInfo`).
6. Timeout unit: `VFRtspDataSource::connect` waits `timeout * 1e6` µs (constant `DAT_0011b498 = 1000000.0`). So the `2000` from Java means about 2000 s, effectively "no timeout". OBSERVED arithmetic. That Java meant ms is INFERRED. Use your own ~5 s timeout.

### 1.3 RTSP session details (OBSERVED)

- `VFRtspDataSource::VFRtspDataSource @ 0011b1d8` → `RTSPClient(env, url=NULL, verbosity=0, appName="VFLive", tunnelOverHTTPPortNum=0, socket=-1)`.
  - INFERRED: the User-Agent header is `VFLive (LIVE555 Streaming Media v2017.01.26)` (version string at libvfvideo 0x3786fe).
  - No RTSP-over-HTTP tunnel.
  - No `Authenticator` (NULL in DESCRIBE, SETUP and PLAY), so **no RTSP auth**.
- `_thread_client @ 0011b2e8` → `sendDescribeCommand`.
- `_continueAfterDESCRIBE @ 0011b6ec` → `MediaSession::createNew(sdp)` → `_setupNextSubsession`.
- `_setupNextSubsession @ 0011b82c` does three things for each subsession:
  - It skips every subsession whose medium is `"audio"` (string at 0x377665).
  - It calls `MediaSubsession::initiate(-1)`, so live555 chooses an even/odd client RTP/RTCP port pair.
  - It calls `setReceiveBufferTo(rtpSocket, 0x80000)`, a 512 KiB UDP receive buffer.
- The SETUP call is at 0x11b9b8-0x11b9c8. Raw Thumb from `libvfvideo.so`:
  ```
  2200       movs r2,#0          ; (r2 reloaded below with handler)
  e9cd 2200  strd r2,r2,[sp]     ; sp[0] streamUsingTCP = 0, sp[4] forceMulticastOnUnspecified = 0
  2300       movs r3,#0          ; streamOutgoing = 0
  9202       str  r2,[sp,#8]     ; authenticator = NULL
  ```
  **The client requests `Transport: RTP/AVP;unicast;client_port=a-b` (UDP), not interleaved TCP.**
- `_continueAfterSETUP @ 0011ba0c` → `VFRtpSink::createNew` → `startPlaying`, and sets an RTCP BYE handler. live555 sends RTCP receiver reports automatically on the RTCP port.
- PLAY covers the whole session. `_continueAfterPLAY @ 0011bc58` arms a timer only if the SDP gives a duration > 0, which a live stream does not. It then sets the data source status to 2 ("playing"). **No explicit RTSP keep-alive** (GET_PARAMETER/OPTIONS) is sent. live555 relies on RTCP RRs. INFERRED: the server's session timeout is satisfied by RTCP.
- The multicast group `228.67.43.91` from doc 03 is the arbitrary group that live555's own `ourIPAddress() @ 000c2768` joins to discover the local IP. It is **not a video stream**. OBSERVED (string ref resolved to 0x3775e2).

### 1.4 What happens to the NAL units (OBSERVED)

- `VFRtpSink::VFRtpSink @ 0011a400` parses `sprop-parameter-sets` (`parseSPropParameterSets`) and pushes record 0 (SPS) and record 1 (PPS) into the data source before any RTP arrives.
- `VFRtpSink::continuePlaying @ 0011a560` / `afterGettingFrame @ 0011a520` call `getNextFrame` on live555's `H264VideoRTPSource` into a 200000-byte `VFPacketItem`, one **complete NAL without a start code** per call. They then call `pushPacket(size)`.
- `VFDataSource::pushPacket @ 0011ac7c` handles each NAL by type:
  - It accepts only NAL types **1** (non-IDR), **5** (IDR), **7** (SPS) and **8** (PPS). It drops everything else (SEI, AUD and so on).
  - It caches the first SPS (≤ 0x403 bytes at +0x41) and the first PPS (at +0x448).
  - On an IDR with auto-optimise on (the default, `this[0x40] = 1` in `VFDataSource::VFDataSource @ 0011a878`), it calls `_clearPackets(1)`. That flushes the backlog, which is the original's low-latency trick.
  - The default packet timeout is 50000 µs (`this+0x38 = 50000`).
- Resolution: `VFDataSource::getVideoWidth/Height @ 0011aa4e` calls `VFRtpPacker::parseSPS`. No hard-coded resolution exists.
- **HW path to Java** (`VFCustomDecoder::openVideo @ 0011d0f0` → callback at `+0x44` = `openVideoFunc @ 0011f1b0`):
  - Java `openVideoHw(byte[] sps, byte[] pps, int w, int h)` gets `sps = 00 00 00 01 + SPS`. In the pseudocode the PPS array is written with the start code and then the PPS, both at offset 0, so the start code is overwritten (likely a decompiler artefact or harmless bug). Kotlin should simply prefix both with `00 00 00 01`.
  - Each frame then goes through `decodeFrameFunc @ 0011f340` → Java `decodeFrameHw(byte[], len+4)`, with the buffer pre-filled with `00 00 00 01`. So Java gets **Annex-B, one NAL per call**.
  - Java configures `video/avc`, `csd-0`/`csd-1` and `"profile" = 2` (`vf/video/VFVideo.java:249-273`).
- **No custom framing on the network path.** The `tag/result/error/last skip` framing is only in `VFUSBDataSource` / `VFUSBProtocol`, which is unused (doc 03 §3.1).

### 1.5 Kotlin sketch: video

**Option A (try first):** Media3 RTSP, with every socket on the station `Network`.

```kotlin
const val VF_VIDEO_URL =
    "rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264"

// Media3 opens RTP DatagramSockets without a Network hook. Either bind the process
// while the flight screen is open, or use option B.
cm.bindProcessToNetwork(stationNetwork)          // undo in onStop(): bindProcessToNetwork(null)
val player = ExoPlayer.Builder(ctx)
    .setLoadControl(DefaultLoadControl.Builder()
        .setBufferDurationsMs(200, 1000, 100, 100).build())
    .build()
val source = RtspMediaSource.Factory()
    .setUserAgent("VFLive (LIVE555 Streaming Media v2017.01.26)")
    .setTimeoutMs(5_000)
    // .setForceUseRtpTcp(true)  // only if the probe in §5 shows the server accepts interleaved
    .createMediaSource(MediaItem.fromUri(VF_VIDEO_URL))
player.setMediaSource(source); player.setVideoSurfaceView(surfaceView); player.prepare(); player.play()
```

**Option B (matches the original, no process binding):** a minimal RTSP client.

```kotlin
class VfRtspClient(private val net: Network, private val url: String = VF_VIDEO_URL) {
    fun start(surface: Surface) {
        val ctl = net.socketFactory.createSocket("192.168.1.240", 8557)
        val rtp = DatagramSocket(null).apply { reuseAddress = true; receiveBufferSize = 512 * 1024
                                               bind(InetSocketAddress(0)) }   // pick an even port pair
        net.bindSocket(rtp); val rtcp = DatagramSocket(rtp.localPort + 1).also(net::bindSocket)
        // OPTIONS → DESCRIBE (Accept: application/sdp) → parse a=control, sprop-parameter-sets,
        // skip m=audio → SETUP <control> Transport: RTP/AVP;unicast;client_port=${rtp.localPort}-${rtcp.localPort}
        // → PLAY (Session: …, Range: npt=0.000-). User-Agent "VFLive". Send RTCP RR every ~5 s, or
        // GET_PARAMETER every timeout/2 if the Session header has ;timeout=.
        // RTP loop: 12-byte header (+CSRC/ext), PT 96(?) → depacketize:
        //   type 1..23 single NAL; 24 STAP-A; 28 FU-A (S/E bits) → NAL
        //   keep only NAL types 1,5,7,8 (like VFDataSource::pushPacket)
        //   on a sequence gap: drop until the next IDR (like _hwExpectKeyFrame)
        // Decoder: MediaFormat("video/avc", w, h from SPS) csd-0 = 00000001+SPS, csd-1 = 00000001+PPS,
        //   KEY_LOW_LATENCY = 1; queueInputBuffer(00000001+NAL, pts = rtpTs*1_000_000/90_000)
    }
}
```

Either way, close and reopen the stream after ~60 consecutive missing frames (original `SetCloseAfterLostFrame(60)`, doc 03 §1.3).

---

## 2. Task B: VF config protocol

### 2.1 Endpoints and transport (OBSERVED)

- `jni_vfconfig_init @ 0003a648` creates `VFPddlProtocol(usesUSB)`. `VFPddlProtocol::VFPddlProtocol @ 000334e8` creates **two** `VFProtocolSocket(type 0, usesUSB)`: `this+0x10` = ground and `this+0x14` = air.
- `VFProtocolSocket::VFProtocolSocket @ 0002d77c`:
  - It wraps a `VFAsyncSocket(VFNetSocket(type 0))` (USB socket only if usesUSB, which is false).
  - It builds the keepalive packet `VFProtocolPacket(0, NULL, 0)`, which is the 2 bytes `00 00`.
  - It creates a 1024-byte RX `ByteQueue` and calls `setRecvTimeout(2000)` (`mov.w r1,#2000` at libvfconfig 0x2d84a).
- `VFNetSocket::newSocket @ 0002bea0` calls `socket(AF_INET, type==0 ? SOCK_STREAM : SOCK_DGRAM, 0)` + `SO_REUSEADDR`. Type 0 gives **TCP**.
- `VFPddlProtocol::executeCommand @ 00033838`, cmd 0x51 (Connect), calls `VFProtocolSocket::connect(host, 0x4d2 = 1234, timeout)`:
  - ground host = `"192.168.1.2"` (`DAT_00033b30+0x339da` → libvfconfig 0x52608)
  - air host = `"192.168.1.240"` (`DAT_00033b2c+0x339f6` → 0x52614)
  - the port immediate `movw r2,#0x4d2` is at 0x33a08
- Timeout: Java `TryConnectGround(ms)` passes `ms*1000` µs (`vf/config/VFConfig.java:531-536`).
- **Not** `.11` / `.12` (doc 03 guessed those). In libvfconfig, `192.168.1.11` / `.12` are only used by `jni_vfconfig_execute @ 0003a798`, cmd 0x26 = UpdateDlFirmware:
  - an FTP client to `.11` (ground) or `.12` (air)
  - user `admin`, pass `admin123`
  - `STOR a.bin`
  OBSERVED strings at 0x53821, 0x5382e, 0x526cd, 0x53853, 0x5386b.
- INFERRED network picture: `.2` = VxFly ground radio/controller, `.240` = VxFly air unit (radio + H.264 encoder), `.11` / `.12` = PowerVision station / flight computers (MAVLink 20002). The pDDL radios bridge at L2, so `.240` is reachable from the phone on the station AP.

### 2.2 Framing (OBSERVED)

```
+--------+--------+-------------------------+
| cmd u8 | len u8 | payload[len] (ASCII)    |
+--------+--------+-------------------------+
```

- TX: `VFProtocolPacket(uchar cmd, void* data, uchar len) @ 0002d5f8` writes `buf[0]=cmd, buf[1]=len, memcpy(buf+2, data, len)`. Total length is `len+2`. There is no terminator, sync byte, sequence number or checksum.
- RX:
  - `VFProtocolSocket::didRecvCallback @ 0002d9dc` appends bytes to the queue.
  - `packetIntact @ 0002da56` returns `peekAt(1)+2` once that many bytes are queued.
  - `getPacket` pops exactly that many bytes.
  - So replies use the **same framing**, with the reply's `cmd` byte equal to the request cmd (dispatch switches on `getCommand()`).
- Payload is text. Integers are formatted with `sprintf("%d")` (`setDlChanel @ 000331c4`, `setVideoRate @ 00033450`, `setDlPower`, `setCountryCode`, `setDlSQThresh`, `setVideoResolution`). Replies are parsed with `vsscanf(data, fmt)` (`VFProtocolPacket::scan @ 0002d710`), after the RX buffer is NUL-terminated at `len`.
- Success test: `VFPddlProtocol::didRecvCallback @ 00033600` treats `strcasecmp(data, "FAILED") != 0` as success (string 0x525fc). `VFPddlPacket::success` compares against `"SUCCESS"`, but nothing in the dispatch uses it. INFERRED: set commands answer `SUCCESS` or `FAILED`.

### 2.3 Session, keepalive and timeouts (OBSERVED)

1. On connect, `VFPddlProtocol::didConnectCallback @ 000335b4` does three things:
   - calls `setRecvTimeout(5000)` (`movw r1,#0x1388` at 0x335c8)
   - **sends `1C 00` (loginDl, cmd 28)**
   - reports `didExecute(ground, 81, connected)` to Java
2. `VFProtocolSocket::setRecvTimeout @ 0002d8c0` does three things:
   - stores `ms*1000` µs (`mov.w r2,#1000; muls`) in the async socket
   - if the socket is connected and the timeout is > 0, schedules the keepalive task after **timeout/2**
   - the keepalive task `FUN_0002dbc8` sends the `00 00` packet and re-arms itself every timeout/2
3. The receive watchdog `FUN_0002d12c` polls the socket with a 10 ms select. If no byte has arrived for `recvTimeout`, it calls `close()`, which reports cmd 83 (Close) to Java. `FUN_0002d1e8` reads up to 1024 bytes per pass, refreshes the last-RX timestamp, and closes the socket on `recv <= 0`.
4. These replies call `setRecvTimeout(2000)` again (`mov.w r1,#2000` at 0x337ba):
   - the reply to `1C` (login)
   - the reply to `10` (battery), which falls through into the `0x1c` case
   After that the keepalive runs **every 1 s** with a 2 s dead-man. GetDlRadioStatus (`1F`) sets the timeout to 5000 before it sends (0x33ac6).
5. INFERRED: the unit must send something at least every 2 s, either an echo of `00 00` or a status frame, or the original app would drop the link constantly. Cmd 0 replies hit no `case` in `didRecvCallback` and are ignored. So a Kotlin client should ignore cmd 0 and treat **any RX byte** as alive.

### 2.4 Command table

Socket routing is from `executeCommand @ 00033838` (OBSERVED). The command ids match `vf/config/VFConfig.java:7-46`.

| cmd (hex/dec) | Name | Socket | Request payload | Reply payload → Java (`VFConfig.didExecute`, `vf/config/VFConfig.java:74-291`) |
|---|---|---|---|---|
| 00 / 0 | keepalive | both | none | ignored |
| 1C / 28 | DlLogin (auto on connect) | both | none | only resets timeout |
| 51 / 81, 52 / 82, 53 / 83 | Connect / IsConnected / Close | local only, no frame | | 81 = `didGroundConnect(Failed)`, 83 = disconnect |
| 03 / 05 | GetApSSID / GetApPassword | ground | none | string |
| 04 / 06 | SetApSSID / SetApPassword | ground | text | ok/FAILED |
| 07 / 09 / 0B | CuReset / CuReboot (= ApplyApConfig) / UnbindDl | per `ground` flag | none | ok/FAILED |
| 0C, 0D, 0E, 0F, 1D, 1E | Model, SN, DlHW, DlSW, HW, SW version | per flag | none | string (`PV-FTA-S-V…` for ground SW) |
| **10 / 16** | GetBatteryVoltage | **ground** | none | `"%lf"` volts → native percent ×100 (§2.5) → Java `/100.0` |
| 11 / 17 | BindDl (pairing), reply = DL connect status | ground | none | `"%d"` |
| 12 / 18 | GetDlRSSI | ground | none | `"%d"` |
| 13 / 19 | GetDlChannel | ground | none | `"%d"` |
| **14 / 20** | SetDlChannel | **ground** | `"%d"` channel (UI 6…19) | ok/FAILED |
| 15 / 21, 16 / 22 | Get / SetDlPower (app sets 24) | per flag / ground | - / `"%d"` | `"%d"` / ok |
| 17 / 23, 18 / 24 | Get / SetVideoResolution | **air** | - / `"%d"` | `"%d"` / ok |
| 19 / 25, **1A / 26** | Get / **SetVideoRate** | **air** | - / `"%d"` Mbps (1…4) | `"%d"` / ok |
| **1B / 27** | ApplyDlConfig | **ground** | none | ok/FAILED |
| **1F / 31** | GetDlRadioStatus | per flag | none | `"rssi,snr,noise"` (Java splits on `,`; needs exactly 3 ints) |
| 20 / 32, 21 / 33 | Get / SetCountryCode (0/44/86) | per flag | - / `"%d"` | `"%d"` / ok |
| 22 / 34, 23 / 35 | Get / SetDlSQThresh | per flag | - / `"%d"` | `"%d"` / ok |
| 24 / 36 | ScanDlChannels | air (as decompiled) | none | `"%d"` |
| 25 / 37, 26 / 38 | UpdateFirmware / UpdateDlFirmware | not this socket | | VFUpdate / FTP (§2.1) |

Notes:

- Java's `execute()` returns `> 0` as soon as the frame is **queued** on a connected socket, not when the reply arrives. `FlyStationFragment` sends `SetDlChannel` and then `ApplyDlConfig` back-to-back without waiting (`gcs/ui/fgt/fly/FlyStationFragment.java:168-170`). OBSERVED.
- Signal bars use **SNR** only (`vfsdk/VFCallback.java:296-299`). The thresholds are `<0` = no link icon, `0` = 0 bars, `≤5` = 1, `≤10` = 2, `≤20` = 3, `≤25` = 4, otherwise 5 (`gcs/manager/PowerManager.java:673-695`). OBSERVED.

### 2.5 Battery mapping (OBSERVED, `didRecvCallback` case 0x10)

1. `scan("%lf", &V)` reads the voltage. Java gets the string `"%.3f"` of it.
2. `mV = (int)(V*1000)`. If `mV - last == 1` the previous value is kept, a 1 mV hysteresis.
3. The percent is then:
   - `mV < 3400` → `0`
   - `mV >= 4050` → `10000`
   - otherwise `(int)((table[mV] + 0.005) * 100)`
4. Java shows `value/100.0` %.
5. `table` is a `double[]` indexed by mV at libvfconfig file offset `0x3d710 + mV*8`. It is a single Li-ion cell curve. Every 25 mV:

```
3400 0.20  3425 1.08  3450 2.15  3475 3.54  3500 5.40  3525 8.02  3550 11.55 3575 15.77
3600 20.93 3625 28.63 3650 40.58 3675 50.51 3700 57.36 3725 62.88 3750 67.73 3775 72.22
3800 76.59 3825 80.76 3850 84.31 3875 87.19 3900 89.78 3925 92.50 3950 95.26 3975 97.54
4000 98.94 4025 99.60 4050 100
```

Linear interpolation between these points is within about 1 % of the native result (INFERRED). For an exact match, extract the 650 doubles.

### 2.6 Worked frames

Assume the phone has connected TCP to `192.168.1.2:1234`.

| Direction | Bytes | Meaning | Label |
|---|---|---|---|
| → | `1C 00` | login, sent right after connect | OBSERVED |
| ← | `1C xx …` | any payload; resets the timeout to 2 s | OBSERVED dispatch, payload unknown |
| → | `00 00` | keepalive, every 1 s (2.5 s before the login reply) | OBSERVED |
| → | `10 00` | GetBatteryVoltage | OBSERVED |
| ← | `10 05 33 2E 39 31 32` | `"3.912"` → 3912 mV → table 91.06 → 9106 → **91.06 %** | format OBSERVED, value example |
| → | `1F 00` | GetDlRadioStatus | OBSERVED |
| ← | `1F 0A 2D 36 32 2C 32 38 2C 2D 39 35` | `"-62,28,-95"` → rssi -62, **snr 28 (5 bars)**, noise -95 | split OBSERVED, values example |
| → | `1E 00` | GetSWVersion (ground) | OBSERVED |
| → | `14 02 31 32` | SetDlChannel `"12"` | OBSERVED |
| → | `1B 00` | ApplyDlConfig | OBSERVED |
| ← | `14 07 53 55 43 43 45 53 53` | `"SUCCESS"` (anything except `FAILED` = ok) | INFERRED text |

Air unit, TCP to `192.168.1.240:1234`: `1C 00` (login), then `1A 01 33` = SetVideoRate 3 Mbps, `19 00` = GetVideoRate, `17 00` = GetVideoResolution.

### 2.7 Kotlin sketch: config

```kotlin
data class VfFrame(val cmd: Int, val payload: ByteArray = ByteArray(0)) {
    val text get() = payload.toString(Charsets.US_ASCII).trimEnd('\u0000')
    val ok get() = !text.equals("FAILED", ignoreCase = true)
    fun encode() = byteArrayOf(cmd.toByte(), payload.size.toByte()) + payload
    companion object { fun of(cmd: Int, arg: Int) = VfFrame(cmd, arg.toString().toByteArray()) }
}

class VfFrameDecoder {                       // [cmd][len][payload]
    private var pending = ByteArray(0)
    fun feed(b: ByteArray, n: Int): List<VfFrame> {
        pending += b.copyOf(n); val out = mutableListOf<VfFrame>()
        while (pending.size >= 2) {
            val len = pending[1].toInt() and 0xFF
            if (pending.size < len + 2) break
            out += VfFrame(pending[0].toInt() and 0xFF, pending.copyOfRange(2, 2 + len))
            pending = pending.copyOfRange(2 + len, pending.size)
        }
        return out
    }
}

class VfConfigLink(private val net: Network, val host: String, val scope: CoroutineScope) {
    // ground = VfConfigLink(net, "192.168.1.2", …); air = VfConfigLink(net, "192.168.1.240", …)
    val frames = MutableSharedFlow<VfFrame>(extraBufferCapacity = 64)
    private lateinit var sock: Socket; @Volatile private var timeoutMs = 5000L; @Volatile private var lastRx = 0L
    val connected get() = ::sock.isInitialized && sock.isConnected && !sock.isClosed
    suspend fun connect() = withContext(Dispatchers.IO) {
        sock = net.socketFactory.createSocket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, 1234), 2000) }
        lastRx = SystemClock.elapsedRealtime(); send(VfFrame(0x1C))                 // login
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch { while (isActive) { delay(timeoutMs / 2); send(VfFrame(0x00)) } } // keepalive
    }
    private fun readLoop() {
        val dec = VfFrameDecoder(); val b = ByteArray(1024); sock.soTimeout = 250
        while (true) {
            val n = try { sock.getInputStream().read(b) } catch (_: SocketTimeoutException) { 0 }
            if (n < 0) break
            if (n > 0) { lastRx = SystemClock.elapsedRealtime()
                dec.feed(b, n).forEach { if (it.cmd == 0x1C || it.cmd == 0x10) timeoutMs = 2000; if (it.cmd != 0) frames.tryEmit(it) } }
            if (SystemClock.elapsedRealtime() - lastRx > timeoutMs) break               // dead-man, like FUN_0002d12c
        }
        sock.close()                                                                     // caller reconnects
    }
    fun send(f: VfFrame) = synchronized(this) { sock.getOutputStream().write(f.encode()) }
}

// Flight-screen poll, same as VFConfigController.UpdateTimeTask (first run at 1 s, then every 5 s):
scope.launch { delay(1000); var sw = true
    while (isActive) {
        if (!ground.connected) ground.connect() else {
            ground.send(VfFrame(0x10)); ground.send(VfFrame(0x1F)); if (sw) { ground.send(VfFrame(0x1E)); sw = false }
        }
        delay(5000)
    }
}
// Reply handling:
//  0x10 → batteryPct = pctFromMv((it.text.toDouble()*1000).toInt())   // table §2.5
//  0x1F → it.text.split(',').takeIf { it.size == 3 }?.map(String::toInt)?.let { (rssi, snr, noise) -> bars(snr) }
// Link settings: ground.send(VfFrame.of(0x14, ch)); ground.send(VfFrame(0x1B)); air.send(VfFrame.of(0x1A, mbps))
```

---

## 3. What PowerSDK / the app layer adds

Nothing changes the endpoints above. `PVStationManager` duplicates the API through `PowerSDKBaseStation`, but nothing instantiates it (doc 03 §3.2). `VFConfig.defaultConfig()` is `new VFConfig(false)`, so the network path is used (`vf/config/VFConfig.java:65-70`). OBSERVED.

## 4. Reproduce the URL decode

```python
import struct
d = open(r"apk/native/libvfvideo.so", "rb").read(); B = 0x10000
u32 = lambda a: struct.unpack_from("<I", d, a - B)[0]
src = u32(0x11b1d0) + 0x11b18e; key = u32(0x11b1d4) + 0x11b1b2      # 0x37e2e0, 0x387690
s = bytearray(d[src - B: src - B + 0x49]).split(b"\0")[0]
k = [u32(key + 4 * i) & 0xFF for i in range(5)]
s[0] = ord("r"); s[1:] = bytes(c ^ k[i % 5] for i, c in enumerate(s[1:], 1))
print(s.decode())
```

## 5. What still needs the real hardware

Everything above is static. Each item below can be settled with one probe. The current `LinkProbe` (`app/.../link/LinkProbe.kt`) only listens, so these probes need a "send these bytes first" option. Add these endpoints:

1. **Video reachability and transport.**
   - Send: TCP `192.168.1.240:8557`, then `DESCRIBE rtsp://192.168.1.240:8557/PSIA/Streaming/channels/2?videoCodecType=H.264 RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\nUser-Agent: VFLive\r\n\r\n`.
   - Expect `RTSP/1.0 200 OK` plus an SDP with `m=video … RTP/AVP 96`, `a=rtpmap:96 H264/90000`, `sprop-parameter-sets=…` and `a=control:`.
   - Record: the SDP (resolution, profile-level-id), any `Session: …;timeout=`, and whether `channels/1` also answers.
   - A laptop on the station Wi-Fi with `ffprobe -rtsp_transport udp "<url>"` settles the same thing. Then try `-rtsp_transport tcp` to learn whether interleaved RTP is supported (that decides `setForceUseRtpTcp`).
2. **Config endpoint alive and reply text.**
   - Send: TCP `192.168.1.2:1234`, send `1C 00`, wait 300 ms, send `10 00`, wait 300 ms, send `1F 00`, then listen 3 s.
   - Record the first ~64 bytes.
   - This confirms three things:
     - (a) the port
     - (b) the login reply payload
     - (c) the battery and radio-status text formats, and the field order (`rssi,snr,noise`)
3. **Keepalive behaviour.** On the same socket, send only `00 00` every 1 s for 10 s and log RX. That tells you whether the unit echoes `00 00`, sends its own frames, or stays silent. If it is silent, relax the dead-man timer.
4. Optional, air unit: TCP `192.168.1.240:1234`, send `1C 00`, `19 00` and `17 00`. This confirms the air config socket and reads the current bitrate and resolution codes before you offer the Link-settings UI.
5. Do **not** send `14`/`1B` (channel), `1A` (rate), `04`/`06`/`09` (AP settings/reboot), `07`/`0B` (reset/unbind) or `11` (bind) during probing. They change the radio link and can unpair the drone.
