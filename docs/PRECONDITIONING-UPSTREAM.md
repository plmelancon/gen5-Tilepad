# Preconditioning upstream integration

Compared Tilepad's original backend with
[paytondevs/gen5w-preconditioning](https://github.com/paytondevs/gen5w-preconditioning),
pinned at [`0e366bdaee27`](https://github.com/paytondevs/gen5w-preconditioning/commit/0e366bdaee2768131e00eba1a5333e961ffd879b).
After replacing only the Java package name, Tilepad's original
`PreconditioningProfile`, `OemVehicleClient`, and `PreconditioningController`
exactly match upstream's initial commit
[`c97fafad688e`](https://github.com/paytondevs/gen5w-preconditioning/commit/c97fafad688e).
Tilepad has independent launcher, trigger, popup and service adaptations, so this
integration ports the useful backend changes instead of merging the application wholesale.

## Imported changes

- Exact firmware profiles for twelve combinations across six head-unit families (Tilepad adds
  three, below). EV6 US 241120 and NEEV US 260903 came later, from upstream's
  [`432e410d`](https://github.com/paytondevs/gen5w-preconditioning/commit/432e410d) and
  [`6ea690f9`](https://github.com/paytondevs/gen5w-preconditioning/commit/6ea690f9) (2026-11-12): their
  software strings and hashes only, with Tilepad's own tests.
  Android API 19, device code, `ro.build.product` (not `Build.PRODUCT` or the
  displayed `ro.build.product2`), and the matching pair of framework hashes must
  all agree. Hashes from different profiles cannot be mixed. HEV/BCM Binder
  descriptors and reply sizes remain checked. The existing Start metadata,
  single Start pulse, maintenance and cancellation sequences are unchanged.
- Per-profile unconfirmed-activation deadlines: five minutes on US EV6 units;
  two minutes on IONIQ 5, IONIQ 6 and Korean EV6. A fresh Active indication at
  the deadline wins. Already-active sessions have no activation timeout. Missing
  or reserved feedback cannot keep an unconfirmed request alive indefinitely.
  Timeout sends cancellation once and retains ownership until cancellation
  succeeds and a fresh Off reading arrives. Failed cancellation remains retryable.
- A one-shot minimum battery-temperature precheck, using the existing cluster
  interface. It verifies the service descriptor, registers a temporary callback,
  requests the array and removes the callback even after an uncertain failure.
  It validates the eight-element reply and minimum-temperature field. There is
  at most one in-flight helper, with an overall 1.8-second deadline, so a hung
  Binder call cannot accumulate threads or indefinitely block the controller.
  Late or unavailable temperature falls back to the existing manual request.
- A ten-second warm advisory, without recording ownership or sending Start.
  Thresholds follow upstream: 20°C for US EV6 and IONIQ 6; 21°C for IONIQ 5 and
  Korean EV6. They are advisories, not guarantees about BMS calibration. The
  popup offers an explicit **Start anyway**; an ordinary duplicate Start or
  another tile tap cannot accept it. READY and Off are rechecked after telemetry
  and before an override. The original one-tap Start and active-session Stop
  actions otherwise remain in place.
- Service reattachment calls the existing bind routine from both `onBind` and
  `onRebind`, and returns true from `onUnbind`. Reopening during a live session
  resumes the existing status binding; an idle unbound service still stops.

Key upstream changes reviewed include `d8bb05a`, `afe9704`, `fb605cf`, `47fc940`,
`6cde3f5`, `3d6b2bb`, `28ec556`, `86ebbb1`, `a7a927c`, `9f7aca6`, `a8303b0`,
`5010bec`, and `edb8f13`. Latest profile constants and matching logic come from
pinned HEAD. The minimal bounded temperature reader comes from `3d6b2bb`, before
scheduler-specific outside-temperature telemetry was added.

## Supported preconditioning identities

| Head unit | Software suffixes |
| --- | --- |
| IONIQ 5 NEEV23 US | 240822*, 260206, 260507, 260903 |
| IONIQ 5 NEEV US | 240822, 260507, 260903 |
| IONIQ 5 NEEV CA | 260206† |
| IONIQ 5 NEEV KR | 260902 |
| EV6 CVEV US | 241120, 251120, 260507 |
| EV6 CVEV CA | 260206‡ |
| EV6 CVEV KR | 260902 |
| IONIQ 6 CEEV US | 260507, 260903§ |

These are exact software/hash combinations, not support for every model year or
firmware with the same suffix, with one rule of Tilepad's own (below, "Rebuilds of inspected code"). †NEEV CA 260206 is also Tilepad's own addition: a compatibility log
(issue 21, a Canadian 2024 IONIQ 5, device `wp_daudioplus_neev_ca`, software
`NEEV.CAN.0000.V015.011.260206`) showed its `automotive.odex` and `automotive-service.odex`
byte-identical to NEEV23 US 260206's, and no difference in the HEV, BCM or cluster interfaces Tilepad
calls across the images compared. It reuses that hash pair and hasn't been tried on the car.
‡EV6 CVEV CA 260206 is Tilepad's own addition too: a compatibility log (issue 22, a Canadian 2024
EV6, device `wp_daudioplus_cvev_ca`, software `CVEV.CAN.0000.V015.011.260206`) showed its
`automotive.odex` and `automotive-service.odex` identical to EV6 US 260507's in every byte except
the source jar's recorded build time and the ODEX header checksum that covers it
(`tools/compat/odex_same.py`): the same embedded DEX, optimized data and dependency SHA-1s. Since
the files aren't byte-identical it has its own hash pair, and as the same code it keeps the US EV6's
five-minute timeout and 20°C threshold. It hasn't been tried on the car. *NEEV23 US 240822 is Tilepad's own addition, not an upstream
profile: a compatibility log (issue 12) showed its `automotive.odex` and `automotive-service.odex`
byte-identical to upstream's NEEV US 240822, so it reuses that hash pair, as upstream's NEEV US
260507 reuses NEEV23 US's. It hasn't been tried on that car. §CEEV US 260903 is Tilepad's own addition too: a compatibility log
(issue 55, a US 2025 IONIQ 6 SEL, device `wp_daudioplus_ceev_us`, software
`CEEV.USA.0000.V016.010.260903`; issue 47 is the same image) showed its `automotive.odex` and
`automotive-service.odex` identical to NEEV23 US 260903's apart from the build time, as with EV6 CVEV
CA. It has its own hash pair and, on the IONIQ 6's own device, the IONIQ 6's two minutes and 20°C. It
hasn't been tried on the car. Evidence and field-testing limits are in the
[pinned compatibility notes](https://github.com/paytondevs/gen5w-preconditioning/blob/0e366bdaee2768131e00eba1a5333e961ffd879b/COMPATIBILITY.md)
and [temperature/timeout notes](https://github.com/paytondevs/gen5w-preconditioning/blob/0e366bdaee2768131e00eba1a5333e961ffd879b/TEMPERATURE-AND-TIMEOUT.md).
The captured `/system/build.prop` contains generic ECOHMC defaults; the live
runtime properties are authoritative. A read-only check on the existing car
confirmed `wp_daudioplus_neev23_us` and
`NEEV23.USA.0000.V015.012.260507`, matching the original framework hash pair.
Compatibility of other Tilepad tweaks is independent; their gates were not broadened.

## Rebuilds of inspected code

Hyundai and Kia rebuild the same release more than once (the IONIQ 6's own image, a Canadian
image), and each rebuild changes the vehicle files' hashes without changing their code. So, besides
the exact pairs, Tilepad accepts a supported device (the table's devices, API 19) whose
`automotive.odex` and `automotive-service.odex` have the same code hashes as an inspected pair. A
code hash (`OdexCode.java`, and `tools/compat/odex_same.py --code` on a computer) is the file's
SHA-256 with two fields zeroed: the ODEX header checksum and the source jar's recorded modification
time, the only fields a rebuild of identical sources changes. The embedded DEX, the optimized data
and every dependency's SHA-1 still count, and a file whose checksum doesn't hold has no code hash.
`PreconditioningProfile.INSPECTED_CODE` lists the four distinct code pairs among the profiles whose
files Tilepad has: NEEV23 US 260507 (which NEEV23 US 260206, NEEV CA 260206 and CEEV US 260507
share), NEEV US 240822, NEEV23 US 260903 (shared by CEEV US 260903) and EV6 US 260507 (shared by
CVEV CA 260206). The software string isn't checked for these, and the device still sets the timeout
and threshold. A different model, or different code, still needs a profile.

## Service and UI boundaries

All control ownership stays in Tilepad's existing `PreconditioningService`, its
serialized worker, private ownership journal and ongoing notification. The
cluster helper is a bounded thread, not another Android service or permanent
subscription. No service, receiver, boot action, alarm or permission was added.
The root star-hold service still only opens Tilepad; moving preconditioning into
that process would couple vehicle ownership to the launcher trigger unnecessarily.

Not imported: scheduled Start, the redesigned upstream app and tabs, installer
changes, TUNE remapping, or the scheduler's unverified outside-temperature scaling.
Upstream's graph and temperature history were not imported either; Tilepad has its
own (below).

## Tilepad's own additions

Written for Tilepad, not taken from upstream. No command sequence, transaction code or
controller decision changed, and every earlier test passes unmodified.

- **Whole battery reply.** `ClusterBatteryTemperatureReader.readInfo()` returns the eight-integer
  reply as `BatteryTemperature.Info` (minimum, and raw charge, outside sensor and conditioning
  status). The same three cluster transactions, 37/109/38. `stuck()` reports a helper still
  blocked after its deadline. `VehiclePort.readBatteryInfo()` is behind the firmware gate.
- **Battery monitor.** A second thread in the service reads the battery every 10 seconds while
  Tilepad is on screen (the tile shows it), every 20 seconds while Keep warm is armed, and never
  otherwise. Five failed reads in a row stop it. The Start pre-check uses a reading younger than
  10 seconds, else its own read. `TemperatureLog` keeps 360 readings in memory.
- **Raw values and Tilepad's reading.** The snapshot carries the raw READY and setting values. The
  monitor shows the raw values as read, including status id 115, whose meaning isn't established,
  and one sentence labelled as Tilepad's reading. `EventTrail` keeps the last 50 events and writes
  each to logcat under `tilepad-precon`, which the support log keeps.
- **Keep warm.** `KeepWarm` repeats ordinary one-off sessions for 15 minutes to 6 hours (15-minute
  steps to 2 hours, then 30-minute steps). It is
  armed only from the popup, lives in memory only, and holds no request between sessions. An
  automatic Start goes through the same path as the button and needs, on the same tick: nothing
  requested, READY, the setting on, the indicator off, no warm notice, fewer automatic Starts than
  the limit (max(4, minutes / 30)), 8 minutes since the last Start, 5 minutes since the last session ended, and 3 minutes of
  readings at least 2°C under the threshold with no gap over 45 seconds. It disarms on a gap of
  more than 10 seconds between ticks, when the time is up, on an error, when the car's own values
  say it is not ready or the setting is off, after a minute of unknown status, after 5 minutes
  without a reading, and after two sessions in a row that never became Active. Disarming by itself
  sends nothing to the car: a session already running, Keep warm's own included, ends by the
  ordinary rules, and a plain notification says why Keep warm ended (not when the car was simply
  switched off). Only the driver's Stop cancels. A Stop that arrives
  while a Start is being prepared prevents that Start. The spacing idea follows the Continuous mode of the
  WiCAN preconditioning firmware (GPL-3.0); nothing is held between sessions here.
- **Tile.** A tap opens the popup and never sends anything by itself. During a session the tile
  shows the battery's start, current and target temperatures; the target is the profile's
  expected heating threshold, not a value read from the car.
- **Emulator.** The debug build's simulated car is scriptable: `tools/preconditioning/SIMULATOR.md`.

Not verified on a car: everything in this section. It has host tests and emulator runs only.

## Verification

- `sh tools/tweaks/test-host.sh`: pure Java regression/profile tests, installer
  checks, and the two Android/Binder fixture suites below. Compatibility tests
  cover every profile and reject wrong devices, software, APIs and mixed hashes.
- `python3 tools/preconditioning/test_temperature_binder.py`: ten scenarios for
  valid/malformed responses, request/registration/removal failures, dead/wrong
  services, a hung transaction, late results and overlapping reads.
- `python3 tools/preconditioning/test_service_rebind.py`: six scenarios running
  the actual service with Android fixtures: Start, unbind, rebind, idle shutdown,
  explicit warm consent and eligibility loss during temperature collection.
- `sh tools/build_apk.sh --sdk-only`: release compilation against Android API 19,
  DEX 035 generation and verification of the pinned v1 signing certificate.
- Standalone Android lint runs as part of `sh tools/build_apk.sh`.

The debug-only emulator adapter accepts `debug.tilepad.battery_min_c` for testing
warm consent. It is ignored outside the emulator and excluded from release code.
