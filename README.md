# OpenEgg

An open replacement ground-control app for the original **PowerVision PowerEgg** drone. PowerVision has shut down; its Vision+ app needs dead servers and ships 32-bit-only native libraries, so it can't run on current 64-bit-only phones such as the Galaxy S25 series.

OpenEgg is pure Kotlin and Jetpack Compose. It has no native code of its own, needs no account and makes no cloud calls.

> **Status: early and untested on real hardware.** Everything here is reverse-engineered for interoperability with hardware the author owns. Fly at your own risk, follow your local aviation rules, and test with the motors off first.

## Features
- **Link** to the base station over its 5 GHz Wi-Fi (`Power_Ground…`). Flight control is MAVLink v1 over TCP `192.168.1.12:20002` (the drone runs a PowerVision fork of PX4).
- **Telemetry:** attitude, GPS, battery, speeds, home, flight mode (including PowerVision's own modes), and the drone's 55 alarm codes as warnings with spoken alerts.
- **Live video:** RTSP/H.264 from the air unit, using Media3 and hardware decoding.
- **Camera and gimbal:** photo and video, camera settings (`PV_CAM_*` params), gimbal pitch.
- **Base station:** battery and signal strength (VxFly config protocol on TCP 1234).
- **Map:** MapLibre and OpenStreetMap, with offline area download, flight trail and home line.
- **Waypoint routes:** upload, verify, start, pause, resume and abort.
- **Return home and hover:** both use press-and-hold to confirm. Arming, take-off and landing stay on the physical remote, as with the original app.
- **Link test:** probes every known endpoint and produces a shareable report. Every flight session also writes a log.
- **Demo mode:** a simulated PowerEgg for trying the UI without the aircraft.

## Building
Requires JDK 25 and the Android SDK (platform 37). Then:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

MAVLink definitions are generated from the official XML: `python tools/gen_mavlink.py tools/mavlink-xml/common.xml CommonDialect app/src/main/java/com/openegg/app/mavlink/gen/CommonDialect.kt`.

## Protocol documentation
`docs/01`–`docs/07` describe everything reverse-engineered from the original app, with each claim marked **OBSERVED** or **INFERRED**:

| Doc | Covers |
|---|---|
| 01 | The SDK interface the original app used |
| 02 | The Ambarella camera protocol (PowerRay only) |
| 03 | The video and radio link |
| 04 | MAVLink and the PX4 flight modes |
| 05 | The PvMsg ground-station protocol (unused by the PowerEgg) |
| 06 | Camera and gimbal control |
| 07 | VxFly video and base-station config |

`docs/HARDWARE-TEST.md` is the first safe hardware checklist.

This repository contains none of PowerVision's code, binaries or manuals.

## Licence
MIT. See [LICENSE](LICENSE). This project is not affiliated with PowerVision.
