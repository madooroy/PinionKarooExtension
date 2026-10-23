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
  19:37 and it answered every poll. (The Smart.Shift user manual gives the idle time, see below.)
- **It is the 60 s reads that keep it awake, and its idle timeout is 8 minutes.** With the poll set to off
  (status screen button) and the bike untouched: connected 20:16:14, last read 20:16:16.7, link lost at
  20:24:20.7 with GATT status 8 (supervision timeout: the gearbox goes silent, it does not send a disconnect).
  That is 480 s after the last read plus a few seconds of link timeout. Karoo's Bluetooth stayed on throughout. Confirmed
  independently with nRF Connect on an iPhone (2026-10-14): last activity a crank turn at 22:14:30.9, iOS reported
  "connection timed out unexpectedly" at 22:22:31.8, about 481 s later. So crank movement resets the timer too, the
  timeout does not depend on the central, and the gearbox sends nothing on any subscribed characteristic (`01`, `03`,
  `05`, `07`, CSC) before it goes silent.
  So an open link alone does not keep the gearbox awake, and without the poll a rider who does not shift for
  8 minutes loses the connection and has to put the gearbox in pairing mode again. Keep the poll on.
  **A battery read alone is enough** (2026-10-21, `pinion-2026-10-21.log`): poll set to battery only, connected
  05:57:27, one battery read every 60 s and nothing else (no shift, no crank turn, Karoo screen saver on, Karoo on
  USB), still connected and answering at 06:10:28, 13 minutes on. So any read resets the idle timer, and Tim Angus's
  data field, which reads the battery every 60 s, keeps the gearbox awake too: its 90 s drops have another cause.
  **A gear read alone is enough too** (same log): last shift 06:12:13, poll set to gear only at 06:12:55, one gear
  read every 60 s and nothing else, still connected and answering at 06:24:30, 10 min 16 s after the last shift.
- **After a clean disconnect the gearbox advertises again** (2026-10-14): connected about 22:36 after a power-on,
  FORGET GEARBOX at about 22:41 (GATT closed by the Karoo, well after the 2 minute pairing window), and the Karoo
  found it and reconnected straight away, without a button hold. An iPhone with nRF Connect saw the same on
  2026-10-13. So the gearbox stops advertising only after links it loses (out of range, its own idle sleep), not
  after ones the other side closes. How long it advertises after a clean disconnect: under 3 minutes, likely the
  usual 2 (2026-10-23, replacement gearbox: clean close 14:06:14, Karoo scanning again from 14:09:02 after a
  restart, not found until a 3 s hold, found 14:09:56).
- **The gearbox can stop answering requests while staying connectable** (2026-10-14, 22:47, Karoo off USB, after
  several clean disconnects and reconnects that evening): every connection succeeded up to the subscriptions, the
  write of the first read request was acknowledged, but no reply came, and the extension dropped the link after 5 s
  and reconnected, round and round every 7-8 s. A missing reply is now logged and skipped instead of ending the link. A power cycle of the gearbox cleared it: at 22:50
  it connected and answered all three reads within 0.4 s.
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
- **Rollers session, 2026-10-16** (ride log file `pinion-2026-10-16.log`): Karoo 2 on its own battery, ride recorded,
  ANT+ lights, Varia and pedals, BLE heart rate strap. One link from 12:44:41 to 13:45:59, ended only by a clean close
  after switching to a profile without Pinion fields. Every shift notified, every gear poll matched. A 17 minute
  lunch-style pause (13:00:47-13:17:36: ride paused, screen gone dark, bike untouched) did not interrupt the 60 s gear
  and 5 minute battery reads, so Android does not hold back the timers while the Karoo sleeps. On waking the screen,
  gear and battery were showing while the speed sensor, heart rate strap and pedals had disconnected and came back
  after a few crank turns.
- **Replacement gearbox, 2026-10-23** (`pinion-2026-10-23.log`, new BLE address): FORGET GEARBOX at 13:28:18, a 3 s
  hold of the rear button, found and connected at 13:28:32 (14 s), saved; 12 gears, battery 87.90 %, shifts notified
  as before. Pairing by the documented button hold works on this box.
- Not yet checked on real hardware: an outdoor ride.

