package it.scambiacanali

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

class OpenImage : ActivityResultContracts.OpenDocument() {
    var initial: Uri? = null
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val i = super.createIntent(context, input)
        initial?.let { i.putExtra("android.provider.extra.INITIAL_URI", it) }
        return i
    }
}

/** Anteprima ridotta dell'immagine originale (pixel ARGB). */
class Prev(val px: IntArray, val w: Int, val h: Int)

/** Stato di un filtro con pannello: acceso/spento, colonne, soglia del nero e i suoi due colori (valori R,G,B 0..100). */
class FState(
    var on: Boolean = false,
    var cols: Int = 60,
    var black: Int = 50,
    val w: IntArray = intArrayOf(100, 100, 100),
    val b: IntArray = intArrayOf(0, 0, 0)
)

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var pal: PaletteView
    private lateinit var img: ImageView
    private lateinit var hint: TextView
    private lateinit var settingsView: View

    private val overlays = ArrayList<View>()
    private val refreshers = ArrayList<() -> Unit>()
    private val menuSync = ArrayList<() -> Unit>()
    private var syncing = false

    private var model = "RGB"
    private var palOn = true
    private var negOn = false
    private val che = FState(black = 50)
    private val dkb = FState()
    private val dkw = FState()

    private var origUri: Uri? = null
    private var imgName = "immagine.png"
    private var mime = "image/png"
    @Volatile private var prev: Prev? = null
    @Volatile private var lv: FloatArray? = null

    private val exec = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val latest = AtomicReference<Fx?>(null)

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

    // ---------- costruzione dell'interfaccia ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        model = prefs.getString("model", "RGB") ?: "RGB"
        negOn = prefs.getBoolean("negative", false)
        loadState("che", che)
        loadState("dkb", dkb)
        loadState("dkw", dkw)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
        }
        val stage = FrameLayout(this)

        img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        img.setOnClickListener { showOnly(null) }
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

        val screenW = resources.displayMetrics.widthPixels
        val settingsP = buildSettings()
        settingsView = settingsP
        val panelChe = buildPanel("che", "Effetto Che Guevara", che, withBlack = true, withCols = false)
        val panelDkb = buildPanel("dkb", "Retino a pallini nero", dkb, withBlack = false, withCols = true)
        val panelDkw = buildPanel("dkw", "Retino a pallini bianco", dkw, withBlack = false, withCols = true)
        val menuP = buildMenu(panelChe, panelDkb, panelDkw)

        addOverlay(stage, pal, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END, 0)
        addOverlay(stage, settingsP, (screenW * 0.62f).toInt(), Gravity.TOP or Gravity.START, 6)
        addOverlay(stage, menuP, (screenW * 0.62f).toInt(), Gravity.TOP or Gravity.END, 6)
        for (p in listOf(panelChe, panelDkb, panelDkw)) addOverlay(stage, p, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP, 8)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(6), 0, dp(6), 0)
        }
        bar.addView(tv("\u2699") { toggle(settingsP) }.apply { textSize = 28f; setTextColor(0xFFCCCCCC.toInt()) })
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(tv("apri") { openClicked() })
        bar.addView(tv("salva") { save() })
        bar.addView(tv("menu \u2630") { toggle(menuP) })

        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        applyModel()
        refreshers.forEach { it() }
    }

    private fun tv(t: String, f: () -> Unit): TextView = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 20f
        isSingleLine = true
        setPadding(dp(10), dp(12), dp(10), dp(12))
        setOnClickListener { f() }
    }

    private fun addOverlay(stage: FrameLayout, v: View, w: Int, gravity: Int, margin: Int) {
        val lp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT, gravity)
        lp.setMargins(dp(margin), dp(margin), dp(margin), dp(margin))
        stage.addView(v, lp)
        overlays.add(v)
    }

    /** Mostra solo il pannello indicato (o nessuno). */
    private fun showOnly(v: View?) {
        for (o in overlays) o.visibility = View.GONE
        v?.visibility = View.VISIBLE
    }

    private fun toggle(v: View) {
        if (v.visibility == View.VISIBLE) showOnly(null) else showOnly(v)
    }

    private fun SeekBar.cyan() {
        progressTintList = ColorStateList.valueOf(Color.CYAN)
        thumbTintList = ColorStateList.valueOf(Color.CYAN)
        progressBackgroundTintList = ColorStateList.valueOf(Color.WHITE)
    }

    private fun seek(maxV: Int, start: Int, onChange: (Int, Boolean) -> Unit, onStop: () -> Unit): SeekBar =
        SeekBar(this).apply {
            max = maxV
            progress = start
            cyan()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p, fromUser)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) = onStop()
            })
        }

    // ---------- impostazioni (ingranaggio) ----------
    private fun buildSettings(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
            setBackgroundColor(0xFFE6E6E6.toInt())
            visibility = View.GONE
        }
        val dotC = View(this)
        val dotR = View(this)
        fun dot(v: View, on: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) 0xFF00FF00.toInt() else Color.WHITE)
                setStroke(dp(3), Color.BLACK)
            }
        }
        fun big(t: String, pad: Int) = TextView(this).apply {
            text = t
            setTextColor(Color.BLACK)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, dp(pad), 0)
        }
        val labC = big("CMY", 22)
        val labR = big("RGB", 0)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(dotC, LinearLayout.LayoutParams(dp(32), dp(32)))
        row.addView(labC)
        row.addView(dotR, LinearLayout.LayoutParams(dp(32), dp(32)))
        row.addView(labR)
        for (v in listOf<View>(dotC, labC)) v.setOnClickListener { setModel("CMY") }
        for (v in listOf<View>(dotR, labR)) v.setOnClickListener { setModel("RGB") }
        box.addView(row)
        refreshers.add { dot(dotC, model == "CMY"); dot(dotR, model == "RGB") }

        fun folder(title: String, key: String, launch: () -> Unit) {
            val t = TextView(this).apply {
                text = title
                textSize = 22f
                setTextColor(Color.BLACK)
                setPadding(0, dp(20), 0, 0)
            }
            val p = TextView(this).apply { textSize = 16f; setTextColor(0xFF333333.toInt()) }
            t.setOnClickListener { launch() }
            p.setOnClickListener { launch() }
            box.addView(t)
            box.addView(p)
            refreshers.add { p.text = "(" + folderLabel(key) + ")" }
        }
        folder("CARTELLA APRI", "openDir") { pickOpenDir.launch(prefs.getString("openDir", null)?.let { Uri.parse(it) }) }
        folder("CARTELLA SALVA", "saveDir") { pickSaveDir.launch(prefs.getString("saveDir", null)?.let { Uri.parse(it) }) }
        return box
    }

    private fun folderLabel(key: String): String {
        val s = prefs.getString(key, null) ?: return "DCIM/fotolab"
        return try {
            DocumentsContract.getTreeDocumentId(Uri.parse(s)).substringAfter(':')
        } catch (e: Exception) {
            "cartella scelta"
        }
    }

    private fun rememberDir(key: String, u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        prefs.edit().putString(key, u.toString()).apply()
        refreshers.forEach { it() }
    }

    private fun setModel(m: String) {
        model = m
        prefs.edit().putString("model", m).apply()
        pal.resetValues()
        applyModel()
        refreshers.forEach { it() }
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
        pal.invalidate()
    }

    // ---------- menu (≡) ----------
    private fun styleSwitch(s: SwitchCompat) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        s.thumbTintList = ColorStateList(states, intArrayOf(Color.CYAN, 0xFFBBBBBB.toInt()))
        s.trackTintList = ColorStateList(states, intArrayOf(0xFF2A7F7F.toInt(), 0xFF666666.toInt()))
    }

    private fun menuRow(box: LinearLayout, label: String, get: () -> Boolean, set: (Boolean) -> Unit, panel: View?) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        val sw = SwitchCompat(this)
        styleSwitch(sw)
        sw.isChecked = get()
        sw.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            set(on)
            requestRender()
            if (panel != null) {
                if (on) showOnly(panel) else panel.visibility = View.GONE
            }
        }
        val t = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(10), dp(4), 0, dp(4))
        }
        t.setOnClickListener { if (panel != null) toggle(panel) }
        row.addView(sw)
        row.addView(t)
        box.addView(row)
        menuSync.add { sw.isChecked = get() }
    }

    private fun buildMenu(panelChe: View, panelDkb: View, panelDkw: View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()) }
            visibility = View.GONE
        }
        menuRow(box, "PALETTE FOTO", { palOn }, { palOn = it }, pal)
        menuRow(box, "NEGATIVO -> POSITIVO", { negOn }, {
            negOn = it
            prefs.edit().putBoolean("negative", it).apply()
        }, null)
        menuRow(box, "EFFETTO CHE GUEVARA", { che.on }, { che.on = it }, panelChe)
        menuRow(box, "RETINO A PALLINI NERI", { dkb.on }, { dkb.on = it }, panelDkb)
        menuRow(box, "RETINO A PALLINI BIANCHI", { dkw.on }, { dkw.on = it }, panelDkw)
        val az = TextView(this).apply {
            text = "AZZERA"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(16), 0, dp(4))
            setOnClickListener { resetAll(); showOnly(null) }
        }
        box.addView(az)
        return box
    }

    private fun syncMenu() {
        syncing = true
        menuSync.forEach { it() }
        syncing = false
    }

    // ---------- pannelli dei filtri ----------
    private fun saveState(key: String, f: FState) {
        prefs.edit()
            .putInt("$key.cols", f.cols)
            .putInt("$key.black", f.black)
            .putString("$key.w", f.w.joinToString(","))
            .putString("$key.b", f.b.joinToString(","))
            .apply()
    }

    private fun loadState(key: String, f: FState) {
        f.cols = prefs.getInt("$key.cols", f.cols).coerceIn(10, 200)
        f.black = prefs.getInt("$key.black", f.black).coerceIn(0, 100)
        for ((suffix, arr) in listOf("w" to f.w, "b" to f.b)) {
            val v = prefs.getString("$key.$suffix", null)?.split(",")?.map { it.toIntOrNull() ?: 0 }
            if (v != null && v.size == 3) for (i in 0..2) arr[i] = v[i].coerceIn(0, 100)
        }
    }

    private fun buildPanel(key: String, title: String, f: FState, withBlack: Boolean, withCols: Boolean): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()) }
            visibility = View.GONE
        }
        fun lab(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = 18f }
        box.addView(lab(title).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD })

        if (withBlack) {
            val l = lab("Nero: ${f.black}")
            box.addView(l)
            box.addView(seek(100, f.black, { p, user ->
                f.black = p
                l.text = "Nero: $p"
                if (user) requestRender()
            }, { saveState(key, f) }))
        }
        if (withCols) {
            val l = lab("Colonne: ${f.cols}")
            box.addView(l)
            box.addView(seek(190, f.cols - 10, { p, user ->
                f.cols = p + 10
                l.text = "Colonne: ${f.cols}"
                if (user) requestRender()
            }, { saveState(key, f) }))
        }

        // i due colori del filtro: si sceglie il cerchio, poi si regolano i tre cursori
        var sel = 0
        val dotW = View(this)
        val dotB = View(this)
        val bars = ArrayList<SeekBar>()
        val labels = ArrayList<TextView>()
        fun cur() = if (sel == 0) f.w else f.b
        fun letters() = if (model == "CMY") "CMY" else "RGB"
        fun shown(v: Int) = if (model == "CMY") 100 - v else v
        fun ring(col: Int, on: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(col)
            setStroke(dp(4), if (on) 0xFF00FF00.toInt() else Color.WHITE)
        }
        fun refreshDots() {
            dotW.background = ring(rgb100ToArgb(f.w), sel == 0)
            dotB.background = ring(rgb100ToArgb(f.b), sel == 1)
        }
        fun refresh() {
            val a = cur()
            for (c in 0..2) {
                bars[c].progress = shown(a[c])
                labels[c].text = "${letters()[c]}:${shown(a[c])}"
            }
            refreshDots()
        }

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(6))
        }
        row.addView(dotW, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(lab("Bianco").apply { setPadding(dp(12), 0, dp(28), 0) })
        row.addView(dotB, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(lab("Nero").apply { setPadding(dp(12), 0, 0, 0) })
        dotW.setOnClickListener { sel = 0; refresh() }
        dotB.setOnClickListener { sel = 1; refresh() }
        box.addView(row)

        for (c in 0..2) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val l = lab("").apply { minWidth = dp(76); typeface = Typeface.DEFAULT_BOLD }
            val s = seek(100, 0, { p, user ->
                if (user) {
                    cur()[c] = if (model == "CMY") 100 - p else p
                    l.text = "${letters()[c]}:$p"
                    refreshDots()
                    requestRender()
                }
            }, { saveState(key, f) })
            labels.add(l)
            bars.add(s)
            r.addView(l)
            r.addView(s, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(r)
        }
        refreshers.add { refresh() }
        return box
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
        showOnly(null)
        openContract.initial = try {
            val chosen = prefs.getString("openDir", null)
            if (chosen != null) {
                val t = Uri.parse(chosen)
                DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
            } else {
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:DCIM/fotolab")
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
                    origUri = uri
                    imgName = nm
                    mime = mt
                    lv = computeLevels(px)
                    prev = Prev(px, b.width, b.height)
                    resetFilters()
                    hint.visibility = View.GONE
                    requestRender()
                }
            } catch (e: Throwable) {
                msg("Impossibile aprire l'immagine")
            }
        }.start()
    }

    /** Palette neutra, effetto Che e retini spenti. Il negativo resta com'è: è un'impostazione che si cambia dal menu. */
    private fun resetFilters() {
        pal.perm = 0
        pal.resetValues()
        palOn = true
        che.on = false
        dkb.on = false
        dkw.on = false
        syncMenu()
    }

    private fun resetAll() {
        if (prev == null) return
        resetFilters()
        requestRender()
    }

    private fun pickPerm(i: Int) {
        pal.perm = i
        pal.resetValues()
        requestRender()
    }

    // ---------- calcolo dell'anteprima ----------
    private fun makeFx() = Fx(
        neg = negOn, lv = lv,
        pal = palOn, perm = pal.perm, gain = pal.gain.copyOf(), cmy = model == "CMY",
        che = che.on, cheBlack = che.black, cheB = rgb100ToArgb(che.b), cheW = rgb100ToArgb(che.w),
        dkb = dkb.on, dkbCols = dkb.cols, dkbB = rgb100ToArgb(dkb.b), dkbW = rgb100ToArgb(dkb.w),
        dkw = dkw.on, dkwCols = dkw.cols, dkwB = rgb100ToArgb(dkw.b), dkwW = rgb100ToArgb(dkw.w)
    )

    private fun requestRender() {
        latest.set(makeFx())
        kick()
    }

    private fun kick() {
        if (!busy.compareAndSet(false, true)) return
        exec.execute {
            try {
                while (true) {
                    val fx = latest.getAndSet(null) ?: break
                    val pv = prev ?: break
                    try {
                        val out = pv.px.copyOf()
                        runFx(out, pv.w, pv.h, fx, null)
                        val bmp = Bitmap.createBitmap(out, pv.w, pv.h, Bitmap.Config.ARGB_8888)
                        runOnUiThread { img.setImageBitmap(bmp) }
                    } catch (e: Exception) {
                    }
                }
            } finally {
                busy.set(false)
                if (latest.get() != null) kick()
            }
        }
    }

    // ---------- salvataggio ----------
    private fun save() {
        showOnly(null)
        val uri = origUri ?: return
        val pv = prev ?: return
        val sd = prefs.getString("saveDir", null)
        if (sd == null && Build.VERSION.SDK_INT < 29) {
            msg("Scegli prima la cartella per salva")
            showOnly(settingsView)
            return
        }
        val fx = makeFx()
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
                val cheT = if (fx.che) cheThreshold(pv.px, fx) else null
                runFx(px, w, h, fx, cheT)
                bmp.setPixels(px, 0, w, 0, 0, w, h)

                val fmt: Bitmap.CompressFormat
                val outMime: String
                when (if (fx.needsPng) "image/png" else mt) {
                    "image/jpeg" -> { fmt = Bitmap.CompressFormat.JPEG; outMime = "image/jpeg" }
                    "image/webp" -> {
                        fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                        outMime = "image/webp"
                    }
                    else -> { fmt = Bitmap.CompressFormat.PNG; outMime = "image/png" }
                }
                val base = nm.substringBeforeLast('.', nm)
                val origExt = if (nm.contains('.')) "." + nm.substringAfterLast('.') else ""
                val ext = if (outMime == mt && origExt.isNotEmpty()) origExt else when (outMime) {
                    "image/jpeg" -> ".jpg"
                    "image/webp" -> ".webp"
                    else -> ".png"
                }
                val tags = ArrayList<String>()
                if (fx.neg) tags.add("pos")
                if (fx.pal && (fx.perm != 0 || fx.gain.any { it != 50 })) tags.add(PERMS[fx.perm].map { letters[it] }.joinToString(""))
                if (fx.che) tags.add("che")
                if (fx.dkb) tags.add("retino")
                if (fx.dkw) tags.add("retinobianco")
                val fn = base + "_" + (if (tags.isEmpty()) "copia" else tags.joinToString("_")) + ext

                if (sd != null) {
                    val dir = DocumentFile.fromTreeUri(this, Uri.parse(sd))!!
                    dir.findFile(fn)?.delete()
                    val f = dir.createFile(outMime, fn)!!
                    contentResolver.openOutputStream(f.uri)!!.use { bmp.compress(fmt, 95, it) }
                } else {
                    // senza cartella scelta: DCIM/fotolab, senza bisogno di permessi (Android 10 e successivi)
                    val cv = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fn)
                        put(MediaStore.Images.Media.MIME_TYPE, outMime)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/fotolab")
                    }
                    val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
                    contentResolver.openOutputStream(out)!!.use { bmp.compress(fmt, 95, it) }
                }
                msg("Salvato: $fn")
            } catch (e: Throwable) {
                msg("Salvataggio non riuscito")
            }
        }.start()
    }
}
