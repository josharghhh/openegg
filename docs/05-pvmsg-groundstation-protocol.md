# 05 - PvMsg ground-station / RF / AP protocol (libpvsdk_api-release.so)

Source: Ghidra decompile `<ghidra-out>/libpvsdk_api-release.c` (each function begins `// ==== <name> @ <addr>`), the raw binary `apk\native\libpvsdk_api-release.so` (constant tables, strings, relocations), and the jadx Java under `apk\decompiled\jadx\sources\`. I searched with Python scripts in the session scratchpad. Nothing under `apk\` or `re_work\` was modified.

**Address convention.** Ghidra loaded the .so at image base **0x10000**. Every address in this document is a Ghidra address. To get the file vaddr / offset of a table, subtract 0x10000. For example, CRC table "HI" is at Ghidra 0x175c16, which is vaddr 0x165c16. PIC literals (`DAT_x + pc`) were resolved against the binary, and GOT slots were resolved through `.rel.dyn` symbol relocations.

Tags:
- **OBSERVED** = read directly from code, data or strings, with the function/address cited.
- **INFERRED** = my interpretation.

---

## 0. Read this first

1. **The PowerEgg app (v1.7.3) never runs this protocol.**
   - The only Java user of the PvMsg API is `gcs/sdk/PVStationManager.java` (`tryConnectGroundWithTimeout(2000)` at line 56). Nothing in the app constructs that class: a grep for `new PVStationManager` finds nothing, and `docs/01-powersdk-interface.md` already lists it as dead code.
   - The live PowerEgg station path uses the **Vxfly `libvfconfig.so`** (`VFConfig.TryConnectGround`, `SetApSSID`, `SetDlChannel`, `UpdateFirmware`; see `docs/03-video-and-link.md` section 3) (OBSERVED `FlyFragment.java:993-1020`, `FlyStationFragment.java:98-100`, `PowerManager.java:63`, `FlyFragment.java:1609`).
2. **PvMsg is the alternative "PV-Link" station firmware protocol.**
   - `PVStationManager.onIsPvLink(false)` falls back to `VFConfig` (OBSERVED `PVStationManager.java:121-135,576-580`).
   - So PvMsg is what a PowerVision-firmware station would speak. VFConfig is what the shipped Egg station ("PV-FTA-S-V", a Vxfly unit) speaks (INFERRED from `FlyFragment.java:1003-1006`, which parses `PV-FTA-S-V` from `vfConfig.GetGroundSWVersion()`).
3. **Nothing in this protocol is needed before MAVLink or video work.**
   - MAVLink runs on its own TCP socket (192.168.1.12:20002, or the RC at 192.168.1.11:20002). PvMsg uses 192.168.1.2:1234. No PvMsg code path gates `connect()`/`connectDrone()` (OBSERVED: `TryConnectGroundWithTimeout` only starts its own threads, `0x000e80c8`).
   - There is no pairing or handshake beyond a 1 Hz heartbeat. `RFPair` (cmd 0x0E) only re-binds air and ground radios.
4. **The other classes in scope are not part of PvMsg.**
   - `Dl01BaseStation` speaks AT commands over TCP.
   - `Mt03RemoteControl`/`Mt03RemoteTcp` speak MAVLink over TCP.
   - `TelnetClient` is telnet plus FTP plus `mtd write` of a QCA9342 Wi-Fi module.
   - `YModemProtocol` is plain YModem tunnelled through the MAVLink connection to the RC.
   - `FirmwareUpload`/`Ap01FirmwareUpload` use MAVLink `FILE_TRANSFER_PROTOCOL` (msg 110).
   - `Protocol`/`ConcrateProtocol` implement the payload-mount "MountMessage" codec.
   - Of these, only the RC YModem upgrade and the MAVLink-FTP flight-controller upgrades are reachable from the v1.7.3 PowerEgg UI. See section 5.

---

## 1. Devices, transport, connection order

| Item | Value | Evidence |
|---|---|---|
| Peer | "Ground" = base station (ground radio + AP); "Air" = the radio in the aircraft, reached *through* the ground station | API naming `Get/SetGround*`, `Get/SetAir*` (`0x000e85ec`...`0x000e9bd4`); OBSERVED |
| Transport | **TCP client**, `socket(AF_INET, SOCK_STREAM, 0)` | `ClientConnectTry @ 0x000f5ee0`; OBSERVED |
| Address | **192.168.1.2 : 1234** (hard-coded strings at 0x166a4b / 0x170c34) | `Thread_Client @ 0x000f6718` calls `ClientConnectTry("192.168.1.2","1234")`; OBSERVED |
| Socket options | `setsockopt(fd, SOL_SOCKET(1), SO_KEEPALIVE(9), 0)`, i.e. keep-alive off | `Thread_Client`; OBSERVED |
| Connect retry | `connect()` every 1 s forever; gives up only if `strerror` == "Bad file descriptor" | `ClientConnectTry`; OBSERVED |
| Who is the peer for the Egg? | 192.168.1.2 is also the default ground IP inside Vxfly `libvfconfig.so` (string at file off 0x42608), the FTP target for station firmware (`PVStationManager.java:561`, `root`/`5up`) and the telnet/FTP target of `TelnetClient` | OBSERVED; same box, **INFERRED** |
| Phone Wi-Fi | SSID prefix `PowerEgg_Station_` (`GlobalConfig.java:22`) | OBSERVED |

**Start-up sequence** (`TryConnectGroundWithTimeout(long)` @ 0x000e80c8, JNI `Java_..._tryConnectGroundWithTimeout` @ wrapper 0x00035874). OBSERVED:

1. `pthread_key_create` (per-thread decoder state), then `SockfdSet_Init()`.
2. `CallbackFill_PvProtocol(table)` fills 49 handler pointers (0xC4 bytes). `PvProtocol_Init(PvMsgSendBySocket, table)` stores the send function (`PvCmd_Init`) and copies the table.
   - The send-function GOT slot 0x17fd78 relocates to symbol `PvMsgSendBySocket` (OBSERVED via `.rel.dyn`).
3. `ClientConnectInit(&struct_main_thread_param_local_client)` starts `Thread_Client`. Struct word 0 = socket fd; the GOT slot 0x17fd74 is symbol `struct_main_thread_param_local_client`.
4. `pthread_create(FUN_000e8180)` starts the **isPvLink probe** (section 4).
5. The `timeout` argument is **ignored** (stored, never read).
   - `TryConnectAirWithTimeout`, `DisconnectWithGround` and `DisconnectWithAir` are empty stubs that return 1 (0x000e9154, 0x000e82dc, 0x000e9164). The library never closes the socket on request.

`Thread_Client` creates four threads per connection. OBSERVED:
- `Thread_ClientRecieveMsg` (`recv` 1024 bytes, then feeds `PvMsg_DecodeByByteToPack`, then queues good frames in `BufMsg`).
- `Thread_ClientHandleMsg` (pops every 10 ms, calls `PvProtocol_HandleMessagePacket`).
- `Thread_ClientHeardbeatSend`.
- `Thread_ClientDetectConnectStatus`.

**Order relative to MAVLink.** The two are independent: different sockets and different threads. The PowerEgg UI starts MAVLink first (`initPowerSDK()` to 192.168.1.12:20002), then VFConfig (`FlyFragment.java:1674-1681`). PvMsg is never started. If you add it, start it in parallel and treat it as optional (INFERRED).

---

## 2. Frame format (OBSERVED: `PvMsg_Encode @ 0x000f40c8`, `PvMsg_Decode @ 0x000f3f24`, `PvMsg_DecodeByByteToPack @ 0x000f3a80`)

```
off  size  field            value / notes
0    4     magic            5A 5A 5A 5A
4    1     ver              always 0x01 (Encode writes the literal 1, ignores struct[0])
5    1     r1               0x00 from every sender in the lib          (meaning unknown)
6    1     r2               0x00 from every sender in the lib          (meaning unknown)
7    1     link / target    app sends 0xFF; GS heartbeat: 0xFF = air linked, 0xF0 = air not linked
8    1     msgType          0x01 = command. HandleMessagePacket ignores any other value
9    1     len              payload length, 0..64 (Encode rejects >= 0x41; decoder rejects > 0x40)
10   len   payload          starts with u16 cmdId (little-endian)
10+len 2   checksum         16-bit, HIGH byte first (big-endian on the wire)
```

- **Maximum frame:** 4 + 6 + 64 + 2 = **76 bytes**. The decoder's per-thread buffer is `malloc(0x4c)`.
- **Endianness:**
  - Every multi-byte field *inside the payload* is little-endian. It is native ARM memory order, copied by `byte_copy_2/4` (`0x000f44a8`, `0x000f44dc`) and read by `pv_return_uint16_t/uint32_t/float` (`0x000f45cc`, `0x000f4608`, `0x000f4530`).
  - Only the checksum is big-endian. Encode writes `struct[0x47]` then `struct[0x46]`. Decode compares the first byte with `crc>>8` and the second with `crc&0xFF`.
- **Escaping:** none.
  - Sync is by the 4×0x5A magic only. A 0x5A inside the payload is not escaped.
  - The decoder does not re-scan after a bad frame: on a mismatch it returns to state 0 at the *next* byte.
- **Field names:** "ver/r1/r2/link" are my names (INFERRED). The library calls the 0x48-byte struct `{u8 f0,f1,f2,f3,msgType,len; u8 payload[64]; u16 crc}`. `PvMsg_GetMsgTypeFromPacket` (0x000f3a20) returns struct[4] = frame byte 8.

### 2.1 Checksum: CRC-16/ARC table plus a byte pre-add quirk

The functions (OBSERVED):
- `pv_protocol_start_checksum` → `crc_init` @ 0x000f43b0: `crc = 0x00FF`. Note: 0x00FF, not 0xFFFF.
- `pv_protocol_update_checksum` → `crc_accumulate(byte b, u16 *crc)` @ 0x000f43cc.

I verified the arithmetic against the ARM instructions (`ldrh lr,[r1]; add r0,lr,r0; strh r0,[r1]` at 0x000f43f8-0x000f4400, then the table lookups).

```c
// tables: HI = Ghidra 0x175c16 (file 0x165c16), LO = Ghidra 0x175b16 (file 0x165b16), 256 bytes each
// (HI[i]<<8 | LO[i]) == standard reflected CRC-16 table (poly 0xA001) for every i   <- verified by script
uint16_t crc_accumulate(uint8_t b, uint16_t crc) {
    crc = (uint16_t)(crc + b);            // QUIRK: 16-bit add of the data byte first
    uint8_t idx = (uint8_t)crc ^ b;       // low byte of (crc+b) XOR b
    uint8_t hi  = crc >> 8;
    return (uint16_t)((HI[idx] << 8) | (hi ^ LO[idx]));   // == (crc>>8) ^ T16[idx]
}
// covered bytes: frame[4] .. frame[9+len]  (ver, r1, r2, link, msgType, len, payload). Magic not covered.
```

- Without the `crc + b` line this would be CRC-16/MODBUS-style with init 0x00FF. With it, the checksum is **weak**. For example, the GS heartbeat with link byte 0xFF and with 0xF0 give the same CRC (0x2B45). Implement it bit-exact for interoperability, but do not rely on it for integrity (INFERRED).
- The station firmware must use the same routine, because the app's decoder uses it to accept replies (INFERRED).
- Reference values, computed with the routine above and the tables read from the binary:
  - `crc("") = 0x00FF`
  - `crc("123456789") = 0x5982`

### 2.2 Worked example: app heartbeat (cmd 0x26), as sent every second by `Thread_ClientHeardbeatSend`

`PvCmd_ParamNone_Heartbeat_Send(fd, 0xFF, 0, 1)` → `FUN_000f0990(fd, 0xFF, 0x26, 0, 1)` → `PvCmd_ParamNone_Pack` → `PvMsg_Encode`. OBSERVED call chain:

```
5A 5A 5A 5A | 01 00 00 FF 01 04 | 26 00 00 01 | EA C4
magic         ver r1 r2 lnk typ len  cmd=0x0026 a=0 st=1   crc=0xEAC4
```

CRC trace (crc after each byte):

| byte | 01 | 00 | 00 | FF | 01 | 04 | 26 | 00 | 00 | 01 |
|---|---|---|---|---|---|---|---|---|---|---|
| crc | C0C0 | 50C0 | 5050 | B450 | 3CB4 | B13D | F370 | E4F2 | 8565 | **EAC4** |

### 2.3 Stream decoder state machine (`PvMsg_DecodeByByteToPack`). OBSERVED

The decoder state is per-thread (`pthread_getspecific`). Every byte is first appended to the output buffer at index `[2]`, then the state machine runs:

| state | input | action |
|---|---|---|
| 0 | 0x5A | idx=1, count=1 → 1; else stay 0 (idx reset) |
| 1 | 0x5A | if count<3: count++; if count==3: → 2 and `crc_init`; else → 0 |
| 2..6 | any | accumulate CRC; store ver/r1/r2/link/msgType; next state |
| 7 | len | accumulate; if len>0x40 → 0 and return −2; else → 8 |
| 8 | payload | accumulate; store; when count==len → 9 |
| 9 | crcHi | must equal crc>>8, else → 0 and return −3 |
| 10 | crcLo | must equal crc&0xFF → return **1** (frame complete, `*outLen` = total bytes); else return −4 |

Quirk: in state 8 the length check happens *after* the first payload byte is stored. A `len=0` frame would swallow 256 bytes. Every real payload is ≥ 4 bytes, so a rewrite should simply treat `len==0` as "go straight to state 9" (INFERRED fix).

---

## 3. Command set

**49 command IDs, 0x01-0x31, all contiguous.** `PvProtocol_HandleMessagePacket @ 0x000ea57c` switches on the payload's u16 cmd for msgType==1. Each cmd goes to slot *i* of the 0xC4-byte handler table (`CallbackFill_PvProtocol @ 0x000ef284`). Every handler gets `(fd, link=frame[7], a=payload[2], status=payload[3], ...)`.

### 3.1 Payload families (OBSERVED from the `*_Pack` / `*_GetInfoFromPayload` functions)

All payloads start with `u16 cmd | u8 a | u8 st`. The app always sends `a = 0`.
- **In replies:** `st == 0` means success. Every handler does `if (status == 0) notify(value, true) else notify(value, false)`.
- **In requests:** the app puts 0, except Heartbeat and the Upgrade-start commands (st=1) and SignalQuality-Get (st=4). These look like request-side flags (INFERRED).

| Family | cmds | Payload layout (LE) | Len | Pack / unpack |
|---|---|---|---|---|
| **ParamFloat** | 0x01 Require, 0x02 Set, 0x03 Value | `u16 cmd, u8 a, u8 st, u32 paramId, f32 value, u8 tail` (app sends tail=1) | 13 | `PvCmd_ParamFloat_Pack @0x000efa44`, `FUN_000efb6c`, `PvCmd_ParamFloat_GetInfoFromPayload @0x000ef7d0` |
| **ParamString** | 0x04 Require, 0x05 Set, 0x06 Value | `u16 cmd, u8 a, u8 st, u32 paramId, char str[48], u8 tail` (app sends tail=0x0C) | 57 | `PvCmd_ParamString_Pack @0x000f0190` (strcpy if strlen<48 else copy 48 and NUL byte 47), `FUN_000f0308`, `..._GetInfoFromPayload @0x000eff34` |
| **ParamNone** | 0x07-0x0F, 0x26, 0x30, 0x31 | `u16 cmd, u8 a, u8 st` | 4 | `PvCmd_ParamNone_Pack @0x000f07b4`, `FUN_000f06d8` |
| **TLV ("PvCmd")** | 0x10-0x25, 0x27-0x2F | `u16 cmd, u8 a, u8 st, u16 dataLen, u8 data[dataLen]` (dataLen < 59) | 6+n | `PvCmd_Encode @0x000f7704`, `PvCmd_Decode @0x000f7628` |
| TLV data: SignalQuality | 0x10-0x13 | `s16 v1, s16 v2, s16 v3` (Java names them `ssid, snr, noise`) | n=6 | `FUN_000f15b4`, `FUN_000f1544` |
| TLV data: Channel | 0x14-0x1C | `u8 channel` | n=1 | `FUN_000f2180`, `FUN_000f2148` |
| TLV data: TxPower | 0x1D-0x25 | `u8 txPower` | n=1 | `FUN_000f2cf4`, `FUN_000f2cbc` |
| TLV data: NetworkId | 0x27-0x2F | `char id[24]` (always 24 bytes; strcpy, so uninitialised tail; zero-fill in a rewrite) | n=24 | `FUN_000f383c` (returns 0x18), `FUN_000f3808` |

Uninitialised bytes: the original sends stack garbage after the string NUL (ParamString, NetworkId) and uninitialised `value` fields. A rewrite should send zeros. INFERRED: the receiver only reads up to the NUL.

**Request/response model.** INFERRED from the handler tables, which are keyed by the same cmd/paramId that the request used:
- The app sends a request.
- The station answers with the **same cmd id** (and the same paramId for Param* commands), `st=0/≠0`, and the value filled in.
- There is no sequence number and no app-side timeout or retry: the lib just fires the Java notify when a reply arrives.
- `*Value` commands (0x03, 0x06, 0x12/0x13, 0x1A-0x1C, 0x23-0x25, 0x2D-0x2F) look like unsolicited station→app pushes. `HandlePvCmd_ParamFloatValue` (0x000ecd88) and `HandlePvCmd_ParamStringValue` (0x000edfbc) are empty, so they are ignored.

### 3.2 Full command table

Direction: A→G = app to ground station; G→A = station to app (reply or push). Handler slot = offset into the Java-notify table pointed to by `g_pCallBackCmdHandleSet`. Notify names come from matching those offsets with `JniNatives.java` notify order. That match is checked for offsets 0x00-0xF4, e.g. 0x28 = `getApConnectStatusNotify` agrees with `GetApConnectStatus @0x000e8594`. Above 0xF4 it is UNMAPPED, because `PVSDK_registerBaseStationCallBack @0x000e786c` re-orders those slots.

| cmd | Name (lib) | Family | App API that sends it (paramId) | Reply handler → Java notify |
|---|---|---|---|---|
| 0x01 | ParamFloatRequire | Float | see 3.3 | `HandlePvCmd_ParamFloatRequire @0x000eba54` → get*Notify by paramId |
| 0x02 | ParamFloatSet | Float | see 3.3 | `HandlePvCmd_ParamFloatSet @0x000ec640` → set*Notify by paramId |
| 0x03 | ParamFloatValue | Float | (none) | handler empty |
| 0x04 | ParamStringRequire | String | see 3.4 | `HandlePvCmd_ParamStringRequire @0x000ecdc4` |
| 0x05 | ParamStringSet | String | see 3.4 | `HandlePvCmd_ParamStringSet @0x000eda94` |
| 0x06 | ParamStringValue | String | `SendUpgradeGroundMcuFirmwareName` (id 0x15), `SendUpgradeGroundMainControlFirmwareName` (id 0x16) | handler empty |
| 0x07 | ApApplyConfig | None | `ApplyApConfig @0x000e8530` / `applyApConfig()` | → `applyApConfigNotify(ok)` (slot 0x24) |
| 0x08 | GroundResetRF | None | `ResetGroundRF` / `resetGroundRF()` | → `resetGroundRFNotify` (0x70) |
| 0x09 | GroundRebootRF | None | `RebootGroundRF` / `rebootGroundRF()` | → `rebootGroundRFNotify` (0x74) |
| 0x0A | GroundApplyConfig | None | `ApplyGroundRFConfig` / `applyGroundRFConfig()` | → `applyGroundRFConfigNotify` (0x78) |
| 0x0B | AirResetRF | None | `ResetAirRF` / `resetAirRF()` | → `resetAirRFNotify` (0xBC) |
| 0x0C | AirRebootRF | None | `RebootAirRF` / `rebootAirRF()` | → `rebootAirRFNotify` (0xC0) |
| 0x0D | AirApplyConfig | None | `ApplyAirRFConfig` / `applyAirRFConfig()` | → `applyAirRFConfigNotify` (0xC4) |
| 0x0E | RFPair | None | `PairRF @0x000e9c38` / `pairRF()` | → `pairRFNotify` (0xC8) |
| 0x0F | RFApplyConfig | None | `ApplyRFConfig` / `applyRFConfig()` | → `applyRFConfigNotify` (0xE4) |
| 0x10 | GetGroundRFSignalQuality | TLV sq | `GetGroundRFSignalQuality` (sends st=4, data 1,2,3) | → `getGroundRFSignalQualityNotify(v1,v2,v3,ok)` (0x68) |
| 0x11 | GetAirRFSignalQuality | TLV sq | `GetAirRFSignalQuality` (st=4, 1,2,3) | → `getAirRFSignalQualityNotify` (0xB8) |
| 0x12 / 0x13 | ValueGround / ValueAir RFSignalQuality | TLV sq | (none) | handlers empty |
| 0x14 / 0x15 | GetGround / GetAir RFChannel | TLV u8 | (none) | handlers empty |
| 0x16 | GetRFChannel | TLV u8 | `GetRFChannel` / `getRFChannel()` (data 0) | → `getRFChannelNotify(ch,ok)` (0xCC) |
| 0x17 / 0x18 | SetGround / SetAir RFChannel | TLV u8 | (none) | empty |
| 0x19 | SetRFChannel | TLV u8 | `SetRFChannel(u8)` / `setRFChannel(int)` | → `setRFChannelNotify` (0xD0) |
| 0x1A-0x1C | Value{Ground,Air,}RFChannel | TLV u8 | (none) | empty |
| 0x1D / 0x1E | GetGround / GetAir RFTxPower | TLV u8 | (none) | empty |
| 0x1F | GetRFTxPower | TLV u8 | `GetRFTxPower` is a stub returning 0 (0x000e9d80); never sent | → `getRFTxPowerNotify` (0xD4) |
| 0x20 / 0x21 | SetGround / SetAir RFTxPower | TLV u8 | (none) | empty |
| 0x22 | SetRFTxPower | TLV u8 | `SetRFTxPower` is a stub (0x000e9d8c) | → `setRFTxPowerNotify` (0xD8) |
| 0x23-0x25 | Value{Ground,Air,}RFTxPower | TLV u8 | (none) | empty. Note: dispatcher case 0x1D-0x25 is missing `break` and falls into the NetworkId decode, which is harmless |
| 0x26 | **Heartbeat** | None | `Thread_ClientHeardbeatSend`, every 1 s (link=0xFF, a=0, st=1) | `HandlePvCmd_ParamNone_Heartbeat @0x000ee598` + counter++ (section 4) |
| 0x27 / 0x28 | GetGround / GetAir RFNetworkId | TLV id24 | (none) | empty (printf only) |
| 0x29 | GetRFNetworkId | TLV id24 | `GetRFNetworkId` (sends "123") | → `getRFNetworkIdNotify(str,true)` (0xDC); on failure the lib calls **set**RFNetworkIdNotify (0xE0), a lib bug |
| 0x2A | SetGroundRFNetworkId | TLV id24 | (none) | → `setRFNetworkIdNotify` (0xE0) |
| 0x2B | SetAirRFNetworkId | TLV id24 | (none) | empty |
| 0x2C | SetRFNetworkId | TLV id24 | `SetRFNetworkId(str)` / `setRFNetworkId(String)` | handler empty (printf) |
| 0x2D-0x2F | Value{Ground,Air,}RFNetworkId | TLV id24 | (none) | empty |
| 0x30 | GroundMcuUpgradeStart | None | `StartUpgradeGroundMcu` (st=1) | handler empty |
| 0x31 | GroundMainControlUpgradeStart | None | `StartUpgradeGroundMainControl` (st=1) | handler empty |

Every sender passes `link=0xFF` and `a=0`. Send wrappers return 0, −2 (pack error) or −3 (`send()` == −1) (OBSERVED `FUN_000f0990`, `FUN_000efd0c`, `PvCmd_GetGroundRFChannel_Send @0x000f177c`). The JNI-facing API functions always return 1/true regardless.

### 3.3 ParamFloat paramIds (cmd 0x01 Require / 0x02 Set). OBSERVED from API senders (0x000e85ec...0x000ea110) and handler switch cases

Requests are sent with `value = 0.0f` (literal at 0x000e8684), `tail = 1`. Setters convert the Java `int` to float (`VectorSignedToFloat`). Notifies receive `(int)value`, except battery, which is passed as a float.

| paramId | Meaning | Get API → notify | Set API → notify |
|---|---|---|---|
| 0x01 | Ground channel | `getGroundChannel()` → `getGroundChannelNotify` (0x2C) | `setGroundChannel(int)` → `setGroundChannelNotify` (0x30) |
| 0x02 | Air channel | `getAirChannel()` → 0x7C | `setAirChannel(int)` → 0x80 |
| 0x03 | Ground operating mode, value 1="master", 2="slave" | handler printf only, no sender | - |
| 0x04 | Air operating mode (same encoding) | printf only | - |
| 0x13 | Ground Tx power | `getGroundTxPower()` → 0x34 | `setGroundTxPower(int)` → 0x38 |
| 0x14 | Air Tx power | `getAirTxPower()` → 0x84 | `setAirTxPower(int)` → 0x88 |
| 0x15 | Ground country code | `getGroundCountryCode()` → 0x3C | `setGroundCountryCode(int)` → 0x40 |
| 0x16 | Air country code | `getAirCountryCode()` → 0x8C | `setAirCountryCode(int)` → 0x90 |
| 0x19 | Video resolution | `getVideoResolution()` → 0xE8 | `setVideoResolution(int)` → 0xEC |
| 0x1A | Video rate | `getVideoRate()` → 0xF0 | `setVideoRate(int)` → 0xF4 |
| 0x1B | Ground battery power (float) | `getGroundBatteryPower()` → `getGroundBatteryPowerNotify(float,ok)` (0x6C) = Java `baseStationBattery`-style | - |
| 0x1C | Ground RSSI | printf "JACK_GetGroundRssi" only | - |
| 0x20 | Ground noise | printf "JACK_GetGroundNoise" only | - |
| 0x24 / 0x25 | unknown (slots 0x100/0x104, UNMAPPED) | no sender | - |

### 3.4 ParamString paramIds (cmd 0x04 Require / 0x05 Set). OBSERVED

Requests carry the dummy string `"123456"` (literal at 0x17086f) and `tail = 0x0C`.

| paramId | Meaning | Get → notify | Set → notify |
|---|---|---|---|
| 0x01 | **AP SSID** | `getApSSID()` → `getApSSIDNotify(str,ok)` (0x14) | `setApSSID(str)` → `setApSSIDNotify` (0x18); then `applyApConfig()` (cmd 0x07) |
| 0x02 | **AP password** | `getApPassword()` → 0x1C | `setApPassword(str)` → 0x20 |
| 0x03 | Ground network id | `getGroundNetworkId()` → 0x50 | `setGroundNetworkId(str)` → 0x54 |
| 0x04 | Air network id | `getAirNetworkId()` → 0xA0 | `setAirNetworkId(str)` → 0xA4 |
| 0x09 / 0x0A | Ground / Air RF HW version | → 0x60 / 0xB0 | - |
| 0x0B / 0x0C | Ground / Air RF SW version | → 0x64 / 0xB4 | - |
| 0x0D / 0x0E | Ground / Air main-ctrl HW version | → 0x58 / 0xA8 | - |
| 0x0F / 0x10 | Ground / Air main-ctrl SW version | → 0x5C / 0xAC | - |
| 0x11 / 0x12 | Ground / Air model | → 0x44 / 0x94 | - |
| 0x13 / 0x14 | Ground / Air SN | → 0x48 / 0x98 | `setGroundSN` → 0x4C / `setAirSN` → 0x9C |
| 0x15 | Ground MCU firmware file name | - | sent with **cmd 0x06 (Value)** by `SendUpgradeGroundMcuFirmwareName` |
| 0x16 | Ground main-control firmware file name | - | cmd 0x06 by `SendUpgradeGroundMainControlFirmwareName` |
| 0x17 | Ground MCU SW version | `GetGroundMcuSWVersion` → slot 0x108 (UNMAPPED, no Java notify) | - |

`getApConnectStatus()` sends nothing. It immediately calls `getApConnectStatusNotify(1, true)` (OBSERVED `0x000e8594`).

### 3.5 Test vectors (computed with the reproduced codec; tables from the binary)

| What | Frame (hex) |
|---|---|
| Heartbeat A→G | `5A5A5A5A 01 00 00 FF 01 04 2600 00 01 EAC4` |
| GS heartbeat, air linked | `5A5A5A5A 01 00 00 FF 01 04 2600 00 00 2B45` |
| GS heartbeat, air not linked | `5A5A5A5A 01 00 00 F0 01 04 2600 00 00 2B45` (same CRC, see 2.1) |
| ApApplyConfig | `5A5A5A5A 01 00 00 FF 01 04 0700 00 00 A345` |
| RFPair | `5A5A5A5A 01 00 00 FF 01 04 0E00 00 00 2B45` |
| GetGroundChannel (Float Require id 1, 0.0, tail 1) | `5A5A5A5A 01 00 00 FF 01 0D 0100 00 00 01000000 00000000 01 F09C` |
| SetGroundChannel(13) | `5A5A5A5A 01 00 00 FF 01 0D 0200 00 00 01000000 00005041 01 91BE` |
| GetGroundBatteryPower (id 0x1B) | `5A5A5A5A 01 00 00 FF 01 0D 0100 00 00 1B000000 00000000 01 F348` |
| Reply: ground channel = 5, ok | `5A5A5A5A 01 00 00 FF 01 0D 0100 00 00 01000000 0000A040 01 31BD` |
| GetRFChannel (TLV, len 1, data 00) | `5A5A5A5A 01 00 00 FF 01 07 1600 00 00 0100 00 E5AE` |
| Reply: RF channel = 7 | `5A5A5A5A 01 00 00 FF 01 07 1600 00 00 0100 07 212F` |
| GetGroundRFSignalQuality (st=4, 1,2,3) | `5A5A5A5A 01 00 00 FF 01 0C 1000 00 04 0600 0100 0200 0300 3261` |
| Reply: SQ (-60, 25, -95) | `5A5A5A5A 01 00 00 FF 01 0C 1000 00 00 0600 C4FF 1900 A1FF 70FC` |
| SetRFNetworkId("PV12345") | `5A5A5A5A 01 00 00 FF 01 1E 2C00 00 00 1800 505631323334350000...00 (24 B) C53B` |
| GetApSSID ("123456" zero-padded to 48, tail 0C) | `5A5A5A5A 01 00 00 FF 01 39 0400 00 00 01000000 313233343536 00×42 0C 717A` |
| SetApSSID("PowerEgg_Station_123456") | `5A5A5A5A 01 00 00 FF 01 39 0500 00 00 01000000 506F776572456767 5F53746174696F6E 5F313233343536 00×25 0C 88CE` |
| Reply: AP SSID "PowerEgg_Station_ABCD", ok | `... 01 39 0400 00 00 01000000 "PowerEgg_Station_ABCD" 00×27 0C F751` |

---

## 4. Heartbeat, link status, isPvLink. OBSERVED unless noted

Globals (names from `.rel.dyn` symbol relocations):

| Global | Ghidra GOT | Meaning |
|---|---|---|
| `g_connnected_to_ground` | 0x17fe4c | `IsConnectToGround()` / `isGroundConnected()` |
| `g_connnected_to_air` | 0x17fe50 | `IsConnectToAir()` / `isAirConnected()` |
| `g_connnected_to_air_heatbeat` | 0x17fd80 | last GS heartbeat link byte |
| `g_pCallBackCmdHandleSet` | 0x17fd68 | Java-notify function table set by `SetCallBack` |

- **App heartbeat:** every 1 s, `Heartbeat(link=0xFF, a=0, st=1)`, regardless of link state.
- **Receiving any cmd 0x26** (`PvProtocol_HandleMessagePacket`) increments a counter in the client struct (`*param_2 += 1`). Then `HandlePvCmd_ParamNone_Heartbeat` runs:
  - frame link byte 0xFF → `g_connnected_to_air_heatbeat = 1`;
  - 0xF0 → `= 0`;
  - any other value: unchanged.
- **Ground-link flag** (in `Thread_ClientHeardbeatSend`):
  - connected = 1 as soon as the counter changes;
  - every 2nd tick, if the counter did not change since the last snapshot, connected = 0.
  - So the timeout is about 2-3 s without a GS heartbeat.
- **`Thread_ClientDetectConnectStatus`** (1 s poll):
  - transition 0→1: `g_connnected_to_ground = 1` and call notify slot +4;
  - transition 1→0: `= 0` and call slot +0.
  - INFERRED mapping: these are `connectedToGroundNotify` / `disConnectToGroundNotify`. `PVSDK_registerBaseStationCallBack` swaps Java entries [0]/[1] into slots +4/+0, so the exact name binding is not proven.
  - Every second it also sets `g_connnected_to_air = ground && air_heartbeat`.
  - No code path I found calls the connected/disconnected-to-**air** notify slots (+8/+0xC) (INFERRED, from my reading of this call graph).
- **Reconnect:** `Thread_Client` waits 3 s, then polls 1 s. If the link has been down for 2 consecutive polls, it `shutdown()`s the socket, joins the 4 threads and reconnects.
- **isPvLink probe** (`FUN_000e8180`):
  - It waits until the client fd > 0 (TCP connected), then polls `IsConnectToGround()` once per second.
  - When the ground flag goes true, it calls `isPvLinkNotify(true)` (slot 0x10) once.
  - After more than 3 polls without it, it calls `isPvLinkNotify(false)` once.
  - It never calls it while the TCP connect is still failing. With nothing listening on :1234, `connect()` just retries forever, so neither true nor false is ever reported.
  - Meaning: "the station answered PvMsg heartbeats within ~4 s of TCP connect", so this station speaks PvMsg. Otherwise the app should use VFConfig (`PVStationManager.connectStation(false)`).

**Recommendation for the rebuild (INFERRED):** probe `192.168.1.2:1234` with a 1-2 s connect timeout. If it connects *and* a cmd 0x26 frame arrives within 4 s, enable the PvMsg station UI. Otherwise use the Vxfly VFConfig protocol (doc 03). Neither probe blocks MAVLink.

---

## 5. Telnet / DL01 / MT03 / YModem / firmware paths

| Component | What it does | Transport | Reachable from v1.7.3 PowerEgg UI? | Risk |
|---|---|---|---|---|
| `TelnetClient` (0x000a6fa8...0x000a7d20) | Telnet login, `cat /pvopt/5gwifi_version`, FTP-upload two images, then `mtd erase /dev/mtd2;mtd write /tmp/mi124_f1e-jffs2 /dev/mtd2 && mtd erase /dev/mtd3;mtd -r write /tmp/vmlinux_mi124_f1e.lzma.uImage /dev/mtd3`, wait for "Rebooting". `getVersionResult` refuses with "9342 version low!" | TCP **23** to 192.168.1.2 (ctor default, port 0x17); login `root` / `Pv+1`, prompt `~ #`; FTP to 192.168.1.2:21 | **No.** Only via `Mt03RemoteControl::connectTelnet` / `DroneService::connectTelnet`. The JNI exports exist in the wrapper (`Java_..._connectTelnet`, `start9342Upgrede`), but `JniNatives.java` does not declare them | **Extreme.** Raw reflash of rootfs and kernel of a QCA9342 ("mi124" board) 5 GHz Wi-Fi module. A bad image or a power loss bricks the radio, with no recovery path in the app. Drop it |
| `Dl01BaseStation` (0x000a4708...0x000a621c) | Text AT client. `sendCommand` sends `"AT"` or `"AT+" + cmd` (no CR/LF appended in code). `splicingCommand` builds `CMD` or `CMD=value`. Reply parsed for `OK` / `ERROR`, `recv` 128 B. Command list: `RSSI NOISE SNR CHANNEL CONNECTKEY SSID SWVERSION MCS BANDWIDTH TXPOWER ENAFTP SENDMD5SUM VERIFYMD5SUM UPDATE`. `uploadJob`: `AT+ENAFTP` → FTP :21 `root`/`admin123` (expects `220 `) → put file → md5 → `AT+SENDMD5SUM=…` → `AT+VERIFYMD5SUM` → `AT+UPDATE` | TCP, ip/port from caller (`connectDl01Base(ip,port)`) | **No.** Only inside `Mt03RemoteControl` (`MavlinkDrone+0x38`); Java has no `connectDl01BS`/`dl01*` natives | High (station firmware flash). Not needed |
| `Mt03RemoteControl` / `Mt03RemoteTcp` (0x000a0930...0x000a2c88) | An RC that speaks **MAVLink** (handles HEARTBEAT 0, PARAM_VALUE 22, MANUAL_CONTROL 69, COMMAND_LONG 76, MOUNT_CONTROL 157, PV_COMMAND 160) and owns a Dl01BaseStation and a TelnetClient | TCP (non-blocking, FIONBIO), ip/port from `createMt03RemoteControl(ip,port)` | **No** (`createMt03RemoteControl` is not declared in Java). INFERRED: another product's remote | n/a |
| `YModemProtocol` (0x0009f7e0...0x000a092c) | Standard YModem: 128-byte SOH / 1024-byte STX blocks, CRC-16 (`Cal_CRC16`), block-0 header with file name and size (`Ymodem_PrepareIntialPacket`). Bytes go raw through the `MavlinkConnection` (`writeBuf`/`readChar` via vtable) | Same socket as the RC MAVLink link (192.168.1.11:20002 or USB serial) | **Yes, indirectly.** `FlyFragment.java:774` `mPowerSDK.firmwareUpgrade(path)` after `connectRemote()` (192.168.1.11). Chain: `DroneService::firmwareUpgrade` → `MavlinkDrone::firmwareUpgrade` (sets RC firmware path) → `StandardRemoteControl::upgrade` thread → `*RemoteControlUpgradeLogic::doUpgrade` → YModem (INFERRED for the last hop, which is a jump-table call) | High: RC firmware. Only needed to update the RC |
| `FirmwareUpload` (0x000aae24...) | Flight-controller upgrade over **MAVLink FILE_TRANSFER_PROTOCOL (msg 110 = 0x6E, `FirmwarePacket` @0x0008b404)**: opcode 0x10 create, 0x11 write in 239-byte (0xEF) chunks every 30 ms, resend loop, then reset (opcode 2, with *this+0xc4=9). 8 s timer | MAVLink to the drone | **Yes:** `planeFirmwareUpgrade` (`StationUpgradeManager.java:233`) | Very high (FC brick) |
| `Ap01FirmwareUpload` (0x000aba00...) | Same MAVLink-FTP scheme with a 2 s per-request timer, used for newer FC firmware (ROM version ≥ 31271810) | MAVLink | **Yes:** `ap01FirmwareUpgrade` (`StationUpgradeManager.java:239`, via `newFlyControllerUpgrade()` at `FlyFragment.java:1533`) | Very high |
| `SerialConnection` (0x00080ad4...) | `open(path, O_RDWR\|O_NOCTTY\|O_NONBLOCK)`, termios 8N1 at a configured baud (`DEFAULT_USB_BAUD_RATE="115200"` in Java); a MAVLink byte stream (created by `MavlinkConnectionManager::createMavlinkConnection`) | USB-serial | Only in the `initSDKSerial` variant; PowerEgg uses TCP | Low |
| `Protocol` / `ConcrateProtocol` (+ Device/Can/Uart/I2c/Spi/Gpio/Motor subclasses, 0x000d1ebc...) | Payload-mount `MountMessage` codec used by `MountCore`: big-endian 8-byte header `u16,u16,u16,u8,u8 len`, data, 2-byte check. Check type is the low nibble of the 2nd u16: 1 = CRC-16/X-25 (poly 0x8408 reflected, init 0xFFFF, xorout 0xFFFF; `crcCheck @0x000d346c`), 2 = BCC/XOR (`bccCheck @0x000d34d8`) | Tunnelled in MAVLink (`PowerSDKMount`) | Only for mount accessories | n/a. **Not** the ground-station protocol |
| PvMsg 0x30 / 0x31 + StringValue 0x15/0x16 | "Start ground MCU / main-control upgrade" and "here is the firmware file name". INFERRED: the file is FTP'd first (`open_ftp/storfile_ftp` @0x000ea430...; Java `openFtp("192.168.1.2","root","5up")`) | PvMsg + FTP | No (dead `PVStationManager`) | High |

