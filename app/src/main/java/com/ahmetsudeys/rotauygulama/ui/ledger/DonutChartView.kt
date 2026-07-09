package com.ahmetsudeys.rotauygulama.ui.ledger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A lightweight donut (ring) chart drawn with arcs — no external chart library.
 * Feed it slices via [setSlices]; a light track is drawn when there is no data.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Slice(val value: Float, val color: Int)

    private var slices: List<Slice> = emptyList()

    private val strokePx = dp(30f)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.BUTT
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        color = 0xFFE9ECF1.toInt()
    }
    private val rect = RectF()

    fun setSlices(newSlices: List<Slice>) {
        slices = newSlices.filter { it.value > 0f }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val pad = strokePx / 2f + dp(2f)
        rect.set(cx - size / 2f + pad, cy - size / 2f + pad, cx + size / 2f - pad, cy + size / 2f - pad)

        // Track first so gaps still read as a ring.
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        val total = slices.sumOf { it.value.toDouble() }.toFloat()
        if (total <= 0f) return

        var start = -90f
        val gap = if (slices.size > 1) 3f else 0f
        slices.forEach { slice ->
            val sweep = slice.value / total * 360f
            arcPaint.color = slice.color
            canvas.drawArc(rect, start + gap / 2f, (sweep - gap).coerceAtLeast(0.5f), false, arcPaint)
            start += sweep
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
