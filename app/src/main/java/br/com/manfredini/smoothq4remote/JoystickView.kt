package br.com.manfredini.smoothq4remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    fun interface Listener {
        fun onMove(x: Float, y: Float, released: Boolean)
    }

    var listener: Listener? = null
    private val knob = PointF()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553D5263 }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF45D6B5.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF45D6B5.toInt() }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x557E919F
        strokeWidth = dp(1f)
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val radius get() = min(width, height) * 0.43f
    private val knobRadius get() = radius * 0.23f

    init {
        isClickable = true
        contentDescription = "Joystick pan e tilt"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cx, cy, radius, basePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawLine(cx - radius * .68f, cy, cx + radius * .68f, cy, guidePaint)
        canvas.drawLine(cx, cy - radius * .68f, cx, cy + radius * .68f, guidePaint)
        canvas.drawCircle(cx + knob.x * radius, cy + knob.y * radius, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cx
                val dy = event.y - cy
                val distance = sqrt(dx * dx + dy * dy)
                val scale = if (distance > radius) radius / distance else 1f
                knob.set(dx * scale / radius, dy * scale / radius)
                listener?.onMove(knob.x, knob.y, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knob.set(0f, 0f)
                listener?.onMove(0f, 0f, true)
                invalidate()
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
