package com.example.truescale.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class CompassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var heading: Float = 0f
    private var pitch: Float = 0f

    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(heading: Float, pitch: Float) {
        this.heading = heading
        this.pitch = pitch
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) * 0.8f

        canvas.drawCircle(centerX, centerY, radius, dialPaint)

        val markerX = centerX + radius * sin(Math.toRadians(heading.toDouble())).toFloat()
        val markerY = centerY - radius * cos(Math.toRadians(heading.toDouble())).toFloat()
        canvas.drawCircle(markerX, markerY, 10f, markerPaint)

        val isLevel = abs(pitch) < 1.0f
        bubblePaint.color = if (isLevel) Color.GREEN else Color.argb(128, 255, 255, 255)
        val bubbleRadius = radius * 0.2f
        val bubbleX = centerX + (pitch / 90f) * radius
        canvas.drawCircle(bubbleX, centerY, bubbleRadius, bubblePaint)
    }
}
