# GNSS Link — LAN GPS Sharing / Mock Location

Phone A acquires GPS → sends to Phone B over LAN UDP → Phone B injects it as a **system mock location**.
After that, B opens Amap / Baidu / Google Maps and thinks it is at A's location, ready for navigation.
Ideal for tablets, head units, dev boards without GPS modules, or any location-related testing.

Redesigned based on the open-source project [wxip/gnsslink](https://github.com/wxip/gnsslink).

```
Phone A (has GPS)            Wi-Fi / Hotspot            Phone B (no GPS needed)
┌──────────────────┐                                ┌──────────────────┐
│ LocationManager  │  ── UDP 7007 ──►  ┌──────────┐  ◄──  │ MockLocationEngine│
│  gps / network   │                   │ Router/AP│       │  addTestProvider  │
│  1Hz sampling    │  ◄── ACK ───────  └──────────┘  ──►  │  setTestProvider… │
└──────────────────┘                                └──────────────────┘
```

<p align="right"><a href="./README.md">🇨🇳 中文版</a></p>

## Directory Structure

```
gpslink/                          Android project (Kotlin, open directly in Android Studio)
└── app/src/main/java/top/wxip/gpslink/
    ├── MainActivity.kt           UI, permissions, role switching, satellite panel
    ├── core/
    │   ├── Protocol.kt           Protocol constants, Bluetooth UUID, rate presets
    │   ├── LocationFrame.kt      Location frame + JSON codec + Location conversion (with extras)
    │   ├── SatWire.kt            Satellite snapshot codec
    │   ├── SatelliteMonitor.kt   GnssStatus subscription
    │   ├── Gnss.kt               Constellation / band / color display rules
    │   ├── RateProbe.kt          Sampling cap measurement
    │   ├── Geo.kt                Spherical distance
    │   ├── NetInfo.kt            Local IPv4
    │   ├── LinkBus.kt            Service ↔ UI state bus
    │   └── Prefs.kt              Parameter persistence
    ├── mock/MockLocationEngine.kt  addTestProvider / setTestProviderLocation
    ├── transport/
    │   ├── BtSender.kt           Bluetooth sender (RFCOMM client)
    │   └── BtReceiver.kt         Bluetooth receiver (RFCOMM server)
    ├── ui/SatelliteBarsView.kt   GPS Test–style signal bars (custom View)
    └── service/
        ├── SendService.kt          Sender foreground service
        └── ReceiveService.kt       Receiver foreground service
docs/技术方案.md                  Principles, coordinate systems, permissions, performance, compliance
README.md
```

## Differences from the Reference Project

The reference project `gnsslink` is only 145 lines of Java — the idea is solid, but it's essentially a demo. This version does the following:

| Issue | Reference | This Project |
|---|---|---|
| Screen-off / background | Logic in Activity, stops when process frozen | Two foreground services + persistent notification, runs long-term |
| Pairing | Manually enter B's LAN IP | UDP broadcast auto-discovery, with optional manual IP fallback |
| Authentication | None — any device on the subnet can feed coordinates | Room code check, mismatched packets dropped |
| Mock location permission | Catches `SecurityException` and shows a Toast | Proactively checks via AppOps + guides to Developer options |
| Injected provider | Only replaces `gps` | `gps` + `network`, compatible with head units/tablets that only read `network` |
| Link quality | Invisible | Real-time FPS, round-trip latency, packet loss |
| Disconnection | Dead is dead | Heartbeat + auto rediscovery + auto-recovery after service is killed |
| Crashes | `throw new RuntimeException(e)` | Full try/catch chain, graceful degradation to logging |
| Language / Build | Java, AGP 8.3.0-alpha07, Gradle 8.4-rc-2 | Kotlin, AGP 8.3.2, Gradle 8.4 stable |

## Quick Start

### 1. Build

Open the `gpslink/` directory directly in Android Studio, or use the command line:

```bash
cd gpslink
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` contains `sdk.dir` pointing to your local Android SDK; update it when switching machines.
Requirements: JDK 17+, compileSdk 34, minSdk 26.

> If it hangs on `Downloading https://services.gradle.org/distributions/gradle-8.4-bin.zip`,
> that domain may be unreachable. Replace `distributionUrl` in `gpslink/gradle/wrapper/gradle-wrapper.properties`
> with the Tencent mirror:
> `https\://mirrors.cloud.tencent.com/gradle/gradle-8.4-bin.zip`
>
> This project has already been compiled successfully; the artifact is at
> `gpslink/app/build/outputs/apk/debug/app-debug.apk` (5.7 MB), ready for `adb install`.

### 2. Authorize Mock Location on Phone B (Receiver) First

This step **must be done manually — no app can bypass it with code**:

1. Settings → About Phone → tap "Build number" 7 times to enable Developer options;
2. Settings → System → Developer options → **Select mock location app** → choose `GNSS Link`;
3. Return to the app, tap "Start Receiving", and grant location and notification permissions.

If step 2 is skipped, tapping "Start Receiving" will show a blue prompt card and take you directly to Developer options.

### 3. Usage

1. Both phones connect to the same Wi-Fi (or A opens a hotspot and B joins it);
2. Set the same room code (default `8888`), keep port `7007`;
3. B taps "Start Receiving" → status shows "Receiver Running · Waiting…";
4. A taps "Start Sharing" → within seconds both sides display the other's IP;
5. B opens any map app — the location is A's location.

Leave "Receiver IP" on A empty for auto-discovery. If the router has AP isolation / client isolation enabled,
broadcasts will be blocked — in that case manually enter B's IP (visible in B's app, or check the router admin panel).

