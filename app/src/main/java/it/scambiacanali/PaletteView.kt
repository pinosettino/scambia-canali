package it.scambiacanali

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Le sei combinazioni: il canale di uscita c prende il valore dal canale PERMS[i][c]. */
val PERMS = arrayOf(
    intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
    intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0)
)

fun tint(col: Int, g: Float): Int {
    val t = if (g >= 0f) 255 else 0
    val k = abs(g) * 0.65f
    fun m(v: Int) = (v + (t - v) * k).roundToInt()
    return Color.rgb(m(Color.red(col)), m(Color.green(col)), m(Color.blue(col)))
}

class PaletteView(context: Context) : View(context) {
    var perm = 0
    val gain = intArrayOf(128, 128, 128)
    var letters = arrayOf("R", "G", "B")
    var colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
    var onPickPerm: ((Int) -> Unit)? = null
    var onGain: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Int): Float = v * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 26 * d
    }
    private val pct = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 16 * d
    }
    private var drag = -1

    private fun gx(c: Int): Float = dp(50) + c * dp(82)
    private val top get() = dp(10)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(dp(300).toInt(), dp(368).toInt())
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF808080.toInt())
        stroke.strokeWidth = dp(2)
        for (i in 0..5) {
            val cy = top + i * dp(52) + dp(26)
            fill.color = if (i == perm) 0xFF00FF00.toInt() else Color.WHITE
            cv.drawCircle(dp(27), cy, dp(14), fill)
            cv.drawCircle(dp(27), cy, dp(14), stroke)
            for (c in 0..2) {
                val src = PERMS[i][c]
                val l = gx(c) + dp(26)
                val r = l + dp(50)
                fill.color = if (i == perm) tint(colors[src], (gain[c] - 128) / 128f) else colors[src]
                cv.drawRect(l, cy - dp(23), r, cy + dp(23), fill)
                if (i == perm) cv.drawRect(l, cy - dp(23), r, cy + dp(23), stroke)
                val fm = letter.fontMetrics
                cv.drawText(letters[src], (l + r) / 2, cy - (fm.ascent + fm.descent) / 2, letter)
            }
        }
        val th = dp(312)
        stroke.strokeWidth = dp(1)
        for (c in 0..2) {
            val tx = gx(c) + dp(13)
            fill.color = 0xFFBBBBBB.toInt()
            cv.drawRect(tx - dp(2), top + dp(6), tx + dp(3), top + th - dp(6), fill)
            val hy = top + th * (1f - gain[c] / 256f)
            fill.color = Color.WHITE
            cv.drawRect(gx(c) + dp(2), hy - dp(3), gx(c) + dp(24), hy + dp(3), fill)
            cv.drawRect(gx(c) + dp(2), hy - dp(3), gx(c) + dp(24), hy + dp(3), stroke)
            cv.drawText("${gain[c] * 100 / 128}%", gx(c) + dp(38), top + th + dp(24), pct)
        }
    }

    private fun setGain(c: Int, y: Float) {
        val f = ((y - top) / dp(312)).coerceIn(0f, 1f)
        gain[c] = ((1f - f) * 256f).roundToInt()
        invalidate()
        onGain?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = -1
                for (c in 0..2) if (e.x >= gx(c) - dp(3) && e.x <= gx(c) + dp(29)) drag = c
                if (drag >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    setGain(drag, e.y)
                } else {
                    val row = ((e.y - top) / dp(52)).toInt()
                    if (e.y >= top && row in 0..5) onPickPerm?.invoke(row)
                }
            }
            MotionEvent.ACTION_MOVE -> if (drag >= 0) setGain(drag, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = -1
        }
        return true
    }
}
