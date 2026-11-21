package com.talabati.fastcore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenVisionEngine(private val context: Context) {

    private data class RecognizedWord(val text: String, val box: Rect)

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    init {
        CaptchaAiTrainer.ensureLoaded(context)
    }

    suspend fun analyzeFrame(
        bitmap: Bitmap,
        targetZones: List<String>,
        blockedRestaurants: List<String>,
        orderFilterMode: OrderFilterMode
    ): FrameDecision {
        val visionText = runTextRecognition(bitmap)

        val words = ArrayList<RecognizedWord>()
        val linesBuilder = StringBuilder()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lt = line.text.trim()
                if (lt.isNotEmpty()) {
                    linesBuilder.append(lt).append('\n')
                }
                for (element in line.elements) {
                    val et = element.text.trim()
                    val box = element.boundingBox
                    if (et.isNotEmpty() && box != null) {
                        words.add(RecognizedWord(et, box))
                    }
                }
                val lBox = line.boundingBox
                if (lt.isNotEmpty() && lBox != null) {
                    words.add(RecognizedWord(lt, lBox))
                }
            }
        }

        val fullText = linesBuilder.toString()
        val normText = normalizeArabic(fullText)
        val h = bitmap.height.toFloat()
        val w = bitmap.width.toFloat()

        // فحص ما إذا كانت الشاشة الحالية هي شاشة الكابتشا
        val keypadMap = HashMap<Char, PointF>()
        var rawBandDigit: String? = null

        for (item in words) {
            val d = singleDigitChar(item.text) ?: continue
            val cy = item.box.exactCenterY()
            if (cy >= h * 0.55f) {
                if (!keypadMap.containsKey(d)) {
                    keypadMap[d] = PointF(item.box.exactCenterX(), cy)
                }
            } else if (cy in (h * 0.16f)..(h * 0.43f) && rawBandDigit == null) {
                rawBandDigit = d.toString()
            }
        }

        val isCaptcha = normText.contains("ادخل الرقم") ||
            normText.contains("للمتابعه") ||
            keypadMap.size >= 5

        if (isCaptcha) {
            return solveCaptchaScreen(bitmap, rawBandDigit, keypadMap)
        }

        // شاشة الطلب: التحقق من نوع الطلب والمطاعم المحظورة والمناطق المستهدفة
        val isRestaurant = normText.contains("المطعم") || normText.contains("مطعم")

        val blockedHit = blockedRestaurants.firstOrNull { isBlockedMatch(fullText, it) }
        if (blockedHit != null) {
            return FrameDecision(
                isBlocked = true,
                blockedName = blockedHit,
                allowedByOrderFilter = false
            )
        }

        val filterOk = when (orderFilterMode) {
            OrderFilterMode.ALL -> true
            OrderFilterMode.NORMAL_ONLY -> !isRestaurant
            OrderFilterMode.RESTAURANT_ONLY -> isRestaurant
        }

        val matchedZone = if (targetZones.isEmpty()) {
            "الكل"
        } else {
            targetZones.firstOrNull { isZoneMatch(fullText, it) }
        }

        // البحث عن كلمة "موافق" نفسها في الجزء السفلي الأيمن
        var approveCenter: PointF? = null
        var approveRect: Rect? = null

        for (item in words) {
            val nWord = normalizeArabic(item.text)
            if (nWord == "موافق" || nWord.contains("موافق")) {
                val cx = item.box.exactCenterX()
                val cy = item.box.exactCenterY()
                if (cy >= h * 0.85f && cx >= w * 0.45f) {
                    approveRect = Rect(item.box)
                    approveCenter = PointF(cx, cy)
                    break
                }
            }
        }

        return FrameDecision(
            isCaptchaScreen = false,
            isBlocked = false,
            allowedByOrderFilter = filterOk,
            matchedTargetZone = matchedZone,
            approveWordCenter = approveCenter,
            approveWordRect = approveRect
        )
    }

    private suspend fun solveCaptchaScreen(
        bitmap: Bitmap,
        rawBandDigit: String?,
        keypadMap: Map<Char, PointF>
    ): FrameDecision {
        val cropLeft = (bitmap.width * 0.10f).toInt().coerceAtLeast(0)
        val cropTop = (bitmap.height * 0.19f).toInt().coerceAtLeast(0)
        val cropRight = (bitmap.width * 0.90f).toInt().coerceAtMost(bitmap.width)
        val cropBottom = (bitmap.height * 0.38f).toInt().coerceAtMost(bitmap.height)

        val cropW = cropRight - cropLeft
        val cropH = cropBottom - cropTop

        var isolatedDigitStr: String? = null
        var features12x16: FloatArray? = null

        if (cropW > 30 && cropH > 30) {
            val region = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropW, cropH)
            try {
                val iso = CaptchaAiTrainer.isolateDigit(region)
                if (iso != null) {
                    features12x16 = iso.mask12x16
                    isolatedDigitStr = readDigitFromCleanBitmap(iso.cleanBitmap)
                    iso.cleanBitmap.recycle()
                }
            } finally {
                region.recycle()
            }
        }

        val aiPrediction = CaptchaAiTrainer.predict(context, features12x16)
        val aiDigitStr = aiPrediction?.first?.toString()
        val aiConf = aiPrediction?.second ?: 0f

        val finalDigit = when {
            isolatedDigitStr != null -> isolatedDigitStr
            aiDigitStr != null && aiConf >= 0.65f -> aiDigitStr
            rawBandDigit != null -> rawBandDigit
            else -> null
        }

        // تدريب الذكاء الاصطناعي المحلي عند وجود قراءة متسقة
        if (isolatedDigitStr != null && features12x16 != null) {
            val agreesBand = rawBandDigit == null || rawBandDigit == isolatedDigitStr
            val notConflictingAi = aiDigitStr == null || aiDigitStr == isolatedDigitStr || aiConf < 0.60f
            if (agreesBand && notConflictingAi) {
                CaptchaAiTrainer.trainSample(context, isolatedDigitStr[0], features12x16)
            }
        }

        val tapPoint = if (finalDigit != null) {
            val ch = finalDigit[0]
            keypadMap[ch] ?: fallbackKeypadPoint(bitmap, ch)
        } else {
            null
        }

        return FrameDecision(
            isCaptchaScreen = true,
            captchaDigit = finalDigit,
            captchaKeypadPoint = tapPoint,
            aiConfidence = aiConf
        )
    }

    private suspend fun readDigitFromCleanBitmap(cleanBmp: Bitmap): String? {
        return try {
            val res = runTextRecognition(cleanBmp)
            for (block in res.textBlocks) {
                for (line in block.lines) {
                    val d = singleDigitChar(line.text)
                    if (d != null) return d.toString()
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun fallbackKeypadPoint(bitmap: Bitmap, digitChar: Char): PointF? {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val x = when (digitChar) {
            '1', '4', '7' -> w * 0.25f
            '2', '5', '8', '0' -> w * 0.50f
            '3', '6', '9' -> w * 0.75f
            else -> return null
        }
        val y = when (digitChar) {
            '1', '2', '3' -> h * 0.63f
            '4', '5', '6' -> h * 0.72f
            '7', '8', '9' -> h * 0.81f
            '0' -> h * 0.90f
            else -> return null
        }
        return PointF(x, y)
    }

    private fun isZoneMatch(source: String, zone: String): Boolean {
        val z = normalizeArabic(zone).replace(" ", "")
        if (z.isEmpty()) return false
        val src = normalizeArabic(source).replace(" ", "")
        return src.contains(z)
    }

    private fun isBlockedMatch(source: String, blocked: String): Boolean {
        val b = normalizeArabic(blocked).replace(" ", "")
        if (b.isEmpty()) return false
        val src = normalizeArabic(source).replace(" ", "")
        return src.contains(b)
    }

    private fun normalizeArabic(input: String): String {
        return input
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace(Regex("[\\u064B-\\u065F]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
    }

    private fun singleDigitChar(text: String): Char? {
        val cleaned = buildString {
            for (c in text.trim()) {
                when (c) {
                    in '0'..'9' -> append(c)
                    in '٠'..'٩' -> append('0' + (c - '٠'))
                    in '۰'..'۹' -> append('0' + (c - '۰'))
                }
            }
        }
        return if (cleaned.length == 1) cleaned[0] else null
    }

    private suspend fun runTextRecognition(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            val img = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(img)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    fun close() {
        CaptchaAiTrainer.flushSave(context)
        recognizer.close()
    }
}