## How to Confirm B is Using A's Location

Once the receiver is running, a "Location Verification" card appears on the UI:

```
Injected    33.592432, 114.078952
System Read gps · mock=true · 33.592432, 114.078952
Conclusion  System is serving mock location to all apps (deviation 0.0 m)
```

- **Injected**: Coordinates received from A;
- **System Read**: B asks the system again "where are you now" (`LocationManager.getLastKnownLocation()`),
  getting the **same value** that Amap / Baidu / Google Maps will receive;
- **mock=true**: The system marks this location as mock; third-party apps' `Location.isMock()` will also be true;
- **Deviation**: Should be close to 0. Large deviation means injection is not working.

**Why just looking at "coordinates" isn't proof**: when both phones are placed together, coordinates are naturally similar; and older versions don't clear the UI after stopping, leaving stale values. You must look at "System Read + mock flag".

Two additional independent verification methods:

**Physical verification (most intuitive)**: Walk 20 m away with A — B's coordinates should follow, while B itself hasn't moved.
Conversely, only take B away — coordinates stay the same. That confirms it.

**Command line**:

```bash
adb shell dumpsys location | grep -i mock
```

Seeing `mock` confirms a test provider is active in the system.

## Location Data Fields

Fields shown in the "Location Data" card:

| Field | Meaning | Source |
|---|---|---|
| Coordinates | WGS-84 latitude/longitude | `getLatitude()` / `getLongitude()`, no GCJ-02 offset |
| Accuracy | Horizontal accuracy (m), lower is better | `getAccuracy()` |
| Altitude | Elevation (m) | `getAltitude()` |
| Speed | m/s, with km/h in parentheses | `getSpeed()` |
| Bearing | 0~360°, with cardinal direction, 0=North 90=East | `getBearing()` |
| Source Age | How long since this frame was generated by GPS | See below |

**Why source age isn't just timestamp subtraction**: clock differences of a few seconds between phones would give wrong results. So the sender includes "how old this frame already is" (`now - location.time`) in the packet; the receiver adds "time since received" to get the true age. Only monotonic clocks are used — desynchronized clocks don't matter.

Over 3 seconds is flagged "Data stale", usually meaning the sender lost GPS lock or the link is stuck.

## Sampling Rate vs. Injection Rate

Two different things — don't confuse them:

- **Sampling rate (sender)**: How often A reads GPS. Options: 1 / 2 / 5 / 10 Hz.
- **Injection rate (receiver)**: How often B writes location to the system. Options: 1 / 5 / 10 Hz, default **10 Hz**.

**Is 1 Hz enough?** Yes. Real-device GPS itself is 1 Hz, and Amap / Baidu / Google Maps are designed for 1 Hz:
at 120 km/h each frame covers 33 m; navigation software extrapolates using `speed` + `bearing`, so it won't stutter.

**Then why does mock location look jerky?** Real 1 Hz goes through FusedLocationProvider and IMU fusion for smoothing,
but injected test providers bypass that path, causing positions to "jump" every second. This is a **smoothing problem, not a sampling rate problem**.

**The right fix isn't raising sampling to 10 Hz** (many phone GPS chips only output 1 Hz, so it won't help),
but **receiver-side interpolation**: B receives 1 Hz frames and linearly extrapolates between them using `speed` + `bearing`,
writing at 10 Hz to the system. Input is still 1 Hz, but the system sees continuous movement.

Three layers of protection for extrapolation:

