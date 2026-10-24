package io.github.madooroy.pinionkaroo.extension

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import io.hammerhead.karooext.models.ViewConfig

/**
 * Draws a data field split in two halves, each with its own label and value, into one bitmap the size of the
 * cell. Drawing everything ourselves sidesteps the RemoteViews limits that sank the first graphical field
 * (see CLAUDE.md): the Karoo's own fonts are available by name in our process ("relative" for the numbers,
 * "ibm-plex-sans-condensed" for the labels), as Barberfish found and uses on the Karoo 2
 * (github.com/jpweytjens/Barberfish, docs/sdk-findings.md). Proportions follow its measurements of native
 * fields: a label of about a third of the value size, centred in a header band, right-aligned with a small
 * margin, and the value just above the centre of the space below.
 */
object SplitFieldRenderer {
    /** One half: its label, its value (null shows a grey dash), and an optional zone colour fill */
    class Half(val label: String, val value: String?, val fill: Int? = null)

    private const val PLACEHOLDER = "--"

    fun render(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        textSizeSp: Int,
        night: Boolean,
        alignment: ViewConfig.Alignment,
        left: Half,
        right: Half,
    ): Bitmap {
        val width = widthPx.coerceAtLeast(1)
        val height = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)

        val foreground = if (night) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val muted = 0xFF888888.toInt()
        val divider = if (night) 0xFF444444.toInt() else 0xFFCCCCCC.toInt()

        val pad = 3f * density
        val half = width / 2f
        val nativeValuePx = textSizeSp * density

        // Zone colour fills first, under the text, with the cell's rounded corners on the outer side
        fun fillHalf(color: Int, isLeft: Boolean) {
            val radius = 8f * density
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.save()
            if (isLeft) {
                canvas.clipRect(0f, 0f, half, height.toFloat())
                canvas.drawRoundRect(0f, 0f, half + radius, height.toFloat(), radius, radius, paint)
            } else {
                canvas.clipRect(half, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(half - radius, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            }
            canvas.restore()
        }
        left.fill?.let { fillHalf(it, isLeft = true) }
        right.fill?.let { fillHalf(it, isLeft = false) }
        fun textColor(h: Half) = h.fill?.let { io.github.madooroy.pinionkaroo.ZoneColors.textOn(it) } ?: foreground

        // Header: a label about a third the native value size, centred in a two-line band like native
        // two-column cells reserve
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.NORMAL)
            textSize = (nativeValuePx * 0.35f).coerceIn(12f * density, 20f * density)
            color = foreground
            textAlign = Paint.Align.RIGHT
        }
        val labelBounds = Rect()
        labelPaint.getTextBounds("CAD", 0, 3, labelBounds)
        val headerHeight = labelPaint.textSize * 2f
        val labelBaseline = (headerHeight + labelBounds.height()) / 2f
        labelPaint.color = textColor(left)
        canvas.drawText(left.label.uppercase(), half - pad, labelBaseline, labelPaint)
        labelPaint.color = textColor(right)
        canvas.drawText(right.label.uppercase(), width - pad, labelBaseline, labelPaint)

        // Values: one size for both halves, so the two numbers line up. Fit the digit height to the band
        // below the header, then shrink if the wider value (at least two digits, so the size does not jump
        // between 9 and 10) does not fit its half. Never larger than the native value size.
        val leftText = left.value ?: PLACEHOLDER
        val rightText = right.value ?: PLACEHOLDER
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("relative", Typeface.NORMAL)
            letterSpacing = -0.04f
        }
        val bandTop = headerHeight
        val bandBottom = height - 2f * density
        val band = (bandBottom - bandTop).coerceAtLeast(1f)
        val digit = Rect()
        valuePaint.textSize = 100f
        valuePaint.getTextBounds("0", 0, 1, digit)
        var fontPx = if (digit.height() > 0) 100f * band * 0.72f / digit.height() else band
        fontPx = fontPx.coerceAtMost(nativeValuePx)
        valuePaint.textSize = fontPx
        val widest = maxOf(valuePaint.measureText(leftText), valuePaint.measureText(rightText), valuePaint.measureText("88"))
        val room = half - 3f * pad
        if (widest > room) fontPx *= room / widest
        valuePaint.textSize = fontPx
        valuePaint.getTextBounds("0", 0, 1, digit)
        val valueBaseline = bandTop + (band + digit.height()) / 2f - 2f * density

        fun drawValue(text: String, halfLeft: Float, halfRight: Float, placeholder: Boolean, color: Int) {
            valuePaint.color = if (placeholder) muted else color
            when (alignment) {
                ViewConfig.Alignment.LEFT -> {
                    valuePaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(text, halfLeft + pad, valueBaseline, valuePaint)
                }
                ViewConfig.Alignment.CENTER -> {
                    valuePaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(text, (halfLeft + halfRight) / 2f, valueBaseline, valuePaint)
                }
                else -> {
                    valuePaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(text, halfRight - pad, valueBaseline, valuePaint)
                }
            }
        }
        drawValue(leftText, 0f, half, left.value == null, textColor(left))
        drawValue(rightText, half, width.toFloat(), right.value == null, textColor(right))

        // A thin divider between the halves, below the labels; a fill already marks the edge
        if (left.fill != null || right.fill != null) return bitmap
        val dividerPaint = Paint().apply {
            color = divider
            strokeWidth = density.coerceAtLeast(1f)
        }
        canvas.drawLine(half, labelBaseline + pad, half, height - 4f * density, dividerPaint)
        return bitmap
    }
}
