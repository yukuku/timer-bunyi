package yuku.timerbunyi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class TimerService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val BROADCAST_TICK = "yuku.timerbunyi.TICK"
        const val EXTRA_REMAINING_MS = "remaining_ms"
        const val EXTRA_IS_RUNNING = "is_running"

        const val TIMER_CHANNEL_ID = "timer_channel"
        const val ALARM_CHANNEL_ID = "alarm_channel"
        const val FOREGROUND_NOTIF_ID = 1
        const val ALARM_NOTIF_ID = 2

        const val TIMER_DURATION_MS = 25 * 60 * 1000L
        const val ALARM_START_MS = 60 * 1000L
        // Volume increases from 0f to 1f in steps of 0.05f every 3 seconds = 20 steps * 3s = 60s
        const val VOLUME_STEP = 0.05f
        const val VOLUME_INTERVAL_MS = 3000L
    }

    private var countDownTimer: CountDownTimer? = null
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentVolume = 0f
    private var alarmStarted = false

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
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }

    private fun startTimer() {
        if (countDownTimer != null) return

        startForeground(FOREGROUND_NOTIF_ID, buildTimerNotification(TIMER_DURATION_MS))

        countDownTimer = object : CountDownTimer(TIMER_DURATION_MS, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                updateNotification(millisUntilFinished)
                broadcastTick(millisUntilFinished, running = true)

                if (millisUntilFinished <= ALARM_START_MS && !alarmStarted) {
                    alarmStarted = true
                    wakeScreen()
                    showAlarmNotification()
                    startAlarmSound()
                }
            }

            override fun onFinish() {
                broadcastTick(0, running = false)
                // Alarm keeps playing until user presses stop
            }
        }.start()
    }

    private fun stopTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
        alarmStarted = false

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

        broadcastTick(TIMER_DURATION_MS, running = false)
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
        wakeLock?.acquire(70_000L)
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

        // Update foreground notification to alarm one so it stays visible
        startForeground(FOREGROUND_NOTIF_ID, notification)
    }

    private fun buildTimerNotification(remainingMs: Long): Notification {
        val contentIntent = Intent(this, MainActivity::class.java)
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
            .setContentText(formatTime(remainingMs))
            .setContentIntent(contentPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(remainingMs: Long) {
        if (alarmStarted) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_NOTIF_ID, buildTimerNotification(remainingMs))
    }

    private fun broadcastTick(remainingMs: Long, running: Boolean) {
        val intent = Intent(BROADCAST_TICK).apply {
            putExtra(EXTRA_REMAINING_MS, remainingMs)
            putExtra(EXTRA_IS_RUNNING, running)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Timer channel: silent countdown notification
        val timerChannel = NotificationChannel(
            TIMER_CHANNEL_ID,
            getString(R.string.timer_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(timerChannel)

        // Alarm channel: uses USAGE_ALARM so it bypasses silent/DND
        val alarmAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val alarmChannel = NotificationChannel(
            ALARM_CHANNEL_ID,
            getString(R.string.alarm_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                alarmAudioAttributes
            )
            enableVibration(true)
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
