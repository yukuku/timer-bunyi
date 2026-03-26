package yuku.timerbunyi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.roundToInt

enum class Screen { TIMER, SETTINGS }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ALARM_RINGING = "alarm_ringing"
    }

    private var settings by mutableStateOf(AppSettings())
    private var remainingMs by mutableLongStateOf(AppSettings().timerDurationMs)
    private var isRunning by mutableStateOf(false)
    private var currentScreen by mutableStateOf(Screen.TIMER)

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            remainingMs = intent?.getLongExtra(TimerService.EXTRA_REMAINING_MS, settings.timerDurationMs)
                ?: settings.timerDurationMs
            isRunning = intent?.getBooleanExtra(TimerService.EXTRA_IS_RUNNING, false) ?: false
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsStore.load(this)
        remainingMs = settings.timerDurationMs

        // Show activity over lock screen and turn screen on
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            when (currentScreen) {
                Screen.TIMER -> TimerScreen(
                    remainingMs = remainingMs,
                    isRunning = isRunning,
                    onStartStop = ::toggleTimer,
                    onOpenSettings = { currentScreen = Screen.SETTINGS }
                )
                Screen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onBack = { newSettings ->
                        settings = newSettings
                        SettingsStore.save(this, newSettings)
                        if (!isRunning) remainingMs = newSettings.timerDurationMs
                        currentScreen = Screen.TIMER
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            tickReceiver,
            IntentFilter(TimerService.BROADCAST_TICK)
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(tickReceiver)
    }

    private fun toggleTimer() {
        val action = if (isRunning) TimerService.ACTION_STOP else TimerService.ACTION_START
        val intent = Intent(this, TimerService::class.java).apply { this.action = action }
        if (!isRunning) {
            startForegroundService(intent)
            isRunning = true
            remainingMs = settings.timerDurationMs
        } else {
            startService(intent)
        }
    }
}

@Composable
fun TimerScreen(
    remainingMs: Long,
    isRunning: Boolean,
    onStartStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = formatTime(remainingMs),
                color = Color.White,
                fontSize = 80.sp,
                fontWeight = FontWeight.Thin,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = onStartStop,
                modifier = Modifier
                    .width(160.dp)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFB00020) else Color(0xFF1B5E20)
                )
            ) {
                Text(
                    text = if (isRunning) "Stop" else "Start",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (!isRunning) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: (AppSettings) -> Unit
) {
    var durationSeconds by remember { mutableIntStateOf(settings.timerDurationSeconds) }
    var wakeLeadSeconds by remember { mutableIntStateOf(settings.wakeLeadSeconds) }

    val maxWakeLead = minOf(AppSettings.MAX_WAKE_LEAD_SECONDS, durationSeconds)
    if (wakeLeadSeconds > maxWakeLead) wakeLeadSeconds = maxWakeLead

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            IconButton(onClick = {
                onBack(AppSettings(timerDurationSeconds = durationSeconds, wakeLeadSeconds = wakeLeadSeconds))
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Settings",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }

        SettingSlider(
            label = "Timer Duration",
            displayValue = formatDuration(durationSeconds),
            value = durationSeconds.toFloat(),
            onValueChange = { durationSeconds = it.roundToInt() },
            valueRange = AppSettings.MIN_DURATION_SECONDS.toFloat()..AppSettings.MAX_DURATION_SECONDS.toFloat()
        )

        Spacer(modifier = Modifier.height(32.dp))

        SettingSlider(
            label = "Wake Screen Before Alarm",
            displayValue = formatDuration(wakeLeadSeconds),
            value = wakeLeadSeconds.toFloat(),
            onValueChange = { wakeLeadSeconds = it.roundToInt() },
            valueRange = AppSettings.MIN_WAKE_LEAD_SECONDS.toFloat()..maxWakeLead.toFloat()
        )
    }
}

@Composable
private fun SettingSlider(
    label: String,
    displayValue: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Text(text = displayValue, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF1B5E20),
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatDuration(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds % 60 == 0 -> "${seconds / 60}m"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}
