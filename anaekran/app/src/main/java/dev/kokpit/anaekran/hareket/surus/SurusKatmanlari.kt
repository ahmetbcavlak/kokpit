package dev.kokpit.anaekran.hareket.surus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/*
 * The drive scene's small custom views. All draw in design px inside [SurusSahnesi]'s design
 * container, allocate nothing in onDraw, never overlap themselves (hasOverlappingRendering
 * false: an alpha on them is folded into each draw, no offscreen layer — research decision 2)
 * and take no touches.
 */

/** A view of the drive scene: not focusable, not for accessibility, no touches. */
@SuppressLint("ViewConstructor")
abstract class SurusKatmani(context: Context) : View(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun hasOverlappingRendering() = false
}

/** Absolute container in design px (no scaling of its own coordinates). */
open class SurusKabi(context: Context) : ViewGroup(context) {
    class Yer(val x: Int, val y: Int, w: Int, h: Int) : LayoutParams(w, h)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Children reach outside (the body glow, the camera scale): never clip them to our box.
        clipChildren = false
        clipToPadding = false
    }

    fun ekle(v: View, x: Int, y: Int, w: Int, h: Int): View {
        addView(v, Yer(x, y, w, h))
        return v
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as Yer
            c.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as Yer
            c.layout(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height)
        }
    }

    override fun checkLayoutParams(p: LayoutParams?) = p is Yer
    override fun generateDefaultLayoutParams(): LayoutParams = Yer(0, 0, 0, 0)
    override fun shouldDelayChildPressedState() = false
    override fun hasOverlappingRendering() = false
}

/**
 * One dashed lane line (3 m dash + 6 m gap) moving to the right. The fade at both screen edges
 * is a gradient fixed in screen space (the paint's shader), so only the dash rectangles move:
 * no shader matrix per frame.
 */
