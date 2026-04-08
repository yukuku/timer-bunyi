# Timer Bunyi

A configurable countdown timer for Android with a gradually increasing alarm that bypasses silent mode.

## Features

- **Configurable countdown** — set any duration from 15 seconds to 60 minutes via the settings screen
- **Large, easy-to-read display** — monospace clock face built with Jetpack Compose on a dark background
- **Configurable wake lead time** — screen wakes before the alarm (5 seconds to 5 minutes, default 1 minute)
- **Alarm bypasses silent/DND** — uses the ALARM audio channel so the sound rings even when the phone is silenced
- **Gradual volume** — alarm starts silent and increases to full volume over 60 seconds (0→100% in 3-second steps)
- **Reliable timing** — uses `AlarmManager.setAlarmClock()` with absolute wall-clock time instead of tick-based countdown; timer state survives activity recreation and process death
- **Single-instance design** — only one timer session runs at a time; notification and launcher always return to the same activity

## Requirements

- Android 10 (API 29) or higher

## Building

Open the project in Android Studio, or from the command line:

```bash
./gradlew assembleDebug
```

## Permissions

| Permission | Reason |
|---|---|
| `WAKE_LOCK` | Wake and keep the screen on when the alarm fires |
| `FOREGROUND_SERVICE` | Keep the notification visible and play the alarm sound |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Required foreground service type for alarm audio |
| `USE_FULL_SCREEN_INTENT` | Show the UI over the lock screen at the wake lead time |
| `POST_NOTIFICATIONS` | Show the persistent countdown notification (Android 13+) |

## How It Works

When the timer starts, `TimerService` records an absolute end time (`endTimeMs`) and schedules two `AlarmManager.setAlarmClock()` events:

1. **Wake alarm** — fires at `endTimeMs - wakeLeadMs`. Acquires a `SCREEN_BRIGHT_WAKE_LOCK` to turn on the display and shows a full-screen notification that brings `MainActivity` in front of the lock screen.
2. **Fire alarm** — fires at `endTimeMs`. Starts `MediaPlayer` with the system default alarm ringtone routed to `AudioAttributes.USAGE_ALARM`, and ramps volume from 0 → 1.0 in steps of 0.05 every 3 seconds (20 steps × 3 s = 60 s).

The notification countdown display uses the system chronometer (`setChronometerCountDown`), so no per-second service tick is needed. The ALARM audio channel bypasses Do Not Disturb and silent mode.
