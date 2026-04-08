package yuku.timerbunyi

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class Screen { TIMER, SETTINGS }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ALARM_RINGING = "alarm_ringing"
    }

    private var settings by mutableStateOf(AppSettings())
    private var remainingMs by mutableLongStateOf(AppSettings().timerDurationMs)
    private var isRunning by mutableStateOf(false)
    private var currentScreen by mutableStateOf(Screen.TIMER)

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = SettingsStore.load(this)
        TimerService.loadState(this)
        syncStateFromService()

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
            // Poll timer state when running
            LaunchedEffect(isRunning) {
                while (isRunning && isActive) {
                    val running = TimerService.currentlyRunning
                    val alarm = TimerService.alarmActive
                    val endTime = TimerService.endTimeMs
                    if (running && endTime > System.currentTimeMillis()) {
                        remainingMs = endTime - System.currentTimeMillis()
                    } else if (alarm) {
                        remainingMs = 0L
                    } else if (!running && !alarm) {
                        isRunning = false
                        remainingMs = settings.timerDurationMs
                    }
                    delay(250)
                }
            }

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
        syncStateFromService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        syncStateFromService()
    }

    private fun syncStateFromService() {
        val running = TimerService.currentlyRunning
        val alarm = TimerService.alarmActive
        val endTime = TimerService.endTimeMs
        if (running && endTime > System.currentTimeMillis()) {
            isRunning = true
            remainingMs = endTime - System.currentTimeMillis()
        } else if (alarm) {
            isRunning = true
            remainingMs = 0L
        } else {
            isRunning = false
            remainingMs = settings.timerDurationMs
        }
    }

    private fun toggleTimer() {
        if (isRunning) {
            isRunning = false
            remainingMs = settings.timerDurationMs
            startService(Intent(this, TimerService::class.java).apply { action = TimerService.ACTION_STOP })
        } else {
            startForegroundService(Intent(this, TimerService::class.java).apply { action = TimerService.ACTION_START })
            isRunning = true
            remainingMs = settings.timerDurationMs
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
    var durMinText by remember { mutableStateOf((settings.timerDurationSeconds / 60).toString()) }
    var durSecText by remember { mutableStateOf((settings.timerDurationSeconds % 60).toString()) }
    var wakeMinText by remember { mutableStateOf((settings.wakeLeadSeconds / 60).toString()) }
    var wakeSecText by remember { mutableStateOf((settings.wakeLeadSeconds % 60).toString()) }

    fun buildSettings(): AppSettings {
        val durTotal = ((durMinText.toIntOrNull() ?: 0) * 60 + (durSecText.toIntOrNull() ?: 0))
            .coerceIn(AppSettings.MIN_DURATION_SECONDS, AppSettings.MAX_DURATION_SECONDS)
        val wakeTotal = ((wakeMinText.toIntOrNull() ?: 0) * 60 + (wakeSecText.toIntOrNull() ?: 0))
            .coerceIn(AppSettings.MIN_WAKE_LEAD_SECONDS, minOf(AppSettings.MAX_WAKE_LEAD_SECONDS, durTotal))
        return AppSettings(timerDurationSeconds = durTotal, wakeLeadSeconds = wakeTotal)
    }

    BackHandler { onBack(buildSettings()) }

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
            IconButton(onClick = { onBack(buildSettings()) }) {
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

        DurationField(
            label = "Timer Duration",
            minuteValue = durMinText,
            secondValue = durSecText,
            onMinuteChange = { durMinText = it.filter { c -> c.isDigit() }.take(2) },
            onSecondChange = { durSecText = it.filter { c -> c.isDigit() }.take(2) },
            isLast = false
        )

        Spacer(modifier = Modifier.height(28.dp))

        DurationField(
            label = "Wake Screen Before Alarm",
            minuteValue = wakeMinText,
            secondValue = wakeSecText,
            onMinuteChange = { wakeMinText = it.filter { c -> c.isDigit() }.take(2) },
            onSecondChange = { wakeSecText = it.filter { c -> c.isDigit() }.take(2) },
            isLast = true,
            onDone = { onBack(buildSettings()) }
        )
    }
}

@Composable
private fun DurationField(
    label: String,
    minuteValue: String,
    secondValue: String,
    onMinuteChange: (String) -> Unit,
    onSecondChange: (String) -> Unit,
    isLast: Boolean,
    onDone: (() -> Unit)? = null
) {
    val focusManager = LocalFocusManager.current
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color.White,
        unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
        cursorColor = Color.White,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )
    val textStyle = TextStyle(fontSize = 28.sp, fontFamily = FontFamily.Monospace, color = Color.White)

    Text(text = label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = minuteValue,
            onValueChange = onMinuteChange,
            modifier = Modifier.width(88.dp),
            textStyle = textStyle,
            suffix = { Text("m", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) }
            ),
            singleLine = true,
            colors = fieldColors
        )
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = secondValue,
            onValueChange = onSecondChange,
            modifier = Modifier.width(88.dp),
            textStyle = textStyle,
            suffix = { Text("s", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = if (isLast) ImeAction.Done else ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onDone = { focusManager.clearFocus(); onDone?.invoke() }
            ),
            singleLine = true,
            colors = fieldColors
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
