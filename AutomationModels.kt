package com.talabati.fastcore

import android.graphics.PointF
import android.graphics.Rect

enum class OrderFilterMode {
    ALL,
    NORMAL_ONLY,
    RESTAURANT_ONLY
}

data class AutomationConfig(
    val isRunning: Boolean = false,
    val statusText: String = "متوقف",
    val lastLog: String = "جاهز للبدء",
    val zonesRaw: String = "الحويش.بعقوبة الجديدة.حي التحرير",
    val blockedRaw: String = "",
    val orderFilterMode: OrderFilterMode = OrderFilterMode.ALL,
    val clickDelayMs: Long = 20L,
    val jitterPx: Int = 10,
    val armedForTargetCaptcha: Boolean = false,
    val trainedSamplesCount: Int = 0
) {
    fun activeZones(): List<String> =
        zonesRaw.split(".", "،", ",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun blockedList(): List<String> =
        blockedRaw.split(".", "،", ",", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}

data class FrameDecision(
    val isCaptchaScreen: Boolean = false,
    val isBlocked: Boolean = false,
    val blockedName: String? = null,
    val allowedByOrderFilter: Boolean = true,
    val matchedTargetZone: String? = null,
    val approveWordCenter: PointF? = null,
    val approveWordRect: Rect? = null,
    val captchaDigit: String? = null,
    val captchaKeypadPoint: PointF? = null,
    val aiConfidence: Float = 0f
)
