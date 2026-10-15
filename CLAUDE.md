# Pinion Smart.Shift gear field for Hammerhead Karoo

A Karoo extension (`io.hammerhead:karoo-ext`) that connects to a Pinion Smart.Shift gearbox
(c1.12i and siblings) over BLE and provides two data fields: **Pinion Gear** and **Pinion Battery** (%), both
standard numeric fields drawn by Karoo.

## Status

Builds, and the protocol unit tests pass (first build 2026-10-07: Gradle 9.1.0, AGP 8.13.2, Kotlin 2.3.0,
Android Studio's bundled JDK 25). Works on a Karoo 2 against the fake gearbox `tools/fake_pinion.py` (Raspberry Pi, Bumble): connect, shifts,
battery, disconnect alert and reconnect all checked 2026-10-07.

**Real gearbox (2026-10-11, logcat exports from the session):** run on the Karoo 2 against the author's
Smart.Shift (Priority Gemini), bike stationary.
- Gear field follows shifts; gear count read as 12; battery read 89.23 % falling to 88.55 % during the shifting,
  then 88.55 % on every read for the 69 idle minutes that were logged.
- One connection held from 17:48 to the last export at 19:50 (122 minutes) with no drop. The exports have gaps
  (laptop asleep, Karoo log buffer), so this rests on the 60 s polls keeping one unbroken phase. Tim Angus's Garmin data field drops after about 90 s
  (<https://timang.us/2026-10-08-pinion-garmin-datafield/>); that is not seen here.
- From about 18:25 two Flare RT lights and a Garmin Varia were connected to the Karoo over ANT+ (traffic visible
  in the log under tag `antradio`), from 19:30 a Coros heart rate sensor over BLE, and from about 19:37 Favero
  Assioma Pro MX pedals over ANT+; no effect on the link.
- The gearbox does not go to sleep while connected: no shift or crank movement from 18:08:30 to about
  19:37 and it answered every poll. The author believes it normally switches off after some minutes idle, but the
  time is not in the manual.
- **It is the 60 s reads that keep it awake, and its idle timeout is 8 minutes.** With the poll set to off
  (status screen button) and the bike untouched: connected 20:16:14, last read 20:16:16.7, link lost at
  20:24:20.7 with GATT status 8 (supervision timeout: the gearbox goes silent, it does not send a disconnect).
  That is 480 s after the last read plus a few seconds of link timeout. Karoo's Bluetooth stayed on throughout.
  So an open link alone does not keep the gearbox awake, and without the poll a rider who does not shift for
  8 minutes loses the connection and has to put the gearbox in pairing mode again. Keep the poll on. Not tested:
  whether the gear read or the battery read alone is enough.
- The disconnect alert was shown by the ride app at that drop (HHApp `IN_RIDE_NOTIFICATIONS`,
  `EXTENSION-io.github.madooroy.pinionkaroo`), and both fields went to Searching.
- Walking out of range dropped the link and it did not come back (expected: no advertising after a drop). After a
  power cycle the gearbox advertised with the same address and the saved-address scan picked it up.
- Shifts to easier gears are notified one by one, about 0.2 s apart. A quick run to harder gears is notified as
  one jump (1 then 5, 5 then 10); with pauses of about 0.5 s each step is notified. Likely cause: the gearbox
  holds upshifts until the cranks are vertical and then makes them at once. Pinion's Smart.Shift manual of
  2022-07-26 (e-bike version, <https://pinion.eu/wp-content/uploads/2023/11/Smart.Shift_Benutzerhandbuch_2022-07-26.pdf>)
  says on p. 12 that a shift is carried out when the cranks reach the vertical position, and p. 11 that the left
  crank carries a sensor magnet. A test at 19:38 bears it out: three quick presses to harder gears gave one jump
  (9 then 12) both with the cranks still (reported once they were turned) and while pedalling; three presses to
  easier gears were immediate and reported one by one with the cranks still, but while pedalling came as 11
  then 9. So harder shifts always wait for the crank, easier ones only while pedalling. That manual does not cover Bluetooth or the sleep timeout. Nothing is lost
  on the Karoo side.
- Read replies are padded to 13 bytes and the padding can hold a stale byte from the previous reply
  (`02 01 01 61 02 07 22 00 ...`); only the declared length is parsed.
- Not yet checked on real hardware: an actual ride.

Pairing mode, per Pinion's app FAQ
(<https://service.pinion.eu/en/support/solutions/articles/36000505964-pinion-smart-shift-app>): hold the shift button
without the Pinion logo for 3 s; on non-e-bikes, disconnect and reconnect the Smart.Shift battery; on e-bikes, switch
the bike on. It lasts two minutes (measured with nRF Connect after a power-on, 2026-10-14: about 2 min). While advertising the gearbox sends a packet every 25-60 ms; the advertisement's
manufacturer data (company ID 0xFFFF) holds the serial number as ASCII digits, which could identify a gearbox without
connecting to it. Besides the Pinion service the gearbox exposes a standard Cycling Speed and Cadence service, a
Nordic "Buttonless DFU Without Bonds" service (never write to it), and four unknown Pinion characteristics `03`,
`05`, `07` (with CCCD) and `12`. A gear notification was once seen as two bytes, `01 34`; only byte 0 is used. The CSC Measurement (0x2A5B) notifies
about once per crank revolution while pedalling, e.g. `03 00000000 FFFF 1100 D43F`: flags 0x03 claim wheel and crank
data, but the wheel part is always 0 revolutions with event time 0xFFFF; the crank part is a real cumulative count and
last event time (1/1024 s), giving 50-65 rpm for a hand-turned crank (2026-10-14). So the gearbox could serve as a
plain BLE cadence sensor, but paired as a speed sensor it would report nothing useful. One quirk: the last
notification after the cranks stopped counted one more revolution with an event time one tick earlier than the
previous one.

## BLE protocol

Source: Tim Angus's reverse engineering, <https://github.com/timangus/garmin-connectiq-pinion-barrel>
(the library behind <https://github.com/timangus/pinion-garmin-settings>), blog post
<https://timang.us/2025-08-09-pinion-garmin-settings/>. Mirrored in `PinionProtocol.kt`.

| What | UUID | Properties |
|---|---|---|
| Service (also in the advertisement) | `00000000-33d2-4f94-9ee4-9312b3660005` | |
| Current gear | `00000001-33d2-4f94-9ee4-9312b3660005` | notify |
| Request | `0000000d-33d2-4f94-9ee4-9312b3660005` | write with response |
| Response | `0000000e-33d2-4f94-9ee4-9312b3660005` | indicate |

- **Gear notification:** byte 0 = gear, 1-based. Sent on change only, so the start value is read.
- **Requests** are CANopen SDO style. Read: `01 <len> <a0 a1 a2>`. Reply (indication on Response):
  `02 <len> <a0 a1 a2> <value, little endian>`. First byte `00` = error. (Write is `03`, ack `04 ff <addr>`; unused here.)
- **Parameters** (address bytes as sent, length): current gear `01 61 02` (1), battery `64 61 01` (2, hundredths of a
  percent), number of gears `00 25 00` (1), serial `18 10 04` (4).
- Connection sequence: connect, discover, enable indications on Response, enable notifications on Gear, then read.
  One GATT operation at a time.
- **Advertising:** only after the rear shift button is held for 3 seconds. It is not bonding. When a
  connection drops the gearbox stops advertising, so reconnecting always needs the button again;
  the client just scans until that happens and the field shows "Hold shift 3s".

Open questions to settle on the real hardware:
- Whether one read alone keeps the gearbox awake. Test: connect, set the status screen button to "battery only",
  leave the bike untouched 12 minutes (the idle timeout is 8); if still connected, switch to "gear only" for
  another 12. Set it back to "gear + battery" afterwards.
- Whether the reads keep going with the Karoo on its own battery. Every test so far had it on USB power from
  the laptop, where Android does not go into its power-saving state. Unplugged, with the screen off (ride paused
  for lunch, say), Android 8 can hold back the extension's timers; if the reads stop for 8 minutes the gearbox
  switches off and the link is lost. Test: Karoo off USB, ride page, let the screen go off, 15 minutes. If it
  drops, keeping the reads going needs a wake lock or a foreground service.
- Does the gearbox advertise again when it wakes from its 8 minute sleep (shift or crank turn), and does the Karoo
  then reconnect from the ride page by itself? The code keeps scanning from the ride page, so it should. If so, the
  disconnect alert's "hold the rear shift button" wording may need changing.
- Does it advertise again after a clean disconnect (FORGET GEARBOX on the status screen)?
- Whether the connection holds on a real ride (it held 122 minutes stationary).
- Whether the gearbox ever demands an encrypted link (would show as GATT status 5, 8 or 15 in the log).
- 2.4 GHz interference caused dropouts for the Garmin author (an ANT+ heart rate strap).

## To do

- After the extension itself closes the link (no users for 2 minutes), the ride field kept showing the last gear
  (seen 2026-10-11 20:02, right after a reinstall; cause not established).
- Scanning uses `SCAN_MODE_LOW_LATENCY` for as long as the gearbox is not found, which on a ride after a dropped
  link is the rest of the ride. Drop to a low-power scan after the first couple of minutes.
- Then version 0.1.1 and a release: the published 0.1.0 APK has neither the 60 s gear read nor the Bluetooth
  re-request.

## Layout

- `PinionProtocol.kt`: UUIDs, request encoding, reply decoding. Pure Kotlin, covered by `PinionProtocolTest`.
- `PinionBleClient.kt`: scan, connect, subscribe, poll the gear every 60 s and the battery every 5 minutes (the gear poll corrects a lost notification and logs "Gear poll"; any read also resets the gearbox's 8 minute idle switch-off; the status screen has a test button that switches the poll between gear + battery, gear only, battery only and off, kept in prefs), retry forever. Publishes `StateFlow<PinionState>`. Process-wide singleton. Runs only while the active ride profile contains a Pinion field (`ActiveRideProfile` event, handled in the service) or the status activity is open, and for 2 minutes after. Field streams are no use for this: Karoo keeps them running whatever profile is open.
- `extension/PinionExtensionService.kt`: the `KarooExtension` service; owns the client's lifetime, requests the BT radio from Karoo, raises an in-ride alert on disconnect.
- `extension/PinionDataType.kt`: one class for both fields; it only streams a value, Karoo draws the field.
- `MainActivity.kt`: requests Bluetooth/location runtime permissions, shows live status, "Forget gearbox".
- `res/xml/extension_info.xml`: extension id `pinion`, type ids `gear` and `battery`. These must match the Kotlin constants.

Karoo facts that shaped the code (the layout ones found on a Karoo 2, 2026-10-07):
- The manifest action is `io.hammerhead.karooext.KAROO_EXTENSION`.
- Karoo 2 is Android 8 (legacy BT permissions + location), Karoo 3 is Android 13.
- **Why the fields are plain numeric ones.** A graphical (RemoteViews) gear field was built first, to show
  "Hold shift 3s" in the field, and dropped the same day because it could not be made to look native:
  - Karoo's field font is not available to RemoteViews (and `sans-serif-condensed` has no effect on the Karoo 2).
  - With `showHeader = true` the view is pushed down by the header height but keeps the full field height
    (`ViewConfig.viewSize`), so its bottom is clipped; under a two-line title the number does not fit.
  - RemoteViews on Android 8 rejects `TextView.setGravity`; an exception while applying the view kills the field
    until the ride page is reopened, and is logged under tag `HHApp` ("Extensions: Error in view"), not `Pinion`.
  - `ViewEmitter.updateView` drops updates faster than about 1 Hz.
  With `graphical="false"` and no `startView`, Karoo draws title and number itself and shows "Searching..."
  while disconnected. The in-ride alert carries the "hold the button" instruction instead.
- Karoo wraps a field title longer than about 9-10 characters in a half-width field ("MAX POWER" fits,
  "PINION GEAR" wraps). It also caches a field's title in the profile: a changed `displayName` only shows after
  the field is removed and added again. Switching `graphical` took effect without re-adding.
- In the profile editor Karoo draws its own sample number (42) for a numeric field; the extension is not asked.
  Only graphical fields are told about preview mode (`ViewConfig.preview`). Feature request for a sample value:
  <https://github.com/hammerheadnav/karoo-ext/issues/93> (Karoo 2 build 1.613.2351.12, karoo-ext 1.1.9).
- Karoo has no Bluetooth switch for the rider; it turns the radio on and off itself. On 2026-10-11 it was off
  (`SensorServiceModel(... bluetoothEnabled=false ...)` in the log) while the extension was installed and the
  status screen open, and a running scan then reports nothing and no error. The scan now watches
  `adapter.isEnabled`, and the service repeats `RequestBluetooth` while the client reports BLUETOOTH_OFF.
- Screenshots for checking layout: `adb exec-out screencap -p > shot.png`.

## Build and install

1. Install Android Studio (bundles the JDK and SDK) and open this folder.
2. karoo-ext is served from GitHub Packages, which needs a token even for public packages. Create a classic
   personal access token with the `read:packages` scope and put it in `%USERPROFILE%\.gradle\gradle.properties`:
   ```
   gpr.user=<github username>
   gpr.key=<token>
   ```
3. Build and test: `.\gradlew.bat testDebugUnitTest assembleDebug`
4. Install on the Karoo (developer options + USB debugging on):
   `adb install -r app\build\outputs\apk\debug\app-debug.apk`
5. On the Karoo open **Pinion Smart.Shift** once and grant the permission, add the **Pinion Gear** field to a
   ride profile, then hold the rear shift button for 3 seconds.

Debug log: `adb logcat -s Pinion` (every Timber line uses that one tag; requests and replies are logged as hex).
