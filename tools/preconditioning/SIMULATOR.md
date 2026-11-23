# Simulated car (debug build, emulator only)

On an emulator (`ro.hardware` is `ranchu` or `goldfish`) the debug build replaces the car with
`SimulatedCar`. It runs the real `PreconditioningProfile` sequences (start, maintain, cancel) and
answers from a small model of the battery. It is driven with `adb shell setprop debug.tilepad.NAME VALUE`.
Properties are read fresh on every call; an empty value (`setprop debug.tilepad.NAME ""`) means the default.
Watch it with `adb logcat -s tilepad-sim` (every write, start pulse, and each model state change, once).

## Properties

| Property (`debug.tilepad.`) | Default | Meaning |
|---|---|---|
| `ready` | 1 | READY flag in the status reply |
| `enabled` | 2 | enabled field in the status reply |
| `failure` | -1 | raw failure field in the status reply |
| `indicator` | auto | `auto` or unset = from the model; any integer (0, 1, 7...) forces the value; the model keeps running |
| `refuse` | 0 | 1 = the car never starts heating after a Start pulse |
| `activate_s` | 8 | seconds from the Start pulse until the indicator turns 1 |
| `target_c` | 20 | the car stops heating by itself (indicator 0, request stays sent) at this temperature |
| `session_max_s` | 0 | the car stops heating by itself after this many seconds of heating (0 = no limit) |
| `battery_min_c` | unset | exact battery temperature (also resets the model to it); unset = the model runs |
| `start_c` | 12 | model: starting temperature, read at the car's first use |
| `heat_s` | 20 | model: seconds per +1 C while heating |
| `cool_s` | 30 | model: seconds per -1 C while not heating |
| `ambient_c` | 5 | model: cooling stops here |
| `temp_fail` | 0 | 1 = battery reads return null |
| `temp_stuck` | 0 | 1 = battery reads return null and the reader is reported stuck |
| `read_fail` | 0 | 1 = status read throws "Simulated: the car didn't answer a status request." |
| `hang_ms` | 0 | the status read first sleeps this many ms |
| `write_fail` | 0 | 62, 65 or 66 = writes of that transaction throw after being logged |
| `charge_raw` | 150 | raw charge field of the battery reply |
| `outside_raw` | 90 | raw outside field of the battery reply |
| `cond_raw` | auto | raw conditioning field; unset = 1 while heating, else 0 |
| `device` | `wp_daudioplus_cvev_ca` | profile for the activation timeout and warm threshold (Canadian EV6: 5 min, 20 C) |

Notes: heating starts only if, when `activate_s` has elapsed, ready = 1, enabled = 2, refuse != 1 and
the battery is below `target_c`. The check happens at the first call after that moment, with the
property values of that call. To restart the model cold, set `battery_min_c` to a value, then clear it.

## Scenarios

Reset first (clears everything the scenarios set):

```
for p in ready enabled failure indicator refuse activate_s target_c session_max_s battery_min_c start_c heat_s cool_s ambient_c temp_fail temp_stuck read_fail hang_ms write_fail charge_raw outside_raw cond_raw device; do adb shell setprop debug.tilepad.$p ""; done
```

1. Normal heat to target (starts at 12 C, stops at 16 C after about 40 s of heating)
```
adb shell setprop debug.tilepad.target_c 16
adb shell setprop debug.tilepad.heat_s 10
```
   Tap Start. Indicator 1 after 8 s; the car stops itself at 16 C; the app should show it ended.

2. The car refuses (the app should cancel when the activation timeout passes: 5 minutes on the default device)
```
adb shell setprop debug.tilepad.refuse 1
```
   Tap Start. For a shorter wait: `adb shell setprop debug.tilepad.device wp_daudioplus_neev_ca` (2 minutes, 21 C).

3. Warm battery (the app should skip the request or ask first)
```
adb shell setprop debug.tilepad.battery_min_c 25
```

4. Lose READY mid-session
```
adb shell setprop debug.tilepad.target_c 30
adb shell setprop debug.tilepad.heat_s 5
```
   Tap Start, wait for heating (indicator 1), then:
```
adb shell setprop debug.tilepad.ready 0
```
   Restore with `adb shell setprop debug.tilepad.ready 1`.

5. Unknown indicator
```
adb shell setprop debug.tilepad.indicator 7
```
   (Use `0` or `1` to force a known value; `auto` returns to the model.)

6. Temperature reader failing
```
adb shell setprop debug.tilepad.temp_fail 1
```
   Tap Start: it must proceed without a temperature.

7. Stuck reader
```
adb shell setprop debug.tilepad.temp_stuck 1
```
   Tap Start: the display shows the reader as stuck; the request must not wait on it.

8. Blocked status read, 30 s
```
adb shell setprop debug.tilepad.hang_ms 30000
```
   Every status read blocks for 30 s. Clear with `adb shell setprop debug.tilepad.hang_ms ""`.
