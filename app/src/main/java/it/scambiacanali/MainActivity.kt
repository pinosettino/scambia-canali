package it.scambiacanali

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.ceil
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat

class Step(val perm: Int, val gain: IntArray, val cmy: Boolean)

class OpenImage : ActivityResultContracts.OpenDocument() {
    var initial: Uri? = null
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val i = super.createIntent(context, input)
        initial?.let { i.putExtra("android.provider.extra.INITIAL_URI", it) }
        return i
    }
}

private fun cl(x: Float): Int = if (x < 0f) 0 else if (x > 255f) 255 else (x + 0.5f).toInt()

/**
 * Retino a pallini, filtro indipendente: lavora sulla luminosità dell'immagine che arriva dai filtri
 * precedenti. Griglia di celle quadrate (cols colonne), un pallino al centro di ogni cella: più la
 * cella è scura, più il pallino è grande (cella nera = pallino che riempie la cella e si fonde con
 * i vicini, cella bianca = nessun pallino). Pallini nel colore del nero, sfondo nel colore del bianco.
 */
/** Raggio (in celle) del pallino che, nella griglia, copre la frazione "dark" della cella; i pallini si fondono oltre 0,5. */
private fun dotRadius(dark: Double): Float {
    fun cov(r: Double): Double =
        if (r <= 0.5) Math.PI * r * r
        else Math.PI * r * r - 4 * (r * r * Math.acos(0.5 / r) - 0.5 * Math.sqrt(r * r - 0.25))
    if (dark <= Math.PI / 4) return Math.sqrt(dark / Math.PI).toFloat()
    var lo = 0.5
    var hi = 0.7072
    repeat(40) {
        val m = (lo + hi) / 2
        if (cov(m) < dark) lo = m else hi = m
    }
    return hi.toFloat()
}

fun applyDots(px: IntArray, w: Int, h: Int, cols: Int, black: Int, white: Int) {
    val cs = w.toFloat() / cols
    val rows = ceil((h / cs).toDouble()).toInt()
    val sum = LongArray(cols * rows)
    val nt = IntArray(cols * rows)
    val xc = IntArray(w) { min(cols - 1, (it / cs).toInt()) }
    for (y in 0 until h) {
        val yc = min(rows - 1, (y / cs).toInt()) * cols
        val base = y * w
        for (x in 0 until w) {
            val c0 = px[base + x]
            val lum = (299 * ((c0 shr 16) and 255) + 587 * ((c0 shr 8) and 255) + 114 * (c0 and 255)) / 1000
            val k = yc + xc[x]
            nt[k]++
            sum[k] += lum.toLong()
        }
    }
    java.util.Arrays.fill(px, (0xFF shl 24) or (white and 0xFFFFFF))
    val bk = (0xFF shl 24) or (black and 0xFFFFFF)
    val br = (black shr 16) and 255
    val bg = (black shr 8) and 255
    val bb = black and 255
    val rt = FloatArray(256) { dotRadius(it / 255.0) }
    for (j in 0 until rows) {
        for (i in 0 until cols) {
            val k = j * cols + i
            if (nt[k] == 0) continue
            val dark = 1f - sum[k].toFloat() / (nt[k] * 255f)
            if (dark < 0.02f) continue
            val r = rt[(dark * 255f + 0.5f).toInt().coerceIn(0, 255)] * cs
            val cx = (i + 0.5f) * cs
            val cy = (j + 0.5f) * cs
            val x0 = max(0, floor(cx - r - 1f).toInt())
            val x1 = min(w - 1, ceil(cx + r + 1f).toInt())
            val y0 = max(0, floor(cy - r - 1f).toInt())
            val y1 = min(h - 1, ceil(cy + r + 1f).toInt())
            for (y in y0..y1) {
                val dy = y + 0.5f - cy
                for (x in x0..x1) {
                    val dx = x + 0.5f - cx
                    val a = (r - sqrt(dx * dx + dy * dy) + 0.5f).coerceIn(0f, 1f)
                    if (a <= 0f) continue
                    val idx = y * w + x
                    if (a >= 1f) {
                        px[idx] = bk
                    } else {
                        val cur = px[idx]
                        val rr = (cur shr 16) and 255
                        val gg = (cur shr 8) and 255
                        val b2 = cur and 255
                        val nr = (rr + (br - rr) * a + 0.5f).toInt()
                        val ng = (gg + (bg - gg) * a + 0.5f).toInt()
                        val nbl = (b2 + (bb - b2) * a + 0.5f).toInt()
                        px[idx] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nbl
                    }
                }
            }
        }
    }
}

