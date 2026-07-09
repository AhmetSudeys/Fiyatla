package com.ahmetsudeys.rotauygulama.ui.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

/**
 * Lets the user pinch-zoom / drag a picked image and fit it inside a circular frame.
 * The visible area inside the circle is what gets exported by [getCroppedCircleBitmap].
 */
class CircleCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null

    /** Image -> view transform. Everything (zoom + pan) is baked into this matrix. */
    private val imageMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var minScale = 0.1f
    private var maxScale = 12f

    // Circle geometry in view coordinates (computed on layout).
    private var circleCx = 0f
    private var circleCy = 0f
    private var circleRadius = 0f

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val overlayPath = Path()

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = clampScaleFactor(detector.scaleFactor)
                imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                invalidate()
                return true
            }
        })

    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        if (width > 0 && height > 0) resetToFit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val margin = 16f * resources.displayMetrics.density
        circleCx = w / 2f
        circleCy = h / 2f
        circleRadius = (minOf(w, h) / 2f) - margin
        if (bitmap != null) resetToFit()
    }

    /** Fit the whole image inside the circle so nothing is cropped initially. */
    private fun resetToFit() {
        val bmp = bitmap ?: return
        val diameter = circleRadius * 2f
        val fit = minOf(diameter / bmp.width, diameter / bmp.height)
        minScale = fit * 0.25f
        maxScale = fit * 10f
        imageMatrix.reset()
        imageMatrix.postScale(fit, fit)
        val dx = circleCx - (bmp.width * fit) / 2f
        val dy = circleCy - (bmp.height * fit) / 2f
        imageMatrix.postTranslate(dx, dy)
    }

    private fun currentScale(): Float {
        imageMatrix.getValues(matrixValues)
        return hypot(matrixValues[Matrix.MSCALE_X], matrixValues[Matrix.MSKEW_Y])
    }

    private fun clampScaleFactor(factor: Float): Float {
        val scale = currentScale()
        val target = scale * factor
        return when {
            target < minScale -> minScale / scale
            target > maxScale -> maxScale / scale
            else -> factor
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, imageMatrix, imagePaint)

        // Dim everything outside the circle (ring = rect minus circle).
        overlayPath.reset()
        overlayPath.fillType = Path.FillType.EVEN_ODD
        overlayPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        overlayPath.addCircle(circleCx, circleCy, circleRadius, Path.Direction.CW)
        canvas.drawPath(overlayPath, dimPaint)
        canvas.drawCircle(circleCx, circleCy, circleRadius, ringPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activePointerId = event.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val index = event.findPointerIndex(activePointerId)
                    if (index != -1) {
                        val x = event.getX(index)
                        val y = event.getY(index)
                        imageMatrix.postTranslate(x - lastX, y - lastY)
                        lastX = x
                        lastY = y
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // If the active pointer went up, promote another one to keep dragging smooth.
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    /**
     * Renders whatever is currently framed by the circle into a [size]x[size] bitmap
     * with a transparent background outside the circle.
     */
    fun getCroppedCircleBitmap(size: Int): Bitmap? {
        val bmp = bitmap ?: return null
        if (circleRadius <= 0f) return null

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Map view coordinates -> output: the circle's bounding box fills the whole output.
        val scale = size / (circleRadius * 2f)
        val exportMatrix = Matrix(imageMatrix)
        exportMatrix.postTranslate(-(circleCx - circleRadius), -(circleCy - circleRadius))
        exportMatrix.postScale(scale, scale)
        canvas.drawBitmap(bmp, exportMatrix, imagePaint)

        // Keep only the circular region (smooth edges via DST_IN mask).
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(mask).drawCircle(size / 2f, size / 2f, size / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawBitmap(mask, 0f, 0f, maskPaint)
        mask.recycle()

        return output
    }
}
