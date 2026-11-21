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

/** Fattore di scala del pannello: 1.0 = grandezza originale, 0.5 = metà. */
const val PALETTE_SCALE = 0.5f

class PaletteView(context: Context) : View(context) {
    var perm = 0
    /** Valori da 100 (colore originale) a 0 (massima attenuazione). */
    val gain = intArrayOf(100, 100, 100)
    var letters = arrayOf("R", "G", "B")
    var colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
    var onPickPerm: ((Int) -> Unit)? = null
    var onGain: (() -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Int): Float = v * d * PALETTE_SCALE
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 26 * d * PALETTE_SCALE
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = 13 * d
    }
    private var drag = -1

    // pallini a sinistra, poi un vuoto, poi i tre gruppi (cursore + quadrati)
    private fun gx(c: Int): Float = dp(78) + c * dp(82)
    private val top get() = dp(10)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(dp(328).toInt(), dp(368).toInt())
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF000000.toInt())
        for (i in 0..5) {
            val cy = top + i * dp(52) + dp(26)
            fill.color = if (i == perm) 0xFF00FF00.toInt() else Color.WHITE
            cv.drawCircle(dp(24), cy, dp(14), fill)
            for (c in 0..2) {
                val src = PERMS[i][c]
                val l = gx(c) + dp(26)
                val r = l + dp(50)
                val sel = i == perm
                fill.color = if (sel) tint(colors[src], -(100 - gain[c]) / 100f) else colors[src]
                cv.drawRect(l, cy - dp(23), r, cy + dp(23), fill)
                if (sel) {
                    stroke.color = Color.WHITE
                    stroke.strokeWidth = dp(3)
                    cv.drawRect(l, cy - dp(23), r, cy + dp(23), stroke)
                }
                val fm = letter.fontMetrics
                cv.drawText(letters[src], (l + r) / 2, cy - (fm.ascent + fm.descent) / 2, letter)
            }
        }
        val th = dp(312)
        for (c in 0..2) {
            val tx = gx(c) + dp(13)
            fill.color = 0xFFBBBBBB.toInt()
            cv.drawRect(tx - dp(2), top + dp(6), tx + dp(3), top + th - dp(6), fill)
            val hy = top + th * (1f - gain[c] / 100f)
            fill.color = Color.WHITE
            cv.drawRect(gx(c) + dp(2), hy - dp(3), gx(c) + dp(24), hy + dp(3), fill)
            cv.drawText("${gain[c]}", gx(c) + dp(38), top + th + dp(24), num)
        }
    }

    private fun setGain(c: Int, y: Float) {
        val f = ((y - top) / dp(312)).coerceIn(0f, 1f)
        gain[c] = ((1f - f) * 100f).roundToInt()
        invalidate()
        onGain?.invoke()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = -1
                for (c in 0..2) if (e.x >= gx(c) - 4 * d && e.x <= gx(c) + dp(26) + 4 * d) drag = c
                if (drag >= 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    setGain(drag, e.y)
                } else if (e.x <= dp(50)) {
                    // solo la colonna dei pallini seleziona lo scambio
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