**Do normal users need any of this?** No, for flying, video and settings.
- Firmware flashing is only needed to *change* firmware. The aircraft and RC are on their final firmware because PowerVision is defunct.
- I recommend dropping all flashing at first (telnet/mtd, DL01 UPDATE, YModem RC, MAVLink-FTP FC, station FTP).
- If FC/RC updates are ever added, gate them behind a battery check (the app requires ≥ 40 %, `FlyFragment.java:1529`), a disarmed check, a version check and explicit confirmation.
- The telnet/mtd path should never be implemented.

---

## 6. Kotlin reimplementation sketch

```kotlin
package pv.groundlink

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CRC used by PvMsg: CRC-16/ARC table, init 0x00FF, plus the "crc += byte" quirk (crc_accumulate @0x000f43cc). */
object PvCrc {
    private val T = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }
    const val INIT = 0x00FF
    fun step(crc0: Int, b: Int): Int {
        val crc = (crc0 + (b and 0xFF)) and 0xFFFF          // quirk
        val idx = (crc and 0xFF) xor (b and 0xFF)
        return ((crc ushr 8) xor T[idx]) and 0xFFFF
    }
    fun of(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var c = INIT
        for (i in from until to) c = step(c, data[i].toInt())
        return c
    }
}

data class PvFrame(
    val link: Int = 0xFF,           // byte 7
    val msgType: Int = 1,           // byte 8
    val payload: ByteArray,         // <= 64 bytes, starts with u16 LE cmd
    val ver: Int = 1, val r1: Int = 0, val r2: Int = 0,
) {
    val cmd: Int get() = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
    val a: Int get() = payload[2].toInt() and 0xFF
    val status: Int get() = payload[3].toInt() and 0xFF
    val ok: Boolean get() = status == 0

    fun encode(): ByteArray {
        require(payload.size <= 64)
        val body = byteArrayOf(ver.toByte(), r1.toByte(), r2.toByte(), link.toByte(),
                               msgType.toByte(), payload.size.toByte()) + payload
        val c = PvCrc.of(body)
        return byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A) + body + byteArrayOf((c ushr 8).toByte(), c.toByte())
    }
}

/** Byte-at-a-time decoder, same states as PvMsg_DecodeByByteToPack, with the len==0 quirk fixed. */
class PvStreamDecoder(private val onFrame: (PvFrame) -> Unit,
                      private val onError: (Int) -> Unit = {}) {
    private var st = 0; private var n = 0; private var crc = 0
    private val hdr = IntArray(6); private var len = 0
    private val pl = ByteArray(64)

    fun feed(buf: ByteArray, off: Int = 0, count: Int = buf.size) {
        for (i in off until off + count) push(buf[i].toInt() and 0xFF)
    }
    private fun push(b: Int) {
        when (st) {
            0 -> if (b == 0x5A) { n = 1; st = 1 }
            1 -> if (b == 0x5A) { if (++n == 4) { st = 2; n = 0; crc = PvCrc.INIT } } else st = 0
            in 2..7 -> {
                crc = PvCrc.step(crc, b); hdr[st - 2] = b
                if (st == 7) {
                    if (b > 64) { st = 0; onError(-2); return }
                    len = b; n = 0; st = if (len == 0) 9 else 8
                } else st++
            }
            8 -> { crc = PvCrc.step(crc, b); pl[n++] = b.toByte(); if (n == len) st = 9 }
            9 -> if (b == (crc ushr 8)) st = 10 else { st = 0; onError(-3) }
            10 -> { st = 0
                if (b == (crc and 0xFF)) onFrame(PvFrame(ver = hdr[0], r1 = hdr[1], r2 = hdr[2],
                        link = hdr[3], msgType = hdr[4], payload = pl.copyOf(len)))
                else onError(-4) }
        }
    }
}

object PvCmd {
    const val FLOAT_REQUIRE = 0x01; const val FLOAT_SET = 0x02; const val FLOAT_VALUE = 0x03
    const val STR_REQUIRE = 0x04; const val STR_SET = 0x05; const val STR_VALUE = 0x06
    const val AP_APPLY = 0x07; const val GROUND_RESET_RF = 0x08; const val GROUND_REBOOT_RF = 0x09
    const val GROUND_APPLY = 0x0A; const val AIR_RESET_RF = 0x0B; const val AIR_REBOOT_RF = 0x0C
    const val AIR_APPLY = 0x0D; const val RF_PAIR = 0x0E; const val RF_APPLY = 0x0F
    const val GET_GROUND_SQ = 0x10; const val GET_AIR_SQ = 0x11
    const val GET_RF_CHANNEL = 0x16; const val SET_RF_CHANNEL = 0x19
    const val GET_RF_TXPOWER = 0x1F; const val SET_RF_TXPOWER = 0x22
    const val HEARTBEAT = 0x26; const val GET_RF_NETID = 0x29; const val SET_RF_NETID = 0x2C
    const val GROUND_MCU_UPG = 0x30; const val GROUND_MAIN_UPG = 0x31

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun none(cmd: Int, st: Int = 0) = le(4).putShort(cmd.toShort()).put(0).put(st.toByte()).array()
    fun float(cmd: Int, paramId: Int, value: Float = 0f, tail: Int = 1) =
        le(13).putShort(cmd.toShort()).put(0).put(0).putInt(paramId).putFloat(value).put(tail.toByte()).array()
    fun string(cmd: Int, paramId: Int, s: String, tail: Int = 0x0C): ByteArray {
        val b = le(57).putShort(cmd.toShort()).put(0).put(0).putInt(paramId)
        val raw = s.toByteArray(Charsets.UTF_8)
        val sb = raw.copyOf(48).also { if (raw.size >= 48) it[47] = 0 }   // strcpy if <48, else 48 bytes with [47]=0
        return b.put(sb).put(tail.toByte()).array()
    }
    fun tlv(cmd: Int, data: ByteArray, st: Int = 0): ByteArray {
        require(data.size < 59)
        return le(6 + data.size).putShort(cmd.toShort()).put(0).put(st.toByte())
            .putShort(data.size.toShort()).put(data).array()
    }
    fun netId(id: String) = id.toByteArray(Charsets.US_ASCII).copyOf(24)

    // --- decoders (payload = PvFrame.payload) ---
    fun paramId(p: ByteArray) = ByteBuffer.wrap(p, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    fun floatValue(p: ByteArray) = ByteBuffer.wrap(p, 8, 4).order(ByteOrder.LITTLE_ENDIAN).float
    fun stringValue(p: ByteArray) = String(p, 8, 48, Charsets.UTF_8).substringBefore('\u0000')
    fun tlvData(p: ByteArray): ByteArray {
        val n = ByteBuffer.wrap(p, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        return p.copyOfRange(6, 6 + n)
    }
    fun signalQuality(p: ByteArray): Triple<Int, Int, Int> {
        val d = ByteBuffer.wrap(tlvData(p)).order(ByteOrder.LITTLE_ENDIAN)
        return Triple(d.short.toInt(), d.short.toInt(), d.short.toInt())
    }
}

object PvParam {           // ParamFloat ids
    const val GROUND_CHANNEL = 0x01; const val AIR_CHANNEL = 0x02
    const val GROUND_TXPOWER = 0x13; const val AIR_TXPOWER = 0x14
    const val GROUND_COUNTRY = 0x15; const val AIR_COUNTRY = 0x16
    const val VIDEO_RESOLUTION = 0x19; const val VIDEO_RATE = 0x1A; const val GROUND_BATTERY = 0x1B
}
object PvStrParam {        // ParamString ids
    const val AP_SSID = 1; const val AP_PASSWORD = 2; const val GROUND_NETID = 3; const val AIR_NETID = 4
    const val GROUND_RF_HW = 9; const val AIR_RF_HW = 10; const val GROUND_RF_SW = 11; const val AIR_RF_SW = 12
    const val GROUND_MC_HW = 13; const val AIR_MC_HW = 14; const val GROUND_MC_SW = 15; const val AIR_MC_SW = 16
    const val GROUND_MODEL = 17; const val AIR_MODEL = 18; const val GROUND_SN = 19; const val AIR_SN = 20
    const val GROUND_MCU_SW = 23
}
```