1. **Speed < 0.5 m/s → no extrapolation** — when standing still, the point stays put, no drift;
2. **Max extrapolation 3 seconds** — on disconnection, won't keep flying in the last direction forever;
3. **Frame-change residual 400 ms linear decay** — difference between predicted and actual points is gradually consumed, no "jump" every second;
   residuals over 100 m (A teleported) are snapped directly, no smoothing.

**Cost**: At 10 Hz injection, about 20 binder calls per second (gps + network, 10 each each), power consumption is negligible.
Extrapolation is inherently estimation — a few meters of error in turns, corrected by the real frame the next second.

## Detecting Your Phone's Sampling Cap

Tap **Detect Device Cap** in "Rate Settings": the app requests GPS updates at 50 ms intervals, then measures the median gap between consecutive `Location.elapsedRealtimeNanos` callbacks and converts to frequency.

- Uses `elapsedRealtimeNanos` instead of callback arrival time — the main thread may be blocked by other tasks, inflating arrival times;
- Requires outdoors or by a window. Indoors with no satellite lock will show "too few samples";
- This is an **actual measurement**, affected by environment, not a hardware spec.

If the measured cap is 1 Hz, that means the GPS chip only outputs 1 Hz — setting sampling to 5/10 Hz won't help.
In that case, rely on receiver-side interpolation for smoothness.

## Bluetooth Transmission

Switch between **LAN / Bluetooth**. Bluetooth uses classic RFCOMM, not BLE:

- 1~10 Hz location frames are ~250 bytes each; RFCOMM bandwidth (~100 KB/s) is more than enough;
- No GATT service / characteristic / MTU negotiation needed — half the code;
- Reconnect after disconnect is just `connect()` again.

**Frame format**: newline-delimited JSON (NDJSON). RFCOMM is a byte stream, unlike UDP which has natural message boundaries,
so each frame is suffixed with `\n` and the receiver splits with `readLine()`.

**Usage**:

1. Pair both phones in **system Bluetooth settings** first (this app only lists already-paired devices, no scanning,
   avoiding `BLUETOOTH_SCAN` and its associated location permission entanglements);
2. Sender switches to "Bluetooth" → "Select Device" → choose the receiver;
3. Receiver switches to "Bluetooth" → "Start Receiving";
4. Sender taps "Start Sharing" — it will auto-connect.

**Notes**:

- Effective range ~10 m (line of sight), walls attenuate significantly;
- Bandwidth is not a bottleneck: 1 Hz ≈ 250 B/s, 10 Hz ≈ 2.5 KB/s;
- Android 12+ requires `BLUETOOTH_CONNECT` runtime permission, requested only when switching to Bluetooth mode.

## Satellite Signals

GPS Test–style display: each row is one satellite, left side shows constellation label (colored by constellation), satellite ID, C/N0 signal bar,
right side shows value and band.

Data from `LocationManager.registerGnssStatusCallback()`'s `GnssStatus`:

| Display | API | Description |
|---|---|---|
| Constellation | `getConstellationType` | GPS / BeiDou / GLONASS / Galileo / QZSS / SBAS / NavIC |
| ID | `getSvid` | Displayed as `G12` `C19` `R07` etc. |
| Signal Strength | `getCn0DbHz` | Carrier-to-noise ratio dB-Hz, 40+ is strong; bar normalized by /50 |
| Carrier Frequency | `getCarrierFrequencyHz` | **Only on Android 11 (API 30)+**, older versions show `—` |
| Elevation / Azimuth | `getElevationDegrees` / `getAzimuthDegrees` | Collected, sky-plot not yet drawn |
| Used in Fix | `usedInFix` | Satellites used in fix are listed first, darker text color |

Sorting: used-in-fix first, then descending by C/N0 — same as GPS Test.

Band inferred from carrier frequency: 1575.42 MHz → L1, 1176.45 MHz → L5, 1561.098 MHz → B1I,
1207.14 MHz → B2I, 1602 MHz → G1. The legend at the bottom lists currently active bands and exact frequencies.

### Single / Dual / Triple / Quad Band

Counts actual distinct carrier frequencies received (deduplicated at 0.1 MHz granularity): 1 → single, 2 → dual,
3 → triple, 4 → quad.

Note this is the literal "how many distinct frequency points are in use" definition, not vendor marketing. For example,
simultaneously receiving GPS L1 (1575.42) and BeiDou B1I (1561.10) counts as dual-band; GPS L1 + L5 also counts as dual-band.
The legend lists the exact frequency points so you can judge yourself. Devices that don't report carrier frequency
(Android 11 and below) show "Band unknown".

