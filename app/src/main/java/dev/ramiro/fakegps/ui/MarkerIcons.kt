package dev.ramiro.fakegps.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.PathParser

object MarkerIcons {
    const val ORIGIN_COLOR = 0xFF2E7D32.toInt()
    const val STOP_COLOR = 0xFF1565C0.toInt()
    const val DESTINATION_COLOR = 0xFFC62828.toInt()
    const val ROUTE_COLOR = 0xFF1A73E8.toInt()
    const val ROUTE_CASING_COLOR = 0xFF0D47A1.toInt()
    const val SIM_POSITION_COLOR = 0xFFFF6F00.toInt()
    const val REAL_POSITION_COLOR = 0xFF1A73E8.toInt()
    const val SELECT_PIN_COLOR = 0xFFD32F2F.toInt()
    const val FIXED_PIN_COLOR = 0xFF1565C0.toInt()
    const val SAVED_PIN_COLOR = 0xFF64B5F6.toInt()

    fun colorFor(index: Int, total: Int): Int = when {
        index == 0 -> ORIGIN_COLOR
        index == total - 1 -> DESTINATION_COLOR
        else -> STOP_COLOR
    }

    /**
     * Pin de ubicación (tipo Google Maps) con contorno blanco. El bitmap termina justo en la punta,
     * así que anclado "abajo" la punta marca el lugar exacto.
     */
    fun pin(context: Context, color: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val scale = PIN_SIZE_DP * density / 24f // el trazo está en un lienzo de 24x24
        val stroke = 1.5f * density
        val width = (14 * scale + 2 * stroke).toInt() // el pin ocupa x de 5 a 19
        val height = (20 * scale + 2 * stroke).toInt() // y de 2 a 22 (punta)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(stroke - 5 * scale, stroke - 2 * scale)
        canvas.scale(scale, scale)

        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 2 * stroke / scale
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() }

        canvas.drawPath(PathParser.createPathFromPathData(PIN_PATH), outline)
        canvas.drawPath(PathParser.createPathFromPathData(PIN_PATH), fill)
        canvas.drawCircle(12f, 9f, 2.6f, hole)
        return bitmap
    }

    private const val PIN_SIZE_DP = 44f
    private const val PIN_PATH = "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7z"

    /** Semáforo pequeño: caja oscura con luces roja, amarilla y verde. */
    fun trafficLight(context: Context): Bitmap {
        val d = context.resources.displayMetrics.density
        val width = (12 * d).toInt()
        val height = (26 * d).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 3 * d, 3 * d, paint)
        paint.color = 0xFF263238.toInt()
        canvas.drawRoundRect(1 * d, 1 * d, width - 1 * d, height - 1 * d, 2.5f * d, 2.5f * d, paint)

        val cx = width / 2f
        val r = 3 * d
        listOf(0xFFE53935, 0xFFFDD835, 0xFF43A047).forEachIndexed { i, color ->
            paint.color = color.toInt()
            canvas.drawCircle(cx, (5 + i * 8) * d, r, paint)
        }
        return bitmap
    }

    /** Círculo de color con el número del punto. */
    fun numbered(context: Context, number: Int, color: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (30 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(c, c, c - 2 * density, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(c, c, c - 2 * density, paint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0xFFFFFFFF.toInt()
            textSize = 13 * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(number.toString(), c, c - (text.descent() + text.ascent()) / 2, text)
        return bitmap
    }
}