Client skeleton (coroutines; INFERRED design that keeps the observed timings):

```kotlin
class PvGroundClient(private val host: String = "192.168.1.2", private val port: Int = 1234,
                     private val scope: CoroutineScope) {
    val groundLinked = MutableStateFlow(false)   // == g_connnected_to_ground
    val airLinked = MutableStateFlow(false)      // == ground && last HB link byte == 0xFF
    private var lastHb = 0L
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<PvFrame>>()  // key = cmd<<32 | paramId

    fun start() = scope.launch(Dispatchers.IO) {
        while (isActive) runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 2000); s.tcpNoDelay = true
                val out = s.getOutputStream()
                val dec = PvStreamDecoder({ f -> onFrame(f) })
                val hb = launch { while (isActive) { out.write(PvFrame(payload = PvCmd.none(PvCmd.HEARTBEAT, 1)).encode()); delay(1000) } }
                val wd = launch { while (isActive) { delay(1000)
                    groundLinked.value = System.nanoTime() - lastHb < 3_000_000_000L
                    if (!groundLinked.value) airLinked.value = false } }
                val buf = ByteArray(1024)
                while (isActive) { val n = s.getInputStream().read(buf); if (n < 0) break; dec.feed(buf, 0, n) }
                hb.cancel(); wd.cancel()
            }
        }.also { groundLinked.value = false; delay(1000) }
    }
    private fun onFrame(f: PvFrame) {
        if (f.msgType != 1 || f.payload.size < 4) return
        if (f.cmd == PvCmd.HEARTBEAT) { lastHb = System.nanoTime()
            when (f.link) { 0xFF -> airLinked.value = true; 0xF0 -> airLinked.value = false }
            return }
        val pid = if (f.cmd in 1..6 && f.payload.size >= 8) PvCmd.paramId(f.payload).toLong() else 0L
        pending.remove((f.cmd.toLong() shl 32) or pid)?.complete(f)
    }
    // e.g. suspend fun getApSsid() = request(PvCmd.STR_REQUIRE, PvStrParam.AP_SSID,
    //          PvCmd.string(PvCmd.STR_REQUIRE, PvStrParam.AP_SSID, "")) -> PvCmd.stringValue(...)
}
```

