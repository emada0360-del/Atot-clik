package com.talabati.fastcore

import android.content.pm.PackageManager
import android.graphics.PointF
import android.graphics.Rect
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import kotlin.random.Random

object ShizukuTapper {

    private const val REQ_SHIZUKU = 1001

    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    fun isServiceAlive(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission(): Boolean {
        return try {
            if (!Shizuku.pingBinder()) return false
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun variablePointInsideWord(center: PointF, wordRect: Rect?, jitterPx: Int): PointF {
        val j = jitterPx.coerceAtLeast(2)
        val dx = Random.nextInt(-j, j + 1).toFloat()
        val dy = Random.nextInt(-j / 2 - 1, j / 2 + 2).toFloat()

        var x = center.x + dx
        var y = center.y + dy

        if (wordRect != null && wordRect.width() > 6 && wordRect.height() > 6) {
            val padX = (wordRect.width() * 0.15f).coerceAtLeast(2f)
            val padY = (wordRect.height() * 0.15f).coerceAtLeast(2f)
            x = x.coerceIn(wordRect.left + padX, wordRect.right - padX)
            y = y.coerceIn(wordRect.top + padY, wordRect.bottom - padY)
        }
        return PointF(x, y)
    }

    fun variablePointAroundDigit(center: PointF, jitterPx: Int): PointF {
        val j = (jitterPx / 2).coerceIn(2, 10)
        val dx = Random.nextInt(-j, j + 1).toFloat()
        val dy = Random.nextInt(-j, j + 1).toFloat()
        return PointF(center.x + dx, center.y + dy)
    }

    suspend fun tapAt(point: PointF, delayMs: Long): Boolean {
        if (!hasPermission()) return false
        val x = point.x.toInt().coerceAtLeast(1)
        val y = point.y.toInt().coerceAtLeast(1)
        if (delayMs > 0) {
            delay(delayMs)
        }
        return runShell("input tap $x $y")
    }

    private fun runShell(cmd: String): Boolean {
        val method = newProcessMethod ?: return false
        return try {
            val proc = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process
                ?: return false
            proc.waitFor()
            proc.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }
}
