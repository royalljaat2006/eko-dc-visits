package com.eko.dcvisits.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Burns the evidence caption into the pixels (contracts/c4-watermark — spec
 * pending; this is the M1 shape). The evidentiary claim rests on the
 * server-verified SHA-256 of these bytes, never the drawn text.
 *
 * Per-category compression targets the 150–500KB band (BUILD_PLAN §3.2).
 */
object Watermark {

    data class Result(val jpeg: ByteArray, val width: Int, val height: Int)

    fun apply(source: Bitmap, lines: List<String>, targetKb: Int = 320): Result {
        // Downscale very large sensor frames before drawing / compressing.
        val maxEdge = 1600
        val scaled = if (max(source.width, source.height) > maxEdge) {
            val ratio = maxEdge.toFloat() / max(source.width, source.height)
            Bitmap.createScaledBitmap(source, (source.width * ratio).toInt(), (source.height * ratio).toInt(), true)
        } else source

        val out = scaled.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val textSize = max(out.width, out.height) * 0.028f
        val pad = textSize * 0.5f
        val lineGap = textSize * 1.35f
        val blockHeight = pad * 2 + lineGap * lines.size

        val bg = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        canvas.drawRect(0f, out.height - blockHeight, out.width.toFloat(), out.height.toFloat(), bg)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
        }
        var y = out.height - blockHeight + pad + textSize
        for (line in lines) {
            canvas.drawText(line, pad, y, text)
            y += lineGap
        }

        var quality = 82
        var bytes: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            bytes = bos.toByteArray()
            quality -= 8
        } while (bytes.size > targetKb * 1024 && quality >= 40)

        return Result(bytes, out.width, out.height)
    }

    /** Rough size guard so an accidental huge frame never bloats a sync batch. */
    fun withinLimit(bytes: ByteArray): Boolean = bytes.size <= 900 * 1024
}