internal class SeritCizgisi(olcek: Float, opaklik: Float) {
    val periyot = SurusFormul.seritPeriyodu(olcek)
    val dolu = SurusFormul.seritDolu(olcek)
    private val boya = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // demo: rgba(220,226,236,.55) × the line's opacity
        val c = Color.argb(Math.round(255 * 0.55f * opaklik), 220, 226, 236)
        val s = c and 0x00FFFFFF
        shader = LinearGradient(
            0f, 0f, SurusYerlesim.TASARIM_W.toFloat(), 0f,
            intArrayOf(s, c, c, s),
            floatArrayOf(0f, SurusYerlesim.SERIT_KENAR, 1f - SurusYerlesim.SERIT_KENAR, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Draws the dashes for [ofset] (px to the right, in [0; periyot)) between y [ust] and [ust]+[h]. */
    fun ciz(c: Canvas, ofset: Float, ust: Float, h: Float) {
        var x = ofset - periyot
        val son = SurusYerlesim.TASARIM_W.toFloat()
        while (x < son) {
            if (x + dolu > 0f) c.drawRect(x, ust, x + dolu, ust + h, boya)
            x += periyot
        }
    }
}

/**
 * Behind the car, bottom to top (demo z-index 1, DOM order): distant city lights, far lane
 * line. Bounds: y 447 (lights) … 587 (far line), full width.
 * Lights: each dot of the 600 px tile is drawn on its own (a small source rectangle of the tile,
 * at a fractional x with bitmap filtering: smooth sub-pixel motion), its alpha × the demo's
 * edge mask (`linear-gradient(90deg, transparent, #000 15%, #000 85%, transparent)`) taken at
 * the dot's centre. Per dot instead of per pixel (a dot is ~16 px wide): no shader matrix per
 * frame, no layer — ≤ 8 small bitmap draws.
 */
@SuppressLint("ViewConstructor")
class ArkaKatman(context: Context) : SurusKatmani(context) {
    private val ust = SurusYerlesim.ISIK_Y.toFloat()
    private val uzak = SeritCizgisi(SurusFormul.UZAK_OLCEK, SurusFormul.UZAK_OPAKLIK)
    private val isikBoya = Paint(Paint.FILTER_BITMAP_FLAG)
    private val kaynak = android.graphics.Rect()
    private val hedef = android.graphics.RectF()

    var isik: Bitmap? = null
    var isikOfset = 0f
    var isikOpakligi = 1f
    var uzakOfset = 0f

    override fun onDraw(c: Canvas) {
        val b = isik
        if (b != null && isikOpakligi > 0f) {
            val d = SurusYerlesim.ISIK_DESEN.toFloat()
            val w = SurusYerlesim.TASARIM_W.toFloat()
            var x0 = isikOfset - d
            while (x0 < w) {
                for (i in 0 until SurusVarliklari.ISIK_SAYISI) {
                    val cx = x0 + SurusVarliklari.isikX(i)
                    val yari = SurusVarliklari.isikYaricap(i)
                    if (cx + yari <= 0f || cx - yari >= w) continue
                    val maske = kenarMaskesi(cx / w)
                    if (maske <= 0f) continue
                    val sx = SurusVarliklari.isikX(i)
                    kaynak.set((sx - yari).toInt(), 0, (sx + yari).toInt(), SurusYerlesim.ISIK_H)
                    hedef.set(x0 + kaynak.left, 0f, x0 + kaynak.right, SurusYerlesim.ISIK_H.toFloat())
                    isikBoya.alpha = Math.round(255 * (isikOpakligi * maske).coerceIn(0f, 1f))
                    c.drawBitmap(b, kaynak, hedef, isikBoya)
                }
                x0 += d
            }
        }
        uzak.ciz(c, uzakOfset, SurusYerlesim.UZAK_Y - ust, SurusYerlesim.UZAK_H)
    }

    companion object {
        const val Y = SurusYerlesim.ISIK_Y
        val H = Math.round(SurusYerlesim.UZAK_Y + SurusYerlesim.UZAK_H) - SurusYerlesim.ISIK_Y

        /** Demo mask: 0 at the edges, 1 from 15 % to 85 % of the width. */
        fun kenarMaskesi(x: Float): Float = when {
            x <= 0f || x >= 1f -> 0f
            x < 0.15f -> x / 0.15f
            x > 0.85f -> (1f - x) / 0.15f
            else -> 1f
        }
    }
}

/** In front of the car (demo z-index 3): the near lane line. */
@SuppressLint("ViewConstructor")
class OnKatman(context: Context) : SurusKatmani(context) {
    private val yakin = SeritCizgisi(1f, SurusFormul.YAKIN_OPAKLIK)
    var ofset = 0f

    override fun onDraw(c: Canvas) = yakin.ciz(c, ofset, SurusYerlesim.YAKIN_Y - Y, SurusYerlesim.YAKIN_H)

    companion object {
        val Y = floor(SurusYerlesim.YAKIN_Y).toInt()
        val H = Math.round(SurusYerlesim.YAKIN_Y + SurusYerlesim.YAKIN_H) - Y
    }
}

/**
 * Speed lines behind the tail: a 1 px line every 12 px, rgba(150,180,240,.30), fading in over
 * the first 20 % of the box and out to the right (demo). Static content; its opacity is the
 * view's alpha (no re-recording per frame).
 */
@SuppressLint("ViewConstructor")
class IzKatmani(context: Context) : SurusKatmani(context) {
    private val boya = Paint().apply {
        val c = Color.argb(Math.round(255 * 0.30f), 150, 180, 240)
        val s = c and 0x00FFFFFF
        shader = LinearGradient(0f, 0f, SurusYerlesim.IZ_W.toFloat(), 0f, intArrayOf(s, c, s), floatArrayOf(0f, 0.2f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        var y = SurusYerlesim.IZ_ARALIK - 1
        while (y < SurusYerlesim.IZ_H) {
            c.drawRect(0f, y.toFloat(), SurusYerlesim.IZ_W.toFloat(), y + 1f, boya)
            y += SurusYerlesim.IZ_ARALIK
        }
    }
}

/**
 * Lamp reflection: a warm band (rgba(255,214,160,.38) peak, 34 % of the body box wide, skewed
 * −18°) crossing the body front → back every 35 m, only where the body is (its alpha), SCREEN
 * blended (research decision 4):
 *   ComposeShader(BitmapShader(body), LinearGradient(band), SRC_IN) + xfermode SCREEN,
 * one drawRect limited to band ∩ body, and only while the band is on the body (18 % of the
 * distance). The view sits exactly on the body bitmap, so the BitmapShader needs no matrix; the
 * band moves through the gradient's local matrix (one reused Matrix).
 * If ComposeShader turns out broken or slow on the head unit's GPU, research gives two fall-backs
 * (a saveLayer limited to the body box, or an unmasked SCREEN band after design approval).
 */
@SuppressLint("ViewConstructor")
class YansimaKatmani(context: Context) : SurusKatmani(context) {
    private val egim = tan(Math.toRadians(SurusFormul.LAMBA_EGIM_DERECE.toDouble())).toFloat()
    private val bantW = SurusYerlesim.KUTU_W * SurusFormul.LAMBA_GENISLIK

    /** Band centre line (demo skew origin: the band's centre = the body box's centre), local y. */
    private val merkezY = SurusYerlesim.KUTU_Y + SurusYerlesim.KUTU_H / 2f - SurusYerlesim.GOVDE_Y
    private val matris = Matrix()
    private val bant = LinearGradient(
        0f, 0f, bantW, 0f,
        intArrayOf(RENK and 0x00FFFFFF, RENK, RENK and 0x00FFFFFF),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val boya = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private var govde: Bitmap? = null

    /** Band left edge as a share of the body box width; NaN = no band. */
    var konum: Float = Float.NaN

    /** 0…1, multiplies the band (0 at a standstill). */
    var gorunurluk = 0f

    /** Last drawn band rectangle (tests), local px; empty when nothing was drawn. */
    internal val sonDikdortgen = android.graphics.RectF()

    fun govdeAyarla(b: Bitmap) {
        if (b === govde) return
        govde = b
        // dst = body (its alpha is the mask), src = band: SRC_IN → band × body alpha.
        boya.shader = ComposeShader(BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP), bant, PorterDuff.Mode.SRC_IN)
    }

    /** Drops the body bitmap (birak): no shader keeps it alive. */
    fun govdeBirak() {
        govde = null
        boya.shader = null
    }

    /** True when [konum] and [gorunurluk] give something to draw. */
    val ciziyor: Boolean get() = govde != null && !konum.isNaN() && gorunurluk > 0f

    override fun onDraw(c: Canvas) {
        sonDikdortgen.setEmpty()
        val k = konum
        if (k.isNaN() || govde == null || gorunurluk <= 0f) return
        val sol = SurusYerlesim.KUTU_X + k * SurusYerlesim.KUTU_W - SurusYerlesim.GOVDE_X
        // gradient space x 0…bantW → local x = sol + x + egim·(y − merkezY)
        matris.setTranslate(sol, 0f)
        matris.postSkew(egim, 0f, 0f, merkezY)
        bant.setLocalMatrix(matris)
        val h = height.toFloat()
        val kayma0 = egim * (0f - merkezY)
        val kayma1 = egim * (h - merkezY)
        val x0 = max(0f, sol + min(kayma0, kayma1))
        val x1 = min(width.toFloat(), sol + bantW + max(kayma0, kayma1))
        if (x1 <= x0) return
        boya.alpha = Math.round(255 * gorunurluk.coerceIn(0f, 1f))
        c.drawRect(x0, 0f, x1, h, boya)
        sonDikdortgen.set(x0, 0f, x1, h)
    }

    companion object {
        /** rgba(255,214,160,.38): the demo's band peak. */
        val RENK = Color.argb(Math.round(255 * SurusFormul.LAMBA_OPAKLIK), 255, 214, 160)
    }
}