### Receiver Can Also See Sender's Satellites

The sender sends a `SAT` packet every **2 seconds** (not every location frame, since satellite states change slowly),
containing all satellites' IDs, constellations, C/N0, frequencies, and fix status. The receiver renders the same signal bars,
with the title showing "From 192.168.1.17".

The packet compresses satellites into arrays `[svid, constellation, cn0, frequencyMHz, usedInFix]`,
~30 satellites ≈ 800 bytes, well under UDP's 1400-byte single-packet limit; Bluetooth streaming handles it fine too.

## About Letting Amap / Baidu Read Satellite Count

Here's the honest truth to avoid disappointment:

- **Satellite lists cannot be faked.** `GnssStatus` (used for sky-plots and satellite lists) comes straight from the GNSS HAL layer,
  mock location cannot inject into it. Any app reading `GnssStatus` gets the device's real (or stopped) GNSS, never A's data.
- **The only thing that can be faked is `Location.getExtras()`.** AOSP's GNSS provider stuffs `satellites` / `meanCn0` / `maxCn0`
  into extras, and this project fills those three fields (using sender-side values) during injection — this is the industry-standard convention.
- **But Amap / Baidu Maps don't display satellite count in their UI.** During navigation they only show signal strength hints,
  not "12 satellites". So even if extras work, you won't see it on their interface.
- **Scenarios where it does show**: GPS Test / GPS Status and similar tools, or your own code reading
  `location.extras.getInt("satellites")`.

To verify extras are actually attached:

```bash
adb shell dumpsys location | grep -i "satellites"
```

## App Icon

Adaptive icon, pure vector, no bitmap resources:

- **Background**: Blue gradient `#4A86F7 → #1B4FC4`
- **Foreground**: White location pin + two upward-right signal arcs, meaning "location + broadcast sharing"
- The pin's center hole uses `fillType="evenOdd"` to cut through, revealing the background color
- All graphics are within the 66dp safe zone of the 108×108 canvas; no OEM icon mask will clip it

To change the icon, just edit two files: `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`.

## Background Keep-Alive

Navigation often requires the phone screen to be off; keep-alive is critical for this type of tool. This project uses four layers:

| Measure | Purpose |
|---|---|
| **Foreground service + persistent notification** | Process won't be frozen by the system; notification content shows peer address and FPS in real time, also the service's "alive credential" |
| **PARTIAL_WAKE_LOCK** | Foreground services don't prevent CPU suspension. Once the screen is off and CPU sleeps, location callbacks and the 10 Hz injection timer stop, so both services hold their own lock |
| **Battery optimization whitelist** | The "Battery Optimization" row in the UI is tappable and goes directly to system settings. This is most effective on domestic ROMs |
| **START_STICKY + parameter persistence** | After being killed, the system restarts the service; on restart intent is null, parameters are restored from SharedPreferences (port, room code, sampling rate, transport mode) |

Additionally, `onTaskRemoved` only logs, doesn't actively stop — swiping away from recent tasks keeps the service running.

**Notification permission**: Android 13+ requires `POST_NOTIFICATIONS`. Even if denied, the foreground service itself still works,
just no notification bar icon, and the system's keep-alive priority is reduced. Granting is recommended.

**Domestic ROMs also need manual whitelisting**: MIUI / ColorOS / EMUI / OriginOS etc. besides battery optimization,
also require allowing this app in "Auto-start management", "Background pop-up", and "Lock screen display", or it may still be cleaned up.

## Known Stability Trade-offs

These are known and intentionally accepted issues:

- **Max extrapolation 1.5 seconds.** If we kept extrapolating in the last direction after disconnect, a car at 120 km/h could drift 100 m in 3 seconds.
  Better to stop than let the blue dot fly off the road.
