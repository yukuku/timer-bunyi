package yuku.timerbunyi

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class TimerService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_WAKE = "WAKE"
        const val ACTION_ALARM_FIRE = "ALARM_FIRE"

        const val TIMER_CHANNEL_ID = "timer_channel"
        const val ALARM_CHANNEL_ID = "alarm_channel"
        const val FOREGROUND_NOTIF_ID = 1
        const val ALARM_NOTIF_ID = 2

        const val VOLUME_STEP = 0.05f
        const val VOLUME_INTERVAL_MS = 3000L

        private const val PREF_NAME = "timer_state"
        private const val PREF_END_TIME = "end_time_ms"
        private const val PREF_RUNNING = "is_running"

        private const val RC_WAKE = 100
        private const val RC_ALARM = 101

        @Volatile
        var endTimeMs: Long = 0L
            private set

        @Volatile
        var currentlyRunning: Boolean = false
            private set

        @Volatile
        var alarmActive: Boolean = false
            private set

        fun loadState(context: Context) {
            val prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            endTimeMs = prefs.getLong(PREF_END_TIME, 0L)
            val wasRunning = prefs.getBoolean(PREF_RUNNING, false)
            if (wasRunning && endTimeMs > System.currentTimeMillis()) {
                currentlyRunning = true
            } else {
                currentlyRunning = false
                endTimeMs = 0L
            }
        }
    }

    private var timerDurationMs = AppSettings().timerDurationMs
    private var alarmStartMs = AppSettings().wakeLeadMs

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentVolume = 0f
    private var screenWoken = false

    private val volumeHandler = Handler(Looper.getMainLooper())
    private val volumeRunnable = object : Runnable {
        override fun run() {
            currentVolume = (currentVolume + VOLUME_STEP).coerceAtMost(1f)
            mediaPlayer?.setVolume(currentVolume, currentVolume)
            if (currentVolume < 1f) {
                volumeHandler.postDelayed(this, VOLUME_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer()
            ACTION_STOP -> stopTimer()
            ACTION_WAKE -> handleWake()
            ACTION_ALARM_FIRE -> handleAlarmFire()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }

    private fun startTimer() {
        if (currentlyRunning || alarmActive) return

        val settings = SettingsStore.load(this)
        timerDurationMs = settings.timerDurationMs
        alarmStartMs = settings.wakeLeadMs

        endTimeMs = System.currentTimeMillis() + timerDurationMs
        currentlyRunning = true
        alarmActive = false
        saveState()

        startForeground(FOREGROUND_NOTIF_ID, buildTimerNotification())
        scheduleAlarms()
    }

    private fun scheduleAlarms() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager

        val showIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule wake screen
        val wakeTimeMs = endTimeMs - alarmStartMs
        if (wakeTimeMs > System.currentTimeMillis()) {
            val wakeIntent = Intent(this, TimerService::class.java).apply { action = ACTION_WAKE }
            val wakePi = PendingIntent.getForegroundService(
                this, RC_WAKE, wakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(wakeTimeMs, showIntent), wakePi)
        } else {
            handleWake()
        }

        // Schedule alarm fire
        val alarmIntent = Intent(this, TimerService::class.java).apply { action = ACTION_ALARM_FIRE }
        val alarmPi = PendingIntent.getForegroundService(
            this, RC_ALARM, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(endTimeMs, showIntent), alarmPi)
    }

    private fun cancelAlarms() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager

        val wakeIntent = Intent(this, TimerService::class.java).apply { action = ACTION_WAKE }
        val wakePi = PendingIntent.getForegroundService(
            this, RC_WAKE, wakeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(wakePi)

        val alarmIntent = Intent(this, TimerService::class.java).apply { action = ACTION_ALARM_FIRE }
        val alarmPi = PendingIntent.getForegroundService(
            this, RC_ALARM, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(alarmPi)
    }

    private fun handleWake() {
        if (screenWoken) return
        screenWoken = true
        wakeScreen()
        showAlarmNotification()
    }

    private fun handleAlarmFire() {
        if (!screenWoken) handleWake()
        currentlyRunning = false
        alarmActive = true
        saveState()
        startAlarmSound()
    }

    private fun stopTimer() {
        cancelAlarms()
        screenWoken = false
        currentlyRunning = false
        alarmActive = false
        endTimeMs = 0L
        saveState()

        volumeHandler.removeCallbacks(volumeRunnable)

        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ALARM_NOTIF_ID)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun wakeScreen() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            @Suppress("DEPRECATION")
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "timerbunyi:alarm"
        )
        wakeLock?.acquire(alarmStartMs + 10 * 60 * 1000L)
    }

    private fun startAlarmSound() {
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        currentVolume = 0f
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    .build()
            )
            setDataSource(applicationContext, alarmUri)
            isLooping = true
            setVolume(0f, 0f)
            prepare()
            start()
        }

        volumeHandler.postDelayed(volumeRunnable, VOLUME_INTERVAL_MS)
    }

    private fun showAlarmNotification() {
        val fullScreenIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ALARM_RINGING, true)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TimerService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.alarm_notification_title))
            .setContentText(getString(R.string.alarm_notification_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .addAction(0, "Stop", stopPi)
            .setAutoCancel(false)
            .setOngoing(true)
            .build()

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ALARM_NOTIF_ID, notification)

        startForeground(FOREGROUND_NOTIF_ID, notification)
    }

    private fun buildTimerNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TimerService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TIMER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(contentPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setWhen(endTimeMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun saveState() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
            .putLong(PREF_END_TIME, endTimeMs)
            .putBoolean(PREF_RUNNING, currentlyRunning)
            .apply()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val timerChannel = NotificationChannel(
            TIMER_CHANNEL_ID,
            getString(R.string.timer_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(timerChannel)

        val alarmChannel = NotificationChannel(
            ALARM_CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(alarmChannel)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}
