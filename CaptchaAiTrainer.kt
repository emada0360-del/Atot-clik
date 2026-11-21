package com.talabati.fastcore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp

object CaptchaAiTrainer {

    private const val PREFS = "captcha_ai_weights_v1"
    private const val KEY_MODEL = "model_json"
    private const val FEAT_SIZE = 192 // شبكة 12 عرض × 16 ارتفاع
    private const val NUM_CLASSES = 10 // الأرقام 0 إلى 9
    private const val LR = 0.08f

    data class IsolatedDigitResult(
        val cleanBitmap: Bitmap,
        val mask12x16: FloatArray,
        val activePixels: Int
    )

    private var loaded = false
    private val weights = Array(NUM_CLASSES) { FloatArray(FEAT_SIZE) }
    private val bias = FloatArray(NUM_CLASSES)
    private var samplesCount = 0

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODEL, null)
            if (raw != null) {
                runCatching {
                    val obj = JSONObject(raw)
                    samplesCount = obj.optInt("samples", 0)
                    val bArr = obj.optJSONArray("bias")
                    if (bArr != null && bArr.length() == NUM_CLASSES) {
                        for (c in 0 until NUM_CLASSES) {
                            bias[c] = bArr.optDouble(c, 0.0).toFloat()
                        }
                    }
                    val wArr = obj.optJSONArray("weights")
                    if (wArr != null && wArr.length() == NUM_CLASSES) {
                        for (c in 0 until NUM_CLASSES) {
                            val row = wArr.optJSONArray(c) ?: continue
                            for (f in 0 until FEAT_SIZE.coerceAtMost(row.length())) {
                                weights[c][f] = row.optDouble(f, 0.0).toFloat()
                            }
                        }
                    }
                }
            }
            loaded = true
            AutomationStore.setTrainedSamplesCount(samplesCount)
        }
    }

    private fun persist(context: Context) {
        runCatching {
            val obj = JSONObject()
            obj.put("samples", samplesCount)
            val bArr = JSONArray()
            for (c in 0 until NUM_CLASSES) bArr.put(bias[c].toDouble())
            obj.put("bias", bArr)

            val wArr = JSONArray()
            for (c in 0 until NUM_CLASSES) {
                val row = JSONArray()
                for (f in 0 until FEAT_SIZE) row.put(weights[c][f].toDouble())
                wArr.put(row)
            }
            obj.put("weights", wArr)

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODEL, obj.toString())
                .apply()
        }
    }

    /**
     * يقص صندوق الكابتشا، يعزل اللون الغالب للرقم عن النقاط والخطوط، ويبني صورة نظيفة + ميزات 12x16.
     */
    fun isolateDigit(captchaBoxBitmap: Bitmap): IsolatedDigitResult? {
        return try {
            val w = captchaBoxBitmap.width
            val h = captchaBoxBitmap.height
            if (w < 30 || h < 30) return null

            val gw = (w / 2).coerceAtLeast(16)
            val gh = (h / 2).coerceAtLeast(16)
            val pixels = IntArray(w * h)
            captchaBoxBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val hsv = FloatArray(3)
            val hueBin = IntArray(gw * gh) { -1 }
            val binScores = IntArray(12)

            for (gy in 0 until gh) {
                val sy = (gy * h) / gh
                val rowOffset = sy * w
                for (gx in 0 until gw) {
                    val sx = (gx * w) / gw
                    Color.colorToHSV(pixels[rowOffset + sx], hsv)
                    // استبعاد الخلفية الداكنة والرمادية الباهتة
                    if (hsv[1] > 0.30f && hsv[2] > 0.32f) {
                        val bin = ((hsv[0] % 360f + 360f) % 360f / 30f).toInt().coerceIn(0, 11)
                        hueBin[gy * gw + gx] = bin
                        binScores[bin]++
                    }
                }
            }

            var bestBin = -1
            var bestScore = 0
            for (b in 0 until 12) {
                val score = binScores[(b + 11) % 12] + binScores[b] + binScores[(b + 1) % 12]
                if (score > bestScore) {
                    bestScore = score
                    bestBin = b
                }
            }
            if (bestBin < 0 || bestScore < 20) return null

            val colorMask = BooleanArray(gw * gh)
            for (i in colorMask.indices) {
                val b = hueBin[i]
                if (b >= 0) {
                    val diff = (b - bestBin + 12) % 12
                    colorMask[i] = diff == 0 || diff == 1 || diff == 11
                }
            }

            // تصفية النقاط الفردية الصغيرة وإبقاء الكتل المتماسكة للرقم
            val cleanMask = BooleanArray(gw * gh)
            var activeCount = 0
            for (gy in 1 until gh - 1) {
                for (gx in 1 until gw - 1) {
                    val idx = gy * gw + gx
                    if (!colorMask[idx]) continue
                    var neighbors = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            if (colorMask[(gy + dy) * gw + (gx + dx)]) neighbors++
                        }
                    }
                    if (neighbors >= 3) {
                        cleanMask[idx] = true
                        activeCount++
                    }
                }
            }
            if (activeCount < 18) return null

            val feat12x16 = buildGrid12x16(cleanMask, gw, gh)

            // بناء صورة أبيض وأسود واضحة لقراءة OCR السريعة
            val pad = 32
            val outW = w + pad * 2
            val outH = h + pad * 2
            val outPixels = IntArray(outW * outH) { Color.WHITE }

            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    if (!cleanMask[gy * gw + gx]) continue
                    val cx = (gx * w) / gw + pad
                    val cy = (gy * h) / gh + pad
                    for (yy in (cy - 2)..(cy + 2)) {
                        if (yy !in 0 until outH) continue
                        for (xx in (cx - 2)..(cx + 2)) {
                            if (xx !in 0 until outW) continue
                            outPixels[yy * outW + xx] = Color.BLACK
                        }
                    }
                }
            }

            val cleanBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            cleanBmp.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

            IsolatedDigitResult(
                cleanBitmap = cleanBmp,
                mask12x16 = feat12x16,
                activePixels = activeCount
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildGrid12x16(mask: BooleanArray, gw: Int, gh: Int): FloatArray {
        val cols = 12
        val rows = 16
        val out = FloatArray(cols * rows)

        var minX = gw
        var maxX = 0
        var minY = gh
        var maxY = 0
        for (y in 0 until gh) {
            for (x in 0 until gw) {
                if (mask[y * gw + x]) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX <= minX || maxY <= minY) return out

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1

        for (r in 0 until rows) {
            val y0 = minY + (r * boxH) / rows
            val y1 = (minY + ((r + 1) * boxH) / rows).coerceAtMost(gh)
            for (c in 0 until cols) {
                val x0 = minX + (c * boxW) / cols
                val x1 = (minX + ((c + 1) * boxW) / cols).coerceAtMost(gw)
                var hit = 0
                var total = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        total++
                        if (mask[y * gw + x]) hit++
                    }
                }
                out[r * cols + c] = if (total > 0) (hit.toFloat() / total.toFloat()) else 0f
            }
        }
        return out
    }

    fun predict(context: Context, features: FloatArray?): Pair<Int, Float>? {
        ensureLoaded(context)
        if (features == null || features.size != FEAT_SIZE || samplesCount == 0) return null

        val logits = FloatArray(NUM_CLASSES)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (c in 0 until NUM_CLASSES) {
            var sum = bias[c]
            val wRow = weights[c]
            for (f in 0 until FEAT_SIZE) {
                sum += wRow[f] * features[f]
            }
            logits[c] = sum
            if (sum > maxLogit) maxLogit = sum
        }

        val probs = FloatArray(NUM_CLASSES)
        var sumExp = 0f
        for (c in 0 until NUM_CLASSES) {
            val e = exp((logits[c] - maxLogit).coerceIn(-15f, 15f).toDouble()).toFloat()
            probs[c] = e
            sumExp += e
        }

        var bestClass = 0
        var bestProb = 0f
        for (c in 0 until NUM_CLASSES) {
            val p = if (sumExp > 0f) probs[c] / sumExp else 0f
            if (p > bestProb) {
                bestProb = p
                bestClass = c
            }
        }
        return bestClass to bestProb
    }

    fun trainSample(context: Context, digitChar: Char, features: FloatArray?) {
        if (digitChar !in '0'..'9' || features == null || features.size != FEAT_SIZE) return
        ensureLoaded(context)

        val target = digitChar - '0'
        val logits = FloatArray(NUM_CLASSES)
        var maxLogit = Float.NEGATIVE_INFINITY
        for (c in 0 until NUM_CLASSES) {
            var sum = bias[c]
            val wRow = weights[c]
            for (f in 0 until FEAT_SIZE) sum += wRow[f] * features[f]
            logits[c] = sum
            if (sum > maxLogit) maxLogit = sum
        }

        val probs = FloatArray(NUM_CLASSES)
        var sumExp = 0f
        for (c in 0 until NUM_CLASSES) {
            val e = exp((logits[c] - maxLogit).coerceIn(-15f, 15f).toDouble()).toFloat()
            probs[c] = e
            sumExp += e
        }

        for (c in 0 until NUM_CLASSES) {
            val p = if (sumExp > 0f) probs[c] / sumExp else 0f
            val y = if (c == target) 1f else 0f
            val grad = (y - p) * LR
            bias[c] += grad
            val wRow = weights[c]
            for (f in 0 until FEAT_SIZE) {
                wRow[f] += grad * features[f]
            }
        }

        samplesCount++
        AutomationStore.setTrainedSamplesCount(samplesCount)
        if (samplesCount % 5 == 0) {
            persist(context)
        }
    }

    fun flushSave(context: Context) {
        persist(context)
    }
}