Unit tests (vectors from section 3.5):

```kotlin
class PvMsgTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun crcReference() {
        assertEquals(0x00FF, PvCrc.of(ByteArray(0)))
        assertEquals(0x5982, PvCrc.of("123456789".toByteArray()))
        assertEquals(0xEAC4, PvCrc.of(hex("01 00 00 FF 01 04 26 00 00 01")))
    }
    @Test fun heartbeatEncode() = assertArrayEquals(
        hex("5A5A5A5A 010000FF0104 26000001 EAC4"),
        PvFrame(payload = PvCmd.none(PvCmd.HEARTBEAT, 1)).encode())
    @Test fun getGroundChannelEncode() = assertArrayEquals(
        hex("5A5A5A5A 010000FF010D 01000000 01000000 00000000 01 F09C"),
        PvFrame(payload = PvCmd.float(PvCmd.FLOAT_REQUIRE, PvParam.GROUND_CHANNEL)).encode())
    @Test fun setGroundChannel13() = assertArrayEquals(
        hex("5A5A5A5A 010000FF010D 02000000 01000000 00005041 01 91BE"),
        PvFrame(payload = PvCmd.float(PvCmd.FLOAT_SET, PvParam.GROUND_CHANNEL, 13f)).encode())
    @Test fun getRfChannelEncode() = assertArrayEquals(
        hex("5A5A5A5A 010000FF0107 16000000 0100 00 E5AE"),
        PvFrame(payload = PvCmd.tlv(PvCmd.GET_RF_CHANNEL, byteArrayOf(0))).encode())
    @Test fun signalQualityRequest() = assertArrayEquals(
        hex("5A5A5A5A 010000FF010C 10000004 0600 0100 0200 0300 3261"),
        PvFrame(payload = PvCmd.tlv(PvCmd.GET_GROUND_SQ, hex("010002000300"), st = 4)).encode())
    @Test fun decodeChannelReply() {
        val got = mutableListOf<PvFrame>()
        // leading garbage + a stray 5A must be skipped
        PvStreamDecoder({ got += it }).feed(hex("00 5A 11 5A5A5A5A 010000FF010D 01000000 01000000 0000A040 01 31BD"))
        assertEquals(1, got.size); val f = got[0]
        assertEquals(1, f.cmd); assertTrue(f.ok)
        assertEquals(PvParam.GROUND_CHANNEL, PvCmd.paramId(f.payload)); assertEquals(5f, PvCmd.floatValue(f.payload))
    }
    @Test fun decodeSignalQualityReply() {
        var f: PvFrame? = null
        PvStreamDecoder({ f = it }).feed(hex("5A5A5A5A 010000FF010C 10000000 0600 C4FF 1900 A1FF 70FC"))
        assertEquals(Triple(-60, 25, -95), PvCmd.signalQuality(f!!.payload))
    }
    @Test fun decodeApSsidReply() {
        val p = PvCmd.string(PvCmd.STR_REQUIRE, PvStrParam.AP_SSID, "PowerEgg_Station_ABCD")
        val wire = PvFrame(payload = p).encode()
        assertEquals(0xF751, ((wire[wire.size - 2].toInt() and 0xFF) shl 8) or (wire.last().toInt() and 0xFF))
        var f: PvFrame? = null; PvStreamDecoder({ f = it }).feed(wire)
        assertEquals("PowerEgg_Station_ABCD", PvCmd.stringValue(f!!.payload))
    }
    @Test fun badCrcRejected() {
        var n = 0; var err = 0
        PvStreamDecoder({ n++ }, { err = it }).feed(hex("5A5A5A5A 010000FF0104 26000001 EAC5"))
        assertEquals(0, n); assertEquals(-4, err)
    }
    @Test fun weakCrcCollisionIsReal() =   // documents the quirk; do not "fix" it
        assertEquals(PvCrc.of(hex("010000FF0104 26000000")), PvCrc.of(hex("010000F00104 26000000")))
}
```

---

## 7. Open questions / what to verify on hardware

1. Does the user's PowerEgg station listen on 192.168.1.2:1234 at all? If `connect()` is refused, it is a Vxfly-only unit and none of this applies (INFERRED most likely, per section 0).
2. If it does listen: capture one GS heartbeat. Confirm the link byte 0xFF/0xF0 meaning, the `st` value in GS replies, and whether bytes 5/6 are ever non-zero.
3. Units of TxPower/Channel/CountryCode (raw values are passed through; no conversion in the lib).
4. Java notify binding for slots ≥ 0xF8 (paramIds 0x24/0x25, string 0x17), and the exact +0/+4 (ground) vs +8/+0xC (air) binding. To resolve these, decode `Java_..._registerCallback` @ wrapper 0x0002f4ec against the wrapper's `.rel.dyn`.
