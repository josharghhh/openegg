# OpenEgg — first hardware test (motors never started)

The goal of this session is to learn what the real PowerEgg says. Nothing here flies, and nothing here starts the motors.

- If the propeller blades come off easily (screws or a quick-release), take them off.
- If they don't, leave them on, folded. Keep the aircraft on its base, and keep hands, cables and the phone clear of the blades.
- Don't touch the remote's sticks. Pulling both sticks down and inward is the motor-start combination. Leave the remote in P mode.

## Before you start
- OpenEgg 0.2 is installed on the S25 (or install `OpenEgg-0.2.0-debug.apk`).
- Charge the aircraft battery, the remote and the base station.
- Have a USB cable for the PC if you can. Then Claude can read the logs live.

## 1. Power up (as in the manual)
1. Aircraft on its base, blades folded (or removed), top cover closed, lower gimbal cover removed.
2. Power on the remote and the base station, then the aircraft.
3. Wait for the aircraft LED to finish initialising.

## 2. Join the base station Wi-Fi
- Settings → Wi-Fi → `Power_Ground…` (it may also be `PowerVision_Ground…` or `PowerEgg_Station_…`), password `1234567890`.
- When Android warns "no internet", choose **stay connected / keep Wi-Fi**.

## 3. Link test (most important)
1. Open OpenEgg → **Link test** → **Probe drone link**. It takes about 20 s.
2. Tap **Share report** and send it to Claude, or tap **Copy report** and paste it.
   The report also saves itself to `Android/data/com.openegg.app/files/reports/`.
3. If something shows ERROR or NO ANSWER, that's still useful. Send it anyway.

## 4. Fly screen (motors stay off)
1. Back → **Fly**.
2. Check, and note anything that looks wrong:
   - The status pill turns green: **Connected**.
   - GPS satellites, battery % and V, and the flight mode name.
   - The horizon moves when you tilt the aircraft by hand; the heading changes when you rotate it.
   - The station pill shows the base-station battery and signal bars.
   - **Live video** appears; there may be a few seconds of "Connecting to camera…" first.
3. Camera: tap **PHOTO** once. Then **REC**, wait 5 s, **STOP**. Then check the SD card.
4. **SET** opens the camera settings panel. Look at the values. Change one harmless setting (e.g. white balance) and check that it sticks.
5. Gimbal: drag the pitch control and watch the camera tilt.
6. Do **not** test HOME / HOVER yet. Those come in a later, deliberate test.

## 5. Bring back the logs
- Each Fly session writes `Android/data/com.openegg.app/files/sessions/session-*.log`.
- Plug the phone into the PC (USB debugging on) and tell Claude. Claude will pull the logs and reports.

## Safety
- The app cannot arm, take off or land. The remote does all of that.
- The only flight commands the app has are **HOME** and **HOVER**, and both need a press-and-hold.
- If anything behaves unexpectedly: power off the aircraft first, then the remote.
