package yuku.timerbunyi

import android.content.Context

data class AppSettings(
    val timerDurationSeconds: Int = 25 * 60,
    val wakeLeadSeconds: Int = 60,
) {
    val timerDurationMs: Long get() = timerDurationSeconds * 1000L
    val wakeLeadMs: Long get() = wakeLeadSeconds * 1000L

    companion object {
        const val MIN_DURATION_SECONDS = 15
        const val MAX_DURATION_SECONDS = 60 * 60
        const val MIN_WAKE_LEAD_SECONDS = 5
        const val MAX_WAKE_LEAD_SECONDS = 5 * 60
    }
}

object SettingsStore {
    private const val PREFS_NAME = "timer_settings"
    private const val KEY_DURATION = "timer_duration_seconds"
    private const val KEY_WAKE_LEAD = "wake_lead_seconds"

    fun load(context: Context): AppSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val duration = prefs.getInt(KEY_DURATION, 25 * 60)
            .coerceIn(AppSettings.MIN_DURATION_SECONDS, AppSettings.MAX_DURATION_SECONDS)
        val wakeLead = prefs.getInt(KEY_WAKE_LEAD, 60)
            .coerceIn(AppSettings.MIN_WAKE_LEAD_SECONDS, minOf(AppSettings.MAX_WAKE_LEAD_SECONDS, duration))
        return AppSettings(timerDurationSeconds = duration, wakeLeadSeconds = wakeLead)
    }

    fun save(context: Context, settings: AppSettings) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DURATION, settings.timerDurationSeconds)
            .putInt(KEY_WAKE_LEAD, settings.wakeLeadSeconds)
            .apply()
    }
}
