package kr.co.edoubles.carlostdetect

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kr.co.edoubles.carlostdetect.utils.TEXT_PADDING
import org.tensorflow.lite.task.vision.detector.Detection
import kotlin.math.max

/**
 * Custom box view
 * 탐지된 물체를 확인 하기 위한 CustomView (물체를 직사각형으로 감싸는 View)
 */
class CustomBoxView(
    context: Context,
    attrs: AttributeSet,
) : View(context, attrs) {

    // 탐지된 물건에 대한 정보가 담겨 있는 List
    private var results: MutableList<Detection> = mutableListOf()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var scaleFactor: Float = 1f

    private var bounds = Rect()

    init {
        initPaints()
    }

    fun clear() {
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 30f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        boxPaint.color = ContextCompat.getColor(context, R.color.bounding_box_color)
        boxPaint.strokeWidth = 3F
        boxPaint.style = Paint.Style.STROKE
    }

    fun setResults(
        detectionResults: List<Detection>,
        imageHeight: Int,
        imageWidth: Int,
    ) {
        results.clear()
        results.addAll(detectionResults)

        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
        // invalidate()를 반드시 호출 해야 그림을 그린다
        invalidate()
    }

    @SuppressLint("DrawAllocation", "DefaultLocale")
    override fun onDraw(canvas: Canvas) {
        for (result in results) {
            val boundingBox = result.boundingBox

            val top = boundingBox.top * scaleFactor
            val bottom = boundingBox.bottom * scaleFactor
            val left = boundingBox.left * scaleFactor
            val right = boundingBox.right * scaleFactor

            val drawableRect = RectF(left, top, right, bottom)
            canvas.drawRect(drawableRect, boxPaint)

            val drawableText =
                result.categories[0].label + " " +
                        String.format("%.2f", result.categories[0].score)

            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)

            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + TEXT_PADDING,
                top + textHeight + TEXT_PADDING,
                textBackgroundPaint
            )

            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
        }
    }
}