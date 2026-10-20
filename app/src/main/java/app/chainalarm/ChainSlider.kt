package app.chainalarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/** Pill track with tick dots: light-blue active part, dark-green rest, vertical handle between. */
class ChainSlider(context: Context) : View(context) {

    var min = 1
    var max = 20
    var value = 1
    var dots = 20
    var onChange: ((Int) -> Unit)? = null

    private val d: Float = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val colActive = 0xFFA8C7FA.toInt()
    private val colTrack = 0xFF0E4A22.toInt()
    private val colDotOn = 0xFF1B3A6E.toInt()
    private val colDotOff = 0xFFE3EEE5.toInt()

    private val trackH: Float get() = 16f * d
    private val handleW: Float get() = 4f * d
    private val gap: Float get() = 8f * d
    private val minActive: Float get() = 26f * d

    private fun usable(): Float = width - minActive - gap * 2 - handleW

    private fun frac(): Float {
        if (max <= min) return 0f
        return (value.coerceIn(min, max) - min).toFloat() / (max - min).toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (48f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val th = trackH
        val top = cy - th / 2f
        val bottom = cy + th / 2f
        val activeEnd = minActive + frac() * usable()
        val hx0 = activeEnd + gap
        val hx1 = hx0 + handleW
        val inStart = hx1 + gap
        val outerR = th / 2f
        val innerR = 3f * d

        paint.style = Paint.Style.FILL

        if (inStart < w) {
            paint.color = colTrack
            rect.set(inStart, top, w, bottom)
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(innerR, innerR, outerR, outerR, outerR, outerR, innerR, innerR),
                Path.Direction.CW
            )
            canvas.drawPath(path, paint)
        }

        paint.color = colActive
        rect.set(0f, top, activeEnd, bottom)
        path.reset()
        path.addRoundRect(
            rect,
            floatArrayOf(outerR, outerR, innerR, innerR, innerR, innerR, outerR, outerR),
            Path.Direction.CW
        )
        canvas.drawPath(path, paint)

        val hh = minOf(44f * d, height.toFloat())
        rect.set(hx0, cy - hh / 2f, hx1, cy + hh / 2f)
        canvas.drawRoundRect(rect, handleW / 2f, handleW / 2f, paint)

        val inset = 7f * d
        val r = 1.8f * d
        val n = if (dots < 2) 2 else dots
        val span = w - 2f * inset
        for (i in 0 until n) {
            val x = inset + span * i / (n - 1)
            if (x + r > activeEnd && x - r < inStart) continue
            paint.color = if (x < activeEnd) colDotOn else colDotOff
            canvas.drawCircle(x, cy, r, paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromX(e.x)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun updateFromX(x: Float) {
        val u = usable()
        if (u <= 0f) return
        val f = ((x - (minActive + gap + handleW / 2f)) / u).coerceIn(0f, 1f)
        val v = (min + Math.round(f * (max - min))).coerceIn(min, max)
        if (v != value) {
            value = v
            invalidate()
            onChange?.invoke(v)
        }
    }
}
