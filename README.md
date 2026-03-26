# Timer Bunyi

A 25-minute countdown timer for Android with a gradually increasing alarm that bypasses silent mode.

## Features

- **25-minute countdown** — large, easy-to-read display built with Jetpack Compose
- **Screen wake at 1 minute** — turns on the screen and shows the UI over the lock screen automatically
- **Alarm bypasses silent/DND** — uses the ALARM audio channel so the sound rings even when the phone is silenced
- **Gradual volume** — alarm starts silent and increases to full volume over 60 seconds (0→100% in 3-second steps)
- **Stop button** — cancels the timer and alarm at any time

## Requirements

- Android 10 (API 29) or higher

## Building

Open the project in Android Studio, or from the command line:

```bash
./gradlew assembleDebug
```

A signed release APK is built automatically via GitHub Actions on every push and published as a [GitHub Release](../../releases).

## Permissions

| Permission | Reason |
|---|---|
| `WAKE_LOCK` | Wake and keep the screen on when the alarm fires |
| `FOREGROUND_SERVICE` | Keep the countdown running while the screen is off |
| `USE_FULL_SCREEN_INTENT` | Show the UI over the lock screen at the 1-minute mark |
| `POST_NOTIFICATIONS` | Show the persistent countdown notification (Android 13+) |

## How It Works

A foreground `TimerService` manages the countdown using `CountDownTimer`. At 1 minute remaining it:

1. Acquires a `SCREEN_BRIGHT_WAKE_LOCK` + `ACQUIRE_CAUSES_WAKEUP` to turn on the display
2. Fires a full-screen notification intent that brings `MainActivity` in front of the lock screen
3. Starts `MediaPlayer` with the system default alarm ringtone, routed to `AudioAttributes.USAGE_ALARM`
4. Ramps volume from 0 → 1.0 in steps of 0.05 every 3 seconds (20 steps × 3 s = 60 s)

The ALARM audio channel bypasses Do Not Disturb and silent mode.
