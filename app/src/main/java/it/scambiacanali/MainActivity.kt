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
        stage.addView(settings, FrameLayout.LayoutParams(dp(300), -2, Gravity.TOP or Gravity.START))

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
                bmp.setPixels(px, 0, w, 0, 0, w, h)

                val fmt: Bitmap.CompressFormat
                val outMime: String
                when (mt) {
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
                val fn = base + (if (neg) "_pos" else "") + hist + "_" + label(sp) + ext

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
