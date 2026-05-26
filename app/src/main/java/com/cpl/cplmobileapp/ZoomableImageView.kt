package com.cpl.cplmobileapp

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import androidx.viewpager2.widget.ViewPager2

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var matrixMode = NONE
    private val mainMatrix = Matrix()
    private val savedMatrix = Matrix()

    private val startPoint = PointF()
    private val midPoint = PointF()
    private var oldDist = 1f

    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 4f
    private lateinit var matrixValues: FloatArray

    private var scaleDetector: ScaleGestureDetector

    var parentViewPager: ViewPager2? = null

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    init {
        scaleType = ScaleType.MATRIX
        matrixValues = FloatArray(9)
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (drawable != null) {
            resetToInitialFit()
        }
    }

    private fun resetToInitialFit() {
        val drawable = drawable ?: return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        mainMatrix.reset()

        val scaleX = viewWidth / drawableWidth
        val scaleY = viewHeight / drawableHeight
        val fitScale = kotlin.math.min(scaleX, scaleY) * 0.95f

        currentScale = fitScale
        minScale = fitScale
        maxScale = fitScale * 4f

        val redundantXSpace = viewWidth - (drawableWidth * fitScale)
        val redundantYSpace = viewHeight - (drawableHeight * fitScale)

        mainMatrix.postScale(fitScale, fitScale)
        mainMatrix.postTranslate(redundantXSpace / 2f, redundantYSpace / 2f)

        imageMatrix = mainMatrix
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val currentPoint = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(mainMatrix)
                startPoint.set(event.x, event.y)
                matrixMode = DRAG

                // Allow parent to intercept initially until we verify structural drag boundaries
                parentViewPager?.isUserInputEnabled = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    savedMatrix.set(mainMatrix)
                    midPoint(midPoint, event)
                    matrixMode = ZOOM
                    parentViewPager?.isUserInputEnabled = false // Lock out page flips during active pinching
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                matrixMode = NONE
                parentViewPager?.isUserInputEnabled = true // Always unlock swiping when fingers lift
            }
            MotionEvent.ACTION_MOVE -> {
                if (matrixMode == DRAG) {
                    mainMatrix.set(savedMatrix)
                    val dx = currentPoint.x - startPoint.x
                    val dy = currentPoint.y - startPoint.y

                    // SMART HAND-OFF EVALUATION:
                    // Check if the user is trying to swipe past the physical edges of the document
                    if (isAtHorizontalEdge(dx)) {
                        parentViewPager?.isUserInputEnabled = true // Hand touch stream back to ViewPager
                    } else {
                        parentViewPager?.isUserInputEnabled = false // Keep touch focused on internal panning
                        mainMatrix.postTranslate(dx, dy)
                        fixTranslation()
                    }
                }
            }
        }

        imageMatrix = mainMatrix
        return true
    }

    // Helper logic to verify if the canvas layout bounds are flush against viewport walls
    private fun isAtHorizontalEdge(deltaX: Float): Boolean {
        mainMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]

        val viewWidth = width.toFloat()
        val drawable = drawable ?: return true
        val actualWidth = drawable.intrinsicWidth.toFloat() * currentScale

        // If the entire page fits on the screen widthwise, it's always considered at the edge
        if (actualWidth <= viewWidth) return true

        // Swiping Right (Moving toward the previous page): deltaX is positive
        if (deltaX > 0 && transX >= 0f) {
            return true
        }

        // Swiping Left (Moving toward the next page): deltaX is negative
        if (deltaX < 0 && transX <= viewWidth - actualWidth) {
            return true
        }

        return false
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var scaleFactor = detector.scaleFactor
            val previousScale = currentScale
            currentScale *= scaleFactor

            if (currentScale > maxScale) {
                currentScale = maxScale
                scaleFactor = maxScale / previousScale
            } else if (currentScale < minScale) {
                currentScale = minScale
                scaleFactor = minScale / previousScale
            }

            if (currentScale in minScale..maxScale) {
                mainMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                fixTranslation()
            }
            return true
        }
    }

    private fun fixTranslation() {
        mainMatrix.getValues(matrixValues)
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()

        val actualWidth = drawableWidth * currentScale
        val actualHeight = drawableHeight * currentScale

        var fixX = 0f
        var fixY = 0f

        if (actualWidth <= viewWidth) {
            fixX = (viewWidth - actualWidth) / 2f - transX
        } else {
            if (transX > 0) fixX = -transX
            if (transX < viewWidth - actualWidth) fixX = viewWidth - actualWidth - transX
        }

        if (actualHeight <= viewHeight) {
            fixY = (viewHeight - actualHeight) / 2f - transY
        } else {
            if (transY > 0) fixY = -transY
            if (transY < viewHeight - actualHeight) fixY = viewHeight - actualHeight - transY
        }

        if (fixX != 0f || fixY != 0f) {
            mainMatrix.postTranslate(fixX, fixY)
        }
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2f, y / 2f)
    }

    fun resetZoom() {
        resetToInitialFit()
        parentViewPager?.isUserInputEnabled = true
    }
}