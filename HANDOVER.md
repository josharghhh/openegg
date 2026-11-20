# OpenEgg handover

**State at 0.3.3:** builds and all 194 unit tests pass. It was exercised in demo mode on a Galaxy S25 Ultra, but has **not yet been tested on the real PowerEgg**.

## Setting up a new PC
1. Install **JDK 25** (e.g. Temurin) and the **Android SDK** with `platforms/android-37.0`, `build-tools/37.0.0` and `platform-tools`. Android Studio installs all of these.
2. Clone: `git clone https://github.com/josharghhh/openegg.git`
3. Create `local.properties` with `sdk.dir=C\:/path/to/android-sdk`. Use forward slashes: backslashes get mangled.
4. Build and test:
   ```
   ./gradlew :app:assembleDebug :app:testDebugUnitTest
   ```
   The APK lands in `app/build/outputs/apk/debug/`.
5. Install on the phone (USB debugging on): `adb install -r app-debug.apk`

## What's where
| Area | Location |
|---|---|
| MAVLink codec, PowerVision dialect, link | `app/src/main/java/com/openegg/app/mavlink/`; generated messages in `mavlink/gen/` (`tools/gen_mavlink.py`) |
| Telemetry, commands, alarms, session log, missions | `drone/` and `drone/mission/` |
| Base-station protocol (battery and signal, TCP 1234) | `vxfly/` |
| Live video (RTSP `rtsp://192.168.1.240:8557/...`) | `video/` |
| Camera settings and gimbal | `camera/`, `ui/camera/` |
| Map and offline areas (MapLibre/OSM) | `map/`, `ui/map/` |
| Screens | `ui/` (start, link test, flight, route, offline maps) |
| Simulators used by tests and demo mode | `link/SimulatedVehicle.kt`, `vxfly/SimulatedVxStation.kt` |
| Protocol specs (every claim marked OBSERVED or INFERRED) | `docs/01`–`docs/07` |
| First hardware test | `docs/HARDWARE-TEST.md` |

## Key facts (from the reverse-engineering)
- The phone joins the base station's 5 GHz Wi-Fi (`Power_Ground…`, password `1234567890`). The app binds to that network, which has no internet.
- Flight link: MAVLink **v1** over TCP to `192.168.1.12:20002`, with GCS sys 255 / comp 190. The aircraft runs a PowerVision fork of PX4.
- Return home is `SET_MODE` with PowerVision main mode 10; the drone reports back AUTO/RTL (`0x05040000`).
- The camera uses MAVLink msg 160 `PV_CAMERA_COMMAND` (CRC_EXTRA 133). Camera settings are `PV_CAM_*` params, with integer values carried as raw bits in the float.
- Activation is local only: PARAM_SET `PV_V_KEY_FLAG` = int bits 1. No server is involved.
- **The app never arms, takes off or lands.** The physical remote does that, as with the original. HOME and HOVER are hold-to-confirm.

## Next steps
1. **Hardware test** (motors never started): follow `docs/HARDWARE-TEST.md`. Run the **link test** first and keep the report. Afterwards, pull `Android/data/com.openegg.app/files/sessions/*.log` and `files/reports/*.txt` off the phone.
2. **Use those logs to confirm or fix the INFERRED items:** video URL and transport, VxFly reply text, MOUNT_CONTROL target and units, the arm/disarm toggle, alarm bits, SD capacity units.
3. **UI:** check that the 0.3.3 bottom-row fix (camera buttons were squeezed) looks right on the S25. It hasn't been seen on screen yet.
4. **Not yet built:** compass and RC calibration screens, safety-limit settings (RTL height, max height and distance, low-battery level), link channel and bitrate settings (the client supports them), orbit and follow-me, on-phone recording and gallery, a flight-log viewer, units. Possible later: AI subject tracking via the gimbal.
5. **Deliberately left out:** firmware flashing, and PowerVision accounts, cloud and store.

## Not in this repo (it stays on the old PC)
Under the project folder: `apk/` holds the original Vision+ 1.7.3 APK, the jadx and apktool decompile and the extracted 32-bit `.so` files. `docs/reference/` holds the PowerEgg manual. Ghidra output (`*.c`, `*.functions.tsv`) is in `C:\Users\joshfa\tools\re_work\out\`. These are PowerVision's copyrighted material and are only needed for further reverse-engineering. Copy them across privately if you want to continue that work; don't commit them.

If you re-run Ghidra under the Claude desktop app, add `-Dapplication.settingsdir=` and `-Dapplication.cachedir=` VMARGS pointing outside AppData in `support/launch.properties`. The app's packaging redirects AppData, which breaks Ghidra's plugin framework.