Pairing mode, per Pinion's app FAQ
(<https://service.pinion.eu/en/support/solutions/articles/36000505964-pinion-smart-shift-app>): hold the shift button
without the Pinion logo for 3 s; on non-e-bikes, disconnect and reconnect the Smart.Shift battery; on e-bikes, switch
the bike on. It lasts two minutes (measured with nRF Connect after a power-on, 2026-10-14: about 2 min). While advertising the gearbox sends a packet every 25-60 ms; the advertisement's
manufacturer data (company ID 0xFFFF) holds the serial number as ASCII digits, which could identify a gearbox without
connecting to it. Besides the Pinion service the gearbox exposes a standard Cycling Speed and Cadence service, a
Nordic "Buttonless DFU Without Bonds" service (never write to it), and four unknown Pinion characteristics `03`,
`05`, `07` (with CCCD) and `12`. Gear notifications are two bytes, gear then `34`: the second byte was 0x34 for every gear 1-8 in a minute
of shifting (2026-10-14), so it does not depend on the gear; only byte 0 is used. In that minute, with all three
subscribed, `03` and `07` sent nothing, and `05` sent once, `00 00 08 20`, about 0.4 s after a burst of six
downshifts in 0.6 s (8 to 3); meaning unknown. Later, with no shifting, `07` sent `04 09 56 9C 08` once (cause unknown), and
`03` sent `04` about 12 s after a 3 s pairing hold (slow beeps) made while connected: possibly a state report. A
second hold in the same connection (slow beeps, then the quick beeps the box gives when a connection is made) sent nothing on `03`, so not a fixed response. The CSC Measurement (0x2A5B) notifies
about once per crank revolution while pedalling, e.g. `03 00000000 FFFF 1100 D43F`: flags 0x03 claim wheel and crank
data, but the wheel part is always 0 revolutions with event time 0xFFFF; the crank part is a real cumulative count and
last event time (1/1024 s), giving 50-65 rpm for a hand-turned crank (2026-10-14). So the gearbox could serve as a
plain BLE cadence sensor, but paired as a speed sensor it would report nothing useful. CSC Feature reads `0300` (wheel and
crank data "supported") and Sensor Location reads `05` (left crank, matching the crank magnet). Quirks, each seen
twice: when the cranks stop, a last notification counts one more revolution with an event time one tick *earlier*
than the previous one; and about half a minute after stopping the count resets, `03 00000000 FFFF 0000 FFFF`
(0 revolutions, time 0xFFFF), so a later session starts counting from 1 again. A consumer that treats the count as
monotonic would see a backwards jump there.

Pinion Workshop Manual 2024 ("Classic", 136 pp., supplied by the author; not in the repo), Smart.Shift parts
pp. 63-66 and 125-127: pairing is a 3-10 s hold of the bottom shift lever, a sound on release, LED flashing blue,
which agrees with the above. Calibration is both buttons 5-10 s, release, both again within 3 s (LED yellow, then
green). A new box needs its B2B settings (mounting angle, sprockets, wheel size, power supply) entered in Pinion's
app before calibrating; the app can archive them from the old box and load them into a replacement. Error codes
are only described for CAN (e-bike display); below 6 V supply shifting is impaired (code 8C-02). Nothing on
Bluetooth, the idle timeout or the BLE protocol.

Pinion Smart.Shift user manual for standard (non-e-) bikes (multilingual, English pp. 25-46; supplied by the
author as a browser printout without a text layer; not in the repo):
- p. 38: Smart.Shift sleeps while on battery power and wakes on the first shift; "once eight minutes have passed
  without any shifting, the sleep mode is activated". Matches the 480 s measured above (reads and crank turns also
  reset the timer, which the manual does not say).
- p. 40: after power-on and one shift to wake it, it is in pairing mode for 2 minutes; after that, a 3 s hold of the
  rear trigger button (without the Pinion logo) starts 2 minutes more, LED flashing blue. Whether "activation"
  includes waking from sleep is not said: if it does, the gearbox would advertise again after its 8 minute sleep at
  the first shift, which is the open question below. p. 42: reversing the trigger mapping does not move the
  Bluetooth button.
- p. 33: battery good for over 20,000 shifts and 100 hours of riding. When low, the box LED flashes after each shift,
  yellow at 20 % or less, red at 10 % or less, and at 5 % or less it also beeps; low charge can reduce shifting
  performance. The low-battery alert uses the same 20 %, 10 % and 5 % levels.
- p. 43, LED: green OK, yellow non-critical error, red critical error, white manual shift (during shifts), light blue
  PRE.SELECT active (during shifts), dark blue pairing; health status shown for 3 s on start or wake-up.
- p. 36: a beep when the lowest or highest gear is already engaged; shifts are made with the cranks vertical, at low
  cadence immediately. START.SELECT and PRE.SELECT need the optional speed sensor.