/** Colore ARGB da tre quantità di inchiostro C, M, Y (0..100): 0,0,0 = bianco, 100,100,100 = nero. */
fun inkToColor(ink: IntArray): Int {
    fun ch(v: Int) = (255f * (100 - v) / 100f + 0.5f).toInt()
    return (0xFF shl 24) or (ch(ink[0]) shl 16) or (ch(ink[1]) shl 8) or ch(ink[2])
}

/**
 * Soglia per l'effetto a due colori: i pixel con luminosità sotto la soglia diventano neri.
 * Si sceglie la soglia in modo che la quota di pixel neri sia pctNero (0..100).
 */
fun thresholdFor(px: IntArray, pctNero: Int): Int {
    val h = IntArray(256)
    for (c0 in px) {
        val r = (c0 shr 16) and 255
        val g = (c0 shr 8) and 255
        val b = c0 and 255
        h[(299 * r + 587 * g + 114 * b) / 1000]++
    }
    val target = px.size.toLong() * pctNero / 100L
    var acc = 0L
    for (t in 0..255) {
        if (acc >= target) return t
        acc += h[t]
    }
    return 256
}

/** Solo due colori, senza grigi: scuri -> colore del nero, chiari -> colore del bianco. */
fun applyThreshold(px: IntArray, t: Int, black: Int, white: Int) {
    for (i in px.indices) {
        val c0 = px[i]
        val r = (c0 shr 16) and 255
        val g = (c0 shr 8) and 255
        val b = c0 and 255
        val lum = (299 * r + 587 * g + 114 * b) / 1000
        px[i] = (c0 and (0xFF shl 24)) or ((if (lum < t) black else white) and 0xFFFFFF)
    }
}

/**
 * Livelli per trasformare un negativo in positivo: per ogni canale, il punto più scuro e il più chiaro
 * dell'immagine già invertita (si scartano lo 0,5% di pixel estremi, per polvere e bordi).
 * Restituisce [min R, min G, min B, max R, max G, max B].
 */
fun computeLevels(px: IntArray): FloatArray {
    val h = Array(3) { IntArray(256) }
    for (c0 in px) {
        h[0][255 - ((c0 shr 16) and 255)]++
        h[1][255 - ((c0 shr 8) and 255)]++
        h[2][255 - (c0 and 255)]++
    }
    val n = px.size
    val lo = (n * 0.005).toInt()
    val hi = (n * 0.995).toInt()
    val r = FloatArray(6)
    for (c in 0..2) {
        var acc = 0
        var l = 0
        var hh = 255
        var gotLo = false
        for (v in 0..255) {
            acc += h[c][v]
            if (!gotLo && acc > lo) { l = v; gotLo = true }
            if (acc >= hi) { hh = v; break }
        }
        r[c] = l.toFloat()
        r[3 + c] = hh.toFloat()
    }
    return r
}

/** Inverte i valori e stira ogni canale tra il suo minimo e massimo: toglie anche la dominante arancione. */
fun applyPositive(px: IntArray, lv: FloatArray) {
    val s0 = 255f / max(lv[3] - lv[0], 1f)
    val s1 = 255f / max(lv[4] - lv[1], 1f)
    val s2 = 255f / max(lv[5] - lv[2], 1f)
    for (i in px.indices) {
        val c0 = px[i]
        val r = 255 - ((c0 shr 16) and 255)
        val g = 255 - ((c0 shr 8) and 255)
        val b = 255 - (c0 and 255)
        px[i] = (c0 and (0xFF shl 24)) or
            (cl((r - lv[0]) * s0) shl 16) or (cl((g - lv[1]) * s1) shl 8) or cl((b - lv[2]) * s2)
    }
}

