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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ALARM_RINGING = "alarm_ringing"
    }

    private var remainingMs by mutableLongStateOf(TimerService.TIMER_DURATION_MS)
    private var isRunning by mutableStateOf(false)

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            remainingMs = intent?.getLongExtra(TimerService.EXTRA_REMAINING_MS, TimerService.TIMER_DURATION_MS)
                ?: TimerService.TIMER_DURATION_MS
            isRunning = intent?.getBooleanExtra(TimerService.EXTRA_IS_RUNNING, false) ?: false
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            TimerScreen(
                remainingMs = remainingMs,
                isRunning = isRunning,
                onStartStop = ::toggleTimer
            )
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
            remainingMs = TimerService.TIMER_DURATION_MS
        } else {
            startService(intent)
        }
    }
}

@Composable
fun TimerScreen(
    remainingMs: Long,
    isRunning: Boolean,
    onStartStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
