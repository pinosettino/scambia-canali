package it.scambiacanali

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private fun cl(x: Float): Int = if (x < 0f) 0 else if (x > 255f) 255 else (x + 0.5f).toInt()

/**
 * Retino a pallini, filtro indipendente: lavora sulla luminosità dell'immagine che arriva dai filtri
 * precedenti. Griglia di celle quadrate (cols colonne), un pallino al centro di ogni cella: più la
 * cella è scura, più il pallino è grande (cella nera = pallino che riempie la cella e si fonde con
 * i vicini, cella bianca = nessun pallino). Pallini nel colore del nero, sfondo nel colore del bianco.
 * Con inverse = true è il contrario: più la cella è chiara, più il pallino è grande; pallini nel
 * colore del bianco su sfondo nel colore del nero.
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

fun applyDots(px: IntArray, w: Int, h: Int, cols: Int, black: Int, white: Int, inverse: Boolean = false) {
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
    val dotCol = if (inverse) white else black
    val bgCol = if (inverse) black else white
    java.util.Arrays.fill(px, (0xFF shl 24) or (bgCol and 0xFFFFFF))
    val bk = (0xFF shl 24) or (dotCol and 0xFFFFFF)
    val br = (dotCol shr 16) and 255
    val bg = (dotCol shr 8) and 255
    val bb = dotCol and 255
    val rt = FloatArray(256) { dotRadius(it / 255.0) }
    for (j in 0 until rows) {
        for (i in 0 until cols) {
            val k = j * cols + i
            if (nt[k] == 0) continue
            val mean = sum[k].toFloat() / (nt[k] * 255f)
            val dark = if (inverse) mean else 1f - mean
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


/** Colore ARGB da tre valori R, G, B espressi da 0 a 100. */
fun rgb100ToArgb(v: IntArray): Int =
    (0xFF shl 24) or (((v[0] * 255 + 50) / 100) shl 16) or (((v[1] * 255 + 50) / 100) shl 8) or ((v[2] * 255 + 50) / 100)

/** Fotografia dello stato di tutti i filtri: serve per usare le stesse impostazioni nell'anteprima e nel salvataggio. */
class Fx(
    val neg: Boolean, val lv: FloatArray?,
    val pal: Boolean, val perm: Int, val gain: IntArray, val cmy: Boolean,
    val che: Boolean, val cheBlack: Int, val cheB: Int, val cheW: Int,
    val dkb: Boolean, val dkbCols: Int, val dkbB: Int, val dkbW: Int,
    val dkw: Boolean, val dkwCols: Int, val dkwB: Int, val dkwW: Int
) {
    val needsPng: Boolean get() = che || dkb || dkw
}

/**
 * Applica i filtri in ordine fisso, ognuno sul risultato del precedente:
 * negativo -> positivo, palette foto, effetto Che, retino a pallini neri, retino a pallini bianchi.
 * cheT: soglia dell'effetto Che già calcolata (salvataggio); se null si calcola qui (anteprima).
 */
fun runFx(px: IntArray, w: Int, h: Int, fx: Fx, cheT: Int?) {
    val lv = fx.lv
    if (fx.neg && lv != null) applyPositive(px, lv)
    if (fx.pal) applyStep(px, fx.perm, fx.gain, fx.cmy)
    if (fx.che) applyThreshold(px, cheT ?: thresholdFor(px, fx.cheBlack), fx.cheB, fx.cheW)
    if (fx.dkb) applyDots(px, w, h, fx.dkbCols, fx.dkbB, fx.dkbW, false)
    if (fx.dkw) applyDots(px, w, h, fx.dkwCols, fx.dkwB, fx.dkwW, true)
}

/** Soglia dell'effetto Che calcolata sull'anteprima: il file salvato usa la stessa. */
fun cheThreshold(preview: IntArray, fx: Fx): Int {
    val tmp = preview.copyOf()
    val lv = fx.lv
    if (fx.neg && lv != null) applyPositive(tmp, lv)
    if (fx.pal) applyStep(tmp, fx.perm, fx.gain, fx.cmy)
    return thresholdFor(tmp, fx.cheBlack)
}