/**
 * Scambia i canali e poi regola i colori: 50 = valore originale, 0 = canale spento, 100 = doppio.
 * RGB: si moltiplica la luce del canale. CMY: si moltiplica l'inchiostro.
 * Il cursore c agisce sul canale indicato dalla sua lettera. La luminosità resta quella
 * dell'immagine prima della regolazione: con tutti i canali a 0 si ottiene il bianco e nero.
 * Con i tre canali uguali è la saturazione classica; l'effetto si ferma prima che
 * un canale esca dal campo 0..255, quindi nessun pixel va in saturazione extra.
 */
fun applyStep(px: IntArray, perm: Int, gain: IntArray, cmy: Boolean) {
    val p = PERMS[perm]
    val g = FloatArray(3)
    for (c in 0..2) g[p[c]] = gain[c] / 50f
    val v = FloatArray(3)
    for (i in px.indices) {
        val c0 = px[i]
        v[0] = ((c0 shr 16) and 255).toFloat()
        v[1] = ((c0 shr 8) and 255).toFloat()
        v[2] = (c0 and 255).toFloat()
        val o0 = v[p[0]]
        val o1 = v[p[1]]
        val o2 = v[p[2]]
        val y = 0.299f * o0 + 0.587f * o1 + 0.114f * o2
        val t0: Float
        val t1: Float
        val t2: Float
        if (cmy) {
            t0 = 255f - (255f - o0) * g[0]
            t1 = 255f - (255f - o1) * g[1]
            t2 = 255f - (255f - o2) * g[2]
        } else {
            t0 = o0 * g[0]
            t1 = o1 * g[1]
            t2 = o2 * g[2]
        }
        val d = y - (0.299f * t0 + 0.587f * t1 + 0.114f * t2)
        var u0 = t0 + d
        var u1 = t1 + d
        var u2 = t2 + d
        // se un canale uscirebbe da 0..255, si riduce l'effetto fino al limite
        val e0 = u0 - o0
        val e1 = u1 - o1
        val e2 = u2 - o2
        var a = 1f
        if (u0 > 255f) a = minOf(a, (255f - o0) / e0) else if (u0 < 0f) a = minOf(a, o0 / -e0)
        if (u1 > 255f) a = minOf(a, (255f - o1) / e1) else if (u1 < 0f) a = minOf(a, o1 / -e1)
        if (u2 > 255f) a = minOf(a, (255f - o2) / e2) else if (u2 < 0f) a = minOf(a, o2 / -e2)
        if (a < 1f) {
            u0 = o0 + a * e0
            u1 = o1 + a * e1
            u2 = o2 + a * e2
        }
        px[i] = (c0 and (0xFF shl 24)) or (cl(u0) shl 16) or (cl(u1) shl 8) or cl(u2)
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var pal: PaletteView
    private lateinit var img: ImageView
    private lateinit var hint: TextView
    private lateinit var palBtn: TextView
    private lateinit var settings: LinearLayout
    private lateinit var nOpen: TextView
    private lateinit var nSave: TextView

    private var model = "RGB"
    private var origUri: Uri? = null
    private var imgName = "immagine.png"
    private var mime = "image/png"
    private var pw = 0
    private var ph = 0
    private var prevOrig: IntArray? = null
    private var prevBase: IntArray? = null
    private var lv: FloatArray? = null
    @Volatile private var che = false
    @Volatile private var cheBlack = 50
    private lateinit var cheSwitch: SwitchCompat
    private lateinit var dotsSwitch: SwitchCompat
    @Volatile private var dotsOn = false
    @Volatile private var dotCols = 60
    private val inkW = intArrayOf(0, 0, 0)
    private val inkB = intArrayOf(100, 100, 100)
    @Volatile private var colW = 0xFFFFFFFF.toInt()
    @Volatile private var colB = 0xFF000000.toInt()
    private val history = ArrayList<Step>()

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)

    private val openContract = OpenImage()
    private val openImage = registerForActivityResult(openContract) { uri -> if (uri != null) loadImage(uri) }
    private val pickOpenDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) rememberDir("openDir", u)
    }
    private val pickSaveDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) rememberDir("saveDir", u)
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun msg(t: String) = runOnUiThread { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private fun tv(t: String, f: () -> Unit): TextView = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 15f
        isSingleLine = true
        setPadding(dp(6), dp(10), dp(6), dp(10))
        setOnClickListener { f() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        model = prefs.getString("model", "RGB") ?: "RGB"
        cheBlack = prefs.getInt("cheBlack", 50)
        dotCols = prefs.getInt("dotCols", 60)
        for ((key, arr) in listOf("inkW" to inkW, "inkB" to inkB)) {
            val v = prefs.getString(key, null)?.split(",")?.map { it.toIntOrNull() ?: 0 }
            if (v != null && v.size == 3) for (i in 0..2) arr[i] = v[i].coerceIn(0, 100)
        }
        colW = inkToColor(inkW)
        colB = inkToColor(inkB)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(4), 0, dp(4), 0)
        }
        bar.addView(tv("\u2699") { settings.visibility = if (settings.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            .apply { textSize = 24f })
        bar.addView(tv("apri") { openClicked() })
        bar.addView(tv("salva") { save() })
        bar.addView(tv("azzera") { resetAll() })
        palBtn = tv("palette RGB") { setPalette(pal.visibility != View.VISIBLE) }
        bar.addView(palBtn)

        val stage = FrameLayout(this)
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        img.setOnClickListener { setPalette(false); settings.visibility = View.GONE }
        stage.addView(img, FrameLayout.LayoutParams(-1, -1))
        hint = TextView(this).apply {
            text = "Tocca \u00abapri\u00bb per scegliere un'immagine."
            setTextColor(0xFF999999.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
        }
        stage.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        pal = PaletteView(this).apply { visibility = View.GONE }
        pal.onPickPerm = { i -> pickPerm(i) }
        pal.onGain = { requestRender() }
        stage.addView(pal, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF808080.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            visibility = View.GONE
        }
        settings.addView(TextView(this).apply { text = "Palette dell'immagine"; setTextColor(Color.WHITE); textSize = 16f })
        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbRgb = RadioButton(this).apply { text = "RGB"; id = View.generateViewId() }
        val rbCmy = RadioButton(this).apply { text = "CMY"; id = View.generateViewId() }
        rg.addView(rbRgb)
        rg.addView(rbCmy)
        (if (model == "RGB") rbRgb else rbCmy).isChecked = true
        rg.setOnCheckedChangeListener { _, id -> setModel(if (id == rbRgb.id) "RGB" else "CMY") }
        settings.addView(rg)
        settings.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "Negativo \u2192 positivo"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("negative", false)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("negative", on).apply()
                rebuildBase()
            }
        })
        val cheBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cheLabel = TextView(this).apply { setTextColor(Color.WHITE); text = "Nero: $cheBlack" }
        val cheSeek = SeekBar(this).apply {
            max = 100
            progress = cheBlack
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    cheBlack = p
                    cheLabel.text = "Nero: $p"
                    if (fromUser) requestRender()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putInt("cheBlack", cheBlack).apply()
                }
            })
        }
        cheBox.addView(cheLabel)
        cheBox.addView(cheSeek)

        // colori del bianco e del nero: si sceglie il cerchio, poi si regolano C, M, Y (0..100)
        val colorBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        var sel = 0
        val dotW = View(this)
        val dotB = View(this)
        val inkLabels = ArrayList<TextView>()
        val inkBars = ArrayList<SeekBar>()
        fun cur() = if (sel == 0) inkW else inkB
        fun refreshDots() {
            fun d(col: Int, on: Boolean) = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(col)
                setStroke(dp(3), if (on) 0xFF00FF00.toInt() else Color.WHITE)
            }
            dotW.background = d(colW, sel == 0)
            dotB.background = d(colB, sel == 1)
        }
        fun showInks() {
            val a = cur()
            for (c in 0..2) {
                inkBars[c].progress = a[c]
                inkLabels[c].text = "${"CMY"[c]}: ${a[c]}"
            }
            refreshDots()
        }
        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        fun lab(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); setPadding(dp(6), 0, dp(16), 0) }
        dots.addView(dotW, LinearLayout.LayoutParams(dp(30), dp(30)))
        dots.addView(lab("Bianco"))
        dots.addView(dotB, LinearLayout.LayoutParams(dp(30), dp(30)))
        dots.addView(lab("Nero"))
        dotW.setOnClickListener { sel = 0; showInks() }
        dotB.setOnClickListener { sel = 1; showInks() }
        colorBox.addView(dots)
        for (c in 0..2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val l = TextView(this).apply { setTextColor(Color.WHITE); minWidth = dp(54) }
            val b = SeekBar(this).apply {
                max = 100
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        cur()[c] = p
                        l.text = "${"CMY"[c]}: $p"
                        if (sel == 0) colW = inkToColor(inkW) else colB = inkToColor(inkB)
                        refreshDots()
                        requestRender()
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        prefs.edit().putString("inkW", inkW.joinToString(","))
                            .putString("inkB", inkB.joinToString(",")).apply()
                    }
                })
            }
            inkLabels.add(l)
            inkBars.add(b)
            row.addView(l)
            row.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            colorBox.addView(row)
        }
        showInks()

        // retino a pallini: numero di colonne della griglia (10..200)
        val dotsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val dotsLabel = TextView(this).apply { setTextColor(Color.WHITE); text = "Colonne: $dotCols" }
        val dotsSeek = SeekBar(this).apply {
            max = 190
            progress = dotCols - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    dotCols = p + 10
                    dotsLabel.text = "Colonne: $dotCols"
                    if (fromUser) requestRender()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    prefs.edit().putInt("dotCols", dotCols).apply()
                }
            })
        }
        dotsBox.addView(dotsLabel)
        dotsBox.addView(dotsSeek)
        dotsSwitch = SwitchCompat(this).apply {
            text = "Retino a pallini"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on ->
                dotsOn = on
                dotsBox.visibility = if (on) View.VISIBLE else View.GONE
                colorBox.visibility = if (che || dotsOn) View.VISIBLE else View.GONE
                requestRender()
            }
        }
        cheSwitch = SwitchCompat(this).apply {
            text = "Effetto Che Guevara"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, on ->
                che = on
                cheBox.visibility = if (on) View.VISIBLE else View.GONE
                colorBox.visibility = if (che || dotsOn) View.VISIBLE else View.GONE
                requestRender()
            }
        }
        settings.addView(cheSwitch)
        settings.addView(cheBox)
        settings.addView(dotsSwitch)
        settings.addView(dotsBox)
        settings.addView(colorBox)
        settings.addView(Button(this).apply {
            text = "Cartella per apri"
            setOnClickListener { pickOpenDir.launch(prefs.getString("openDir", null)?.let { Uri.parse(it) }) }
        })
        nOpen = TextView(this).apply { setTextColor(Color.WHITE) }
        settings.addView(nOpen)
        settings.addView(Button(this).apply {
            text = "Cartella per salva"
            setOnClickListener { pickSaveDir.launch(prefs.getString("saveDir", null)?.let { Uri.parse(it) }) }
        })
        nSave = TextView(this).apply { setTextColor(Color.WHITE) }
        settings.addView(nSave)
        val settingsScroll = android.widget.ScrollView(this)
        settingsScroll.addView(settings, ViewGroup.LayoutParams(-1, -2))
        stage.addView(settingsScroll, FrameLayout.LayoutParams(dp(300), -2, Gravity.TOP or Gravity.START))

        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        applyModel()
        refreshNames()
    }

    private fun setPalette(open: Boolean) {
        pal.visibility = if (open) View.VISIBLE else View.GONE
        palBtn.setTextColor(if (open) 0xFF00E000.toInt() else Color.WHITE)
    }

    // ---------- impostazioni ----------
    private fun setModel(m: String) {
        model = m
        prefs.edit().putString("model", m).apply()
        pal.resetValues()
        applyModel()
        requestRender()
    }

    private fun applyModel() {
        if (model == "RGB") {
            pal.letters = arrayOf("R", "G", "B")
            pal.colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        } else {
            pal.letters = arrayOf("C", "M", "Y")
            pal.colors = intArrayOf(0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(), 0xFFFFFF00.toInt())
        }
        palBtn.text = "palette $model"
        pal.invalidate()
    }

    private fun rememberDir(key: String, u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        prefs.edit().putString(key, u.toString()).apply()
        refreshNames()
    }

    private fun dirName(key: String): String {
        val s = prefs.getString(key, null) ?: return "non scelta"
        return try {
            DocumentFile.fromTreeUri(this, Uri.parse(s))?.name ?: "cartella"
        } catch (e: Exception) {
            "cartella"
        }
    }

    private fun refreshNames() {
        nOpen.text = dirName("openDir")
        nSave.text = dirName("saveDir")
    }

    // ---------- immagini ----------
    private fun loadBitmap(uri: Uri, maxDim: Int): Bitmap {
        val cr = contentResolver
        var sample = 1
        if (maxDim > 0) {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
            while (max(o.outWidth, o.outHeight) / sample > maxDim) sample *= 2
        }
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sample
        o2.inMutable = true
        val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) }!!
        val ori = cr.openInputStream(uri)!!.use {
            try {
                android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            } catch (e: Exception) {
                1
            }
        }
        val m = Matrix()
        when (ori) {
            3 -> m.postRotate(180f)
            6 -> m.postRotate(90f)
            8 -> m.postRotate(270f)
            2 -> m.postScale(-1f, 1f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun openClicked() {
        openContract.initial = try {
            prefs.getString("openDir", null)?.let {
                val t = Uri.parse(it)
                DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            }
        } catch (e: Exception) {
            null
        }
        openImage.launch(arrayOf("image/*"))
    }

    private fun loadImage(uri: Uri) {
        Thread {
            try {
                val b = loadBitmap(uri, 1600)
                val px = IntArray(b.width * b.height)
                b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                val nm = DocumentFile.fromSingleUri(this, uri)?.name ?: "immagine.png"
                val mt = contentResolver.getType(uri) ?: "image/png"
                runOnUiThread {
                    origUri = uri; imgName = nm; mime = mt
                    pw = b.width; ph = b.height
                    prevOrig = px; lv = computeLevels(px); prevBase = basePixels(px)
                    history.clear()
                    pal.perm = 0; pal.resetValues(); pal.invalidate()
                    hint.visibility = View.GONE
                    cheSwitch.isChecked = false
                    dotsSwitch.isChecked = false
                    requestRender()
                }
            } catch (e: Throwable) {
                msg("Impossibile aprire l'immagine")
            }
        }.start()
    }

    private fun requestRender() {
        dirty.set(true)
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try {
                while (dirty.getAndSet(false)) {
                    val base = prevBase ?: break
                    try {
                        val out = base.copyOf()
                        applyStep(out, pal.perm, pal.gain.copyOf(), model == "CMY")
                        if (che) applyThreshold(out, thresholdFor(out, cheBlack), colB, colW)
                        if (dotsOn) applyDots(out, pw, ph, dotCols, colB, colW)
                        val bmp = Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888)
                        runOnUiThread { img.setImageBitmap(bmp) }
                    } catch (e: Exception) {
                    }
                }
            } finally {
                busy.set(false)
                if (dirty.get()) requestRender()
            }
        }
    }

    /** Ogni scambio parte sempre dall'immagine originale; toccando un pallino i cursori tornano a 100. */
    private fun pickPerm(i: Int) {
        pal.perm = i
        pal.resetValues()
        pal.invalidate()
        requestRender()
    }

    /** Immagine di partenza per l'anteprima: l'originale, o il suo positivo se l'interruttore è acceso. */
    private fun basePixels(o: IntArray): IntArray {
        val b = o.copyOf()
        val l = lv
        if (prefs.getBoolean("negative", false) && l != null) applyPositive(b, l)
        return b
    }

    private fun rebuildBase() {
        val o = prevOrig ?: return
        prevBase = basePixels(o)
        requestRender()
    }

    private fun resetAll() {
        val o = prevOrig ?: return
        history.clear()
        prevBase = basePixels(o)
        pal.perm = 0
        pal.resetValues()
        pal.invalidate()
        requestRender()
    }

    // ---------- salvataggio ----------
    private fun save() {
        val uri = origUri ?: return
        val sd = prefs.getString("saveDir", null)
        if (sd == null) {
            msg("Scegli prima la cartella per salva")
            settings.visibility = View.VISIBLE
            return
        }
        val sp = pal.perm
        val sg = pal.gain.copyOf()
        val cm = model == "CMY"
        val neg = prefs.getBoolean("negative", false)
        val lvs = lv
        val chOn = che
        val chPct = cheBlack
        val cB = colB
        val cW = colW
        val dOn = dotsOn
        val dCols = dotCols
        val pb = prevBase
        val steps = ArrayList(history)
        val letters = if (model == "RGB") "RGB" else "CMY"
        val nm = imgName
        val mt = mime
        msg("Salvataggio in corso...")
        Thread {
            try {
                var bmp = loadBitmap(uri, 0)
                if (!bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
                val w = bmp.width
                val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                if (neg && lvs != null) applyPositive(px, lvs)
                for (s in steps) applyStep(px, s.perm, s.gain, s.cmy)
                applyStep(px, sp, sg, cm)
                if (chOn) {
                    // la soglia si calcola sull'anteprima, così il file salvato è identico a ciò che vedi
                    val tmp = pb!!.copyOf()
                    applyStep(tmp, sp, sg, cm)
                    val tt = thresholdFor(tmp, chPct)
                    applyThreshold(px, tt, cB, cW)
                }
                if (dOn) applyDots(px, w, h, dCols, cB, cW)
                bmp.setPixels(px, 0, w, 0, 0, w, h)

                val fmt: Bitmap.CompressFormat
                val outMime: String
                when (if (chOn || dOn) "image/png" else mt) {
                    "image/jpeg" -> { fmt = Bitmap.CompressFormat.JPEG; outMime = mt }
                    "image/webp" -> {
                        fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        outMime = mt
                    }
                    else -> { fmt = Bitmap.CompressFormat.PNG; outMime = "image/png" }
                }
                fun label(p: Int) = PERMS[p].map { letters[it] }.joinToString("")
                val base = nm.substringBeforeLast('.', nm)
                val origExt = if (nm.contains('.')) "." + nm.substringAfterLast('.') else ""
                val ext = if (outMime == mt && origExt.isNotEmpty()) origExt else ".png"
                val hist = steps.filter { it.perm != 0 }.joinToString("") { "_" + label(it.perm) }
                val fn = base + (if (neg) "_pos" else "") + (if (chOn) "_che" else "") + (if (dOn) "_retino" else "") + hist + "_" + label(sp) + ext

                val dir = DocumentFile.fromTreeUri(this, Uri.parse(sd))!!
                dir.findFile(fn)?.delete()
                val f = dir.createFile(outMime, fn)!!
                contentResolver.openOutputStream(f.uri)!!.use { bmp.compress(fmt, 95, it) }
                msg("Salvato: $fn")
            } catch (e: Throwable) {
                msg("Salvataggio non riuscito")
            }
        }.start()
    }
}