- **Test provider may remain after ROM force-kill.** Next time the app opens it auto-cleans
  (`MockLocationEngine.release()` doesn't rely on internal state, attempts removal on both providers).
- **`Location.isMock()` is always true.** Banking apps, attendance check-ins, ride-hailing driver apps etc. actively reject it — this is by system design, cannot be bypassed.
- **10 Hz injection is binder-call-intensive.** Receiver does ~20 calls/sec (gps + network, 10 each),
  power consumption is negligible in practice, but higher than 1 Hz.
- **FusedLocationProvider interaction with test provider depends on OEM implementation.** Works fine on most devices,
  some ROMs may still mix in sensor estimation, causing slight lag in turns.

## Troubleshooting Connection Issues

The UI now shows **local IP, peer online status, packets sent/received**. Follow this order to pinpoint exactly where it's broken:

| Symptom | Meaning | Action |
|---|---|---|
| Receiver "Received 0 packets" | Network layer completely blocked | See #1 below |
| Receiver "Received N packets", sender "Received 0" | Forward path OK, return path blocked | See #2 |
| Both sides have packets, but "Peer: No response" | One-way packet loss / keepalive timeout | See #3 |
| Coordinates correct, but map doesn't move | Injection not working | See #4 |

**1. Not a single packet received**

- Are both phones on the **same** Wi-Fi? Compare the first three octets of "Local IP" on both sides (e.g. `192.168.1.x`).
- Does the router have **AP isolation / client isolation / guest network** enabled? This is the most common cause — internet works but devices can't see each other.
- Is mobile data also on? With both Wi-Fi and cellular active, broadcasts may go out the wrong interface. Turn off mobile data and retry.
- **Fallback**: The "Local IP" shown on the receiver is the address to use. Enter it in the sender's "Receiver IP" field to skip auto-discovery.

**2. Receiver receives, sender gets no ACK**

The receiver's reply uses source port 7007; some routers/firewalls block unsolicited reverse-direction packets. Usually still AP isolation.
If only occasional loss, it doesn't affect usage — newer versions no longer disconnect over a few dropped packets.

**3. Peer shows "No response"**

Usually one side is being throttled by system power saving. Add both phones to the "Battery optimization whitelist";
domestic ROMs also need to allow this app in "Auto-start management" and "Background running".

**4. Coordinates correct but map doesn't move**

- Confirm B has selected this app as "Mock location app" in Developer options;
- `adb shell dumpsys location | grep -i mock` to check if a mock provider exists in the system;
- Some apps (banking, attendance, ride-hailing driver apps) detect `Location.isMock()` and actively ignore it — not a bug.

## Protocol

Single-packet JSON, UDP, default port 7007, max 1400 bytes (no fragmentation, no retransmission).

| Type | Direction | Purpose |
|---|---|---|
| `WHO` | A → broadcast | Discover receiver |
| `HERE` | B → A | Response, with device name |
| `LOC` | A → B | One location frame |
| `ACK` | B → A | Ack, echoes sender's timestamp for RTT calculation |
| `BEAT` | B → A | Active heartbeat every 2 s, prevents false disconnect from packet loss |
| `SAT` | A → B | Satellite snapshot every 2 s (ID / constellation / C/N0 / frequency / fix status) |
| `BYE` | B → A | Reject (room code mismatch, etc.) |

In **Bluetooth mode** there is no `WHO` / `HERE` (the connection itself is the pairing), only `LOC` / `ACK` / `BEAT`.
Packet contents are identical, just replacing UDP unicast with RFCOMM byte stream, each frame terminated by `\n`.

```json
{"v":1,"t":"LOC","sid":"8888","seq":128,
 "lat":31.230416,"lon":121.473701,"alt":12.5,
 "acc":4.2,"spd":0.0,"brg":0.0,
 "vacc":3.0,"sacc":0.5,"bacc":10.0,
 "tms":1760702400123}
```

Coordinates are **WGS-84**, passed through as-is, no coordinate system conversion. See `docs/技术方案.md` for rationale.

## Known Limitations

- **Must be on the same LAN.** Cross-WAN requires a relay (WebSocket / MQTT); protocol layer reserves `v` field for future upgrades.
- **Cannot bypass mock location authorization** — this is Android's security design. Rooted devices can use
  `adb shell appops set top.wxip.gpslink android:mock_location allow` to skip manual setup.
- Some apps detect `Location.isMock()` and refuse service (banking, attendance check-in, ride-hailing driver apps, some fitness apps).
- Mock location has no real satellite signal; navigation relying on GNSS-IMU fusion will lag slightly in turns.
- For personal testing and accessibility use only. Location data is sensitive personal information; sharing with others requires consent;
  using it for attendance spoofing,刷单, game cheating etc. may constitute violations or even illegal acts.

## Future Enhancements

- [ ] WebSocket relay, breaking out of LAN (server-side protocol version field already reserved)
- [ ] Multiple receivers within LAN (one-to-many)
- [ ] Track replay: record a trajectory on A, replay it on B along the timeline
- [ ] Dead reckoning: receiver short-term extrapolation by `spd`/`brg` for smoother weak-network experience
- [ ] Optional AES-encrypted room code, preventing same-subnet packet forgery
