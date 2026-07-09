package com.ahmetsudeys.rotauygulama.ui.ledger

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A simple vertical column chart drawn by hand. Each column carries a pre-formatted [Column.valueLabel]
 * (so the caller controls currency formatting) and can be highlighted (the selected month).
 */
class ColumnChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Column(
        val label: String,
        val value: Float,
        val valueLabel: String,
        val color: Int,
        val highlight: Boolean = false
    )

    private var columns: List<Column> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(12f)
        color = 0xFF666666.toInt()
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        isFakeBoldText = true
    }
    private val rectF = RectF()

    fun setColumns(newColumns: List<Column>) {
        columns = newColumns
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val n = columns.size
        if (n == 0) return

        val labelH = dp(22f)
        val valueH = dp(20f)
        val chartTop = valueH
        val chartBottom = height - labelH
        val chartH = (chartBottom - chartTop).coerceAtLeast(dp(1f))
        val max = (columns.maxOfOrNull { it.value } ?: 1f).coerceAtLeast(1f)

        val slot = width / n.toFloat()
        val barW = min(slot * 0.52f, dp(56f))
        val radius = dp(8f)

        columns.forEachIndexed { i, c ->
            val cx = slot * i + slot / 2f
            val h = c.value / max * chartH
            val top = chartBottom - h
            val left = cx - barW / 2f
            val right = cx + barW / 2f

            barPaint.color = if (c.highlight) c.color else withAlpha(c.color, 0.38f)
            rectF.set(left, top, right, chartBottom)
            canvas.drawRoundRect(rectF, radius, radius, barPaint)

            valuePaint.color = if (c.highlight) c.color else 0xFF888888.toInt()
            canvas.drawText(c.valueLabel, cx, (top - dp(6f)).coerceAtLeast(valuePaint.textSize), valuePaint)

            labelPaint.isFakeBoldText = c.highlight
            labelPaint.color = if (c.highlight) c.color else 0xFF666666.toInt()
            canvas.drawText(c.label, cx, height - dp(5f), labelPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
