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

class Step(val perm: Int, val gain: IntArray)

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
 * Scambia i canali e poi agisce solo sul colore: gain 0 = grigio, 128 = invariato, 256 = doppio.
 * Il cursore c agisce sul canale di uscita PERMS[perm][c]; la luminosità resta costante.
 */
fun applyStep(px: IntArray, perm: Int, gain: IntArray) {
    val p = PERMS[perm]
    val g = FloatArray(3)
    for (c in 0..2) g[p[c]] = gain[c] / 128f
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
        val t0 = y + g[0] * (o0 - y)
        val t1 = y + g[1] * (o1 - y)
        val t2 = y + g[2] * (o2 - y)
        val d = y - (0.299f * t0 + 0.587f * t1 + 0.114f * t2)
        px[i] = (c0 and (0xFF shl 24)) or (cl(t0 + d) shl 16) or (cl(t1 + d) shl 8) or cl(t2 + d)
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
        textSize = 16f
        setPadding(dp(8), dp(10), dp(8), dp(10))
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
        palBtn = tv("palette RGB") {
            val open = pal.visibility != View.VISIBLE
            pal.visibility = if (open) View.VISIBLE else View.GONE
            palBtn.setTextColor(if (open) 0xFF00E000.toInt() else Color.WHITE)
        }
        bar.addView(palBtn)

        val stage = FrameLayout(this)
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
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

    // ---------- impostazioni ----------
    private fun setModel(m: String) {
        model = m
        prefs.edit().putString("model", m).apply()
        pal.gain.fill(128)
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
                    prevOrig = px; prevBase = px.copyOf()
                    history.clear()
                    pal.perm = 0; pal.gain.fill(128); pal.invalidate()
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
                        applyStep(out, pal.perm, pal.gain.copyOf())
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

    /** Scegliendo un nuovo scambio, l'immagine attuale diventa la nuova base: gli scambi si sommano. */
    private fun pickPerm(i: Int) {
        if (i == pal.perm) return
        val base = prevBase
        if (base != null) {
            if (!(pal.perm == 0 && pal.gain.all { it == 128 })) history.add(Step(pal.perm, pal.gain.copyOf()))
            val nb = base.copyOf()
            applyStep(nb, pal.perm, pal.gain.copyOf())
            prevBase = nb
        }
        pal.perm = i
        pal.gain.fill(128)
        pal.invalidate()
        requestRender()
    }

    private fun resetAll() {
        val o = prevOrig ?: return
        history.clear()
        prevBase = o.copyOf()
        pal.perm = 0
        pal.gain.fill(128)
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
                for (s in steps) applyStep(px, s.perm, s.gain)
                applyStep(px, sp, sg)
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
                val fn = base + hist + "_" + label(sp) + ext

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