- p. 45: firmware updates and error messages are in the app ("Notifications").

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
- **Advertising:** after power-on and after a 3 s hold of the rear shift button (2 minutes each), after a clean
  disconnect, and (replacement gearbox, 2026-10-23) after a shift wakes it from its 8 minute sleep. It is not
  bonding. Out of range it stops; the client just scans (fast for 2 minutes, then low power) and the fields show
  Searching. The disconnect alert reads "Pinion offline / Shift once, or hold rear 3 s" for 30 s.

Open questions to settle on the real hardware:
- Does the gearbox advertise again when it wakes from its 8 minute sleep? **First answer: no** (2026-10-21,
  `pinion-2026-10-21.log`, the author's first gearbox): poll off after a read at 06:25:30, link lost 06:33:34 with
  status 8 (8 min 4 s), Karoo scanning from 06:33:35 on the status screen; one shift (not a hold) about 06:35 woke
  the gearbox but the Karoo did not find it in the next minute or two. So the disconnect alert's "hold the rear
  shift button" wording stays. Repeat once on the replacement gearbox, ideally with nRF Connect scanning.
  **Replacement gearbox: yes** (2026-10-23, `pinion-2026-10-23.log`, ride screen open, ride paused): poll off, last
  shift 13:31:02, link lost 13:39:06 with status 8 (8 min 4 s); the rider shifted once (no hold) and the Karoo found
  the gearbox again at 13:39:52 and was connected at 13:39:53, gear 3 (the waking shift was carried out, gear 5 to
  3). So on this box waking it with a shift is enough; the first box's "no" may have been part of its pairing fault.
  Repeated 13:44:12-13:52:16 with the ride running: drop at 8 min 4 s, the disconnect alert showed (title cut to
  "PINION DISCONNEC...", detail to "Hold the rear / shift button for 3 ..."), one shift, back at 13:52:48, 32 s after
  the drop. From 0.1.3 the alert reads "Pinion offline / Shift once, or hold rear 3 s" and stays 30 s instead of 10
  (the rider nearly missed the 10 s one). A sticky alert (`autoDismissMs = null`) was considered and left out:
  karoo-ext has no effect to take an alert down when the gearbox comes back, and it would cover the top of the page.
- Whether anything differs outdoors (it held 122 minutes stationary and an hour on rollers).
- Whether the gearbox ever demands an encrypted link (would show as GATT status 5, 8 or 15 in the log). Not seen in
  any session up to 2026-10-23 (every status 8 so far was the gearbox's own sleep or a deliberate hold).
- 2.4 GHz interference caused dropouts for the Garmin author (an ANT+ heart rate strap). Not seen on the Karoo with
  ANT+ lights, radar, pedals and BLE heart rate connected.
- Disconnect alert while the ride is paused: **raised but not seen** (2026-10-23, ride paused, poll off): drop at
  14:29:28, and the ride app logged the alert (`IN_RIDE_NOTIFICATIONS`, `EXTENSION-io.github.madooroy.pinionkaroo`,
  eventType TIMEOUT, 14:29:28.397 to 14:29:58.398, the full 30 s) just as for the alert seen with the ride running
  (14:18:02.843 to 14:18:32.853); the rider, watching the pause screen, did not see it. So the pause view hides it.
  Minor: polling keeps the gearbox awake through a pause, and on resuming the fields show Searching. After a shift
  in low-power search (from 14:31:29) the gearbox was back at 14:32:21.

## To do

- After the extension itself closes the link (no users for 2 minutes), the ride field kept showing the last gear
  (seen 2026-10-11 20:02, right after a reinstall; cause not established).
- Scanning: `SCAN_MODE_LOW_LATENCY` for the first 2 minutes of a search (the pairing window), then
  `SCAN_MODE_LOW_POWER` (logged "scanning in low-power mode"), so a rider who rides on after a dropped link does not
  drain the Karoo. Each new search (after a failed or ended connection) starts fast again. Checked 2026-10-23 with
  0.1.3: drop 14:18:02, low-power from 14:20:03, the rider shifted once about a minute later and the gearbox was
  found at 14:21:21 and connected at 14:21:23. The new disconnect alert ("PINION OFFLINE / Shift once, or hold /
  rear 3 s") fitted without cutting and stayed up about 30 s.
- Repository renamed 2026-10-23 to `madooroy/PinionKarooExtension` (GitHub redirects the old name); 0.1.3's
  manifest links use the new name. The APK keeps the file name `pinion-karoo-extension-<version>.apk`.
- Version 0.1.3 (update manifest, low-power scan, disconnect wording; built and checked on the Karoo 2026-10-23, release pending): versionCode 4. The version is in three places, `versionName`/
  `versionCode` in `app/build.gradle.kts`, the `KarooExtension` constructor in `PinionExtensionService`, and
  `app/manifest.json`; `ReleaseVersionTest` fails unless they agree (and unless `latestApkUrl` names the release's
  APK). Release APKs are signed with the debug key of the author's laptop, so they must be built there for an
  update over an earlier install to work.
- **Release steps:** bump the version in all three places (also `releaseNotes` in `app/manifest.json`), run
  `testDebugUnitTest assembleRelease`, then a GitHub release tagged `v<version>` with two assets:
  `pinion-karoo-extension-<version>.apk` (the release APK renamed) and `manifest.json` (copied from `app/`). The app
  points Karoo at `releases/latest/download/manifest.json` (meta-data `io.hammerhead.karooext.MANIFEST_URL`), so the
  release must be marked Latest. Releasing from this cloud session is blocked (GitHub API refuses releases and tag
  pushes), so the author creates it on the website.
- Ideas, not planned: a "Pinion Cadence" field from the gearbox's CSC Measurement over the existing link (no
  second connection needed; mind the count reset quirk above), for riders without a cadence sensor. Pairing the
  gearbox as a Karoo cadence sensor instead is untested, and would take the gearbox's single connection away from
  the extension (and its CSC claims a wheel speed that is always 0).
- Karoo 3 can install from a phone (Companion app, share a GitHub APK link; Hammerhead says "New Karoo only", so
  not the Karoo 2) and then offers updates from the manifest. Untested by the author.

## Layout

- `PinionProtocol.kt`: UUIDs, request encoding, reply decoding. Pure Kotlin, covered by `PinionProtocolTest`.
- `LowBatteryWarning.kt`: when to raise the low-battery alert. Pure Kotlin, covered by `LowBatteryWarningTest`.
- `PinionBleClient.kt`: scan, connect, subscribe, poll the gear every 60 s and the battery every 5 minutes (the gear poll corrects a lost notification and logs "Gear poll"; any read also resets the gearbox's 8 minute idle switch-off; the status screen has a test button that switches the poll between gear + battery, gear only, battery only and off, kept in prefs), retry forever. Publishes `StateFlow<PinionState>`. Process-wide singleton. Runs only while the active ride profile contains a Pinion field (`ActiveRideProfile` event, handled in the service) or the status activity is open, and for 2 minutes after. Field streams are no use for this: Karoo keeps them running whatever profile is open.
- `extension/PinionExtensionService.kt`: the `KarooExtension` service; owns the client's lifetime, requests the BT radio from Karoo, raises an in-ride alert on disconnect, and a low-battery alert once per connection at 20 % or less and again at 10 % and at 5 % or less (so the battery field can be left off a crowded page: the map page takes only four fields, 2x2, and they cannot be made half width).
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
- An `InRideAlert` is only shown while the ride screen is open; one dispatched before that is dropped without
  any error (2026-10-20: the gearbox connected on the home screen, the alert went out at once and never showed;
  the ride screen, and with it the field streams, started 12 s later). The `ActiveRideProfile` event already
  comes while the rider scrolls through profiles on the home screen. So the low-battery alert waits until a
  Pinion field stream is running. Checked 2026-10-20 with a temporary 95 % threshold: shown on the ride screen. An alert's detail
  shows about two lines ("87% left. Charge the Smart.Shift b..." was cut), and Karoo's numeric field cuts the
  value off rather than rounding (86.54 shows as 86), so the alert does the same. No "Stop ... stream" has been seen in the logs yet, so whether Karoo stops the
  streams when the ride screen is closed is unknown.
- **After a reinstall Karoo may not bind the extension again** (2026-10-23 14:00, second `adb install` of 0.1.3 in
  five minutes): the process came back only for the status screen ("Process started" with no "Extension service
  created" after it), so the status screen connected to the gearbox by itself while the ride page fields stayed on
  Searching. The crash buffer held only Android Studio's own `installer` helper (SIGBUS), nothing from the extension.
  A Karoo restart fixes it. The two clients cannot both hold the gearbox, which takes one connection.
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
Every line also goes to `/sdcard/Android/data/io.github.madooroy.pinionkaroo/files/logs/pinion-YYYY-MM-DD.log`
(`FileLogTree.kt`: one file per day, capped at 2 MB, kept 14 days); fetch with
`adb pull /sdcard/Android/data/io.github.madooroy.pinionkaroo/files/logs`.
