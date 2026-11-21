package com.talabati.fastcore

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object AutomationStore {
    private const val PREFS_NAME = "fastcore_prefs"
    private const val KEY_ZONES = "zones"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_FILTER = "filter_mode"
    private const val KEY_DELAY = "click_delay"
    private const val KEY_JITTER = "jitter_px"

    private val _config = MutableStateFlow(AutomationConfig())
    val config: StateFlow<AutomationConfig> = _config.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val zones = prefs.getString(KEY_ZONES, "الحويش.بعقوبة الجديدة.حي التحرير") ?: ""
        val blocked = prefs.getString(KEY_BLOCKED, "") ?: ""
        val filterName = prefs.getString(KEY_FILTER, OrderFilterMode.ALL.name) ?: OrderFilterMode.ALL.name
        val filterMode = runCatching { OrderFilterMode.valueOf(filterName) }
            .getOrDefault(OrderFilterMode.ALL)
        val delayMs = prefs.getLong(KEY_DELAY, 20L).coerceIn(5L, 200L)
        val jitter = prefs.getInt(KEY_JITTER, 10).coerceIn(2, 35)

        _config.update {
            it.copy(
                zonesRaw = zones,
                blockedRaw = blocked,
                orderFilterMode = filterMode,
                clickDelayMs = delayMs,
                jitterPx = jitter
            )
        }
    }

    fun saveSettings(
        context: Context,
        zones: String,
        blocked: String,
        filterMode: OrderFilterMode,
        delayMs: Long,
        jitterPx: Int
    ) {
        val safeDelay = delayMs.coerceIn(5L, 200L)
        val safeJitter = jitterPx.coerceIn(2, 35)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ZONES, zones)
            .putString(KEY_BLOCKED, blocked)
            .putString(KEY_FILTER, filterMode.name)
            .putLong(KEY_DELAY, safeDelay)
            .putInt(KEY_JITTER, safeJitter)
            .apply()

        _config.update {
            it.copy(
                zonesRaw = zones,
                blockedRaw = blocked,
                orderFilterMode = filterMode,
                clickDelayMs = safeDelay,
                jitterPx = safeJitter,
                lastLog = "تم حفظ الإعدادات"
            )
        }
    }

    fun setRunning(running: Boolean, status: String) {
        _config.update {
            it.copy(
                isRunning = running,
                statusText = status,
                armedForTargetCaptcha = if (!running) false else it.armedForTargetCaptcha
            )
        }
    }

    fun setArmedForTargetCaptcha(armed: Boolean) {
        _config.update { it.copy(armedForTargetCaptcha = armed) }
    }

    fun setTrainedSamplesCount(count: Int) {
        _config.update { it.copy(trainedSamplesCount = count) }
    }

    fun updateLog(msg: String) {
        _config.update { it.copy(lastLog = msg) }
    }
}
