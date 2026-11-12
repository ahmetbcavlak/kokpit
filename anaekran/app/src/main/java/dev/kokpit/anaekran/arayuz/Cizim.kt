package dev.kokpit.anaekran.arayuz

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * A full-size, non-interactive view that draws the reference's SVG layer (guide lines, ruler,
 * "+" marks, the speed arc…) in design pixels. The owner calls [invalidate] only when what it
 * draws changed (data or theme), so a still screen costs one display-list replay per frame.
 */
@SuppressLint("ViewConstructor")
class Cizim(context: Context, private val ciz: (Canvas) -> Unit) : View(context) {
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val s = canvas.save()
        canvas.scale(Olcek.oran, Olcek.oran)
        ciz(canvas)
        canvas.restoreToCount(s)
    }
}

/**
 * The SVG primitives of the references, in design px. Paints are reused (main thread only).
 * Colours always come from the palette, so day and night share the code.
 */
object Cizgiler {

    private val cizgi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dolgu = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val yazi = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val yol = Path()
    private val NOKTALI_1_5 = DashPathEffect(floatArrayOf(1f, 5f), 0f)
    private val NOKTALI_2_4 = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    private val matris = Matrix()

    private fun kalem(renk: Int, kalinlik: Float, kesik: DashPathEffect? = null): Paint = cizgi.apply {
        color = renk
        strokeWidth = kalinlik
        pathEffect = kesik
        shader = null
    }

    /** `<svg … width=14 height=14><path d="M7,0V14M0,7H14" stroke=hud opacity .55/>` at (x, y). */
    fun arti(c: Canvas, p: Palet, x: Float, y: Float) {
        val k = kalem(saydam(p.hud, 0.55f), 1f)
        c.drawLine(x + 7f, y, x + 7f, y + 14f, k)
        c.drawLine(x, y + 7f, x + 14f, y + 7f, k)
    }

    /** Y-2 ruler: dotted line 40–728 at [y] (hud .35, dash 1 5) and 17 ticks y±3 every 43 px (hud .5). */
    fun cetvel(c: Canvas, p: Palet, y: Float) {
        c.drawLine(40f, y, 728f, y, kalem(saydam(p.hud, 0.35f), 1f, NOKTALI_1_5))
        val k = kalem(saydam(p.hud, 0.5f), 1f)
        var x = 40f
        while (x <= 728.5f) {
            c.drawLine(x, y - 3f, x, y + 3f, k)
            x += 43f
        }
    }

    /**
     * Y-2 guide line: from the anchor [nokta] up (or down) to [cizgiY], then across to the label
     * edge [ucX]; a ring (r 4, 1.2) and a dot (r 1.4) on the anchor. [vurgu] (open door/boot):
     * accent, line opacity .9 (Y-4/Y-10); otherwise hud, line opacity .6.
     */
    fun kilavuz(c: Canvas, p: Palet, nokta: PointF, cizgiY: Float, ucX: Float, vurgu: Boolean) {
        val renk = if (vurgu) p.acc else p.hud
        yol.rewind()
        yol.moveTo(nokta.x, nokta.y)
        yol.lineTo(nokta.x, cizgiY)
        yol.lineTo(ucX, cizgiY)
        c.drawPath(yol, kalem(saydam(renk, if (vurgu) 0.9f else 0.6f), 1f, NOKTALI_2_4))
        c.drawCircle(nokta.x, nokta.y, 4f, kalem(renk, 1.2f))
        dolgu.color = renk
        dolgu.shader = null
        c.drawCircle(nokta.x, nokta.y, 1.4f, dolgu)
    }

    /** Y-14: ring on the car where the open door/boot is (r 26, danger .18 fill, 1.5 stroke, dot r 4). */
    fun halka(c: Canvas, p: Palet, n: PointF) {
        dolgu.shader = null
        dolgu.color = saydam(p.danger, 0.18f)
        c.drawCircle(n.x, n.y, 26f, dolgu)
        c.drawCircle(n.x, n.y, 26f, kalem(p.danger, 1.5f))
        dolgu.color = p.danger
        c.drawCircle(n.x, n.y, 4f, dolgu)
    }

    /** CSS `radial-gradient(50% 50% at 50% 50%, renk, transparent)` with [opaklik] in [r]. */
    fun isilti(c: Canvas, r: RectF, renk: Int, opaklik: Float) {
        val rx = r.width() / 2f
        val g = RadialGradient(0f, 0f, rx, saydam(renk, opaklik), renk and 0x00FFFFFF, Shader.TileMode.CLAMP)
        matris.reset()
        matris.setScale(1f, r.height() / r.width())
        matris.postTranslate(r.centerX(), r.centerY())
        g.setLocalMatrix(matris)
        dolgu.shader = g
        c.drawRect(r, dolgu)
        dolgu.shader = null
    }

    /** Y-1 light: glow (acc .22) above [y] and a 2 px line tx 22 % → acc 60 % → transparent. */
    fun acilisIsigi(c: Canvas, p: Palet, y: Float) {
        isilti(c, RectF(384f - 307.2f, y - 70f, 384f + 307.2f, y + 70f), p.acc, 0.22f)
        val bos = p.tx and 0x00FFFFFF
        dolgu.shader = LinearGradient(0f, 0f, 768f, 0f, intArrayOf(bos, p.tx, p.acc, p.acc and 0x00FFFFFF), floatArrayOf(0f, 0.22f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, y, 768f, y + 2f, dolgu)
        dolgu.shader = null
    }

    /**
     * Y-3 speed lines behind the car's tail: 1 px lines every 12 px in (568, 421, 200, 157),
     * faded in from the left and out to the right (CSS mask: transparent → black 20 % → transparent).
     */
    fun hizCizgileri(c: Canvas, p: Palet) {
        val renk = if (p.gunduz) android.graphics.Color.argb(56, 40, 60, 100) else android.graphics.Color.argb(77, 150, 180, 240)
        dolgu.shader = LinearGradient(568f, 0f, 768f, 0f, intArrayOf(renk and 0x00FFFFFF, renk, renk and 0x00FFFFFF), floatArrayOf(0f, 0.2f, 1f), Shader.TileMode.CLAMP)
        var y = 421f + 11f
        while (y < 421f + 157f) {
            c.drawRect(568f, y, 768f, y + 1f, dolgu)
            y += 12f
        }
        dolgu.shader = null
    }

    // --- Y-3 speed arc: circle centre (384, 884), r 720, 0–180 km/h over ±27.28° ---

    private const val YAY_CX = 384f
    private const val YAY_CY = 884f
    private const val YAY_R = 720f
    const val YAY_EN_COK = 180.0
    private val YAY_ACI = asin(330.0 / 720.0) // half the sweep, radians (endpoints x 54 and 714)
    private val yayKutusu = RectF(YAY_CX - YAY_R, YAY_CY - YAY_R, YAY_CX + YAY_R, YAY_CY + YAY_R)

    /** Angle from vertical (radians, left negative) for a speed. */
    private fun aci(v: Double) = -YAY_ACI + (v.coerceIn(0.0, YAY_EN_COK) / YAY_EN_COK) * 2 * YAY_ACI

    private fun nx(a: Double, r: Float) = YAY_CX + (r * sin(a)).toFloat()
    private fun ny(a: Double, r: Float) = YAY_CY - (r * cos(a)).toFloat()

    /** Arc, ticks every 5 km/h (labels every 20), progress and dot for [hiz]; null = no progress. */
    fun hizYayi(c: Canvas, p: Palet, hiz: Double?) {
        val basla = Math.toDegrees(-Math.PI / 2 - YAY_ACI).toFloat()
        val tarama = Math.toDegrees(2 * YAY_ACI).toFloat()
        c.drawArc(yayKutusu, basla, tarama, false, kalem(saydam(p.hud, 0.35f), 1f))
        yazi.typeface = Yazi.mono
        yazi.textSize = 12f
        yazi.color = p.hud
        var v = 0
        while (v <= YAY_EN_COK) {
            val a = aci(v.toDouble())
            val buyuk = v % 20 == 0
            val dolu = hiz != null && v <= hiz
            val renk = when {
                dolu -> saydam(p.acc, 0.95f)
                buyuk -> saydam(p.hud, 0.7f)
                else -> saydam(p.hud, 0.4f)
            }
            val dis = if (buyuk) 736f else 728f
            c.drawLine(nx(a, 722f), ny(a, 722f), nx(a, dis), ny(a, dis), kalem(renk, 1.2f))
            if (buyuk) c.drawText(v.toString(), nx(a, 754f), ny(a, 754f) + 4f, yazi)
            v += 5
        }
        if (hiz == null) return
        val a = aci(hiz)
        c.drawArc(yayKutusu, basla, Math.toDegrees(a + YAY_ACI).toFloat(), false, kalem(p.acc, 2f))
        dolgu.shader = null
        dolgu.color = saydam(p.acc, 0.18f)
        c.drawCircle(nx(a, YAY_R), ny(a, YAY_R), 11f, dolgu)
        dolgu.color = p.acc
        c.drawCircle(nx(a, YAY_R), ny(a, YAY_R), 4f, dolgu)
    }

    // --- Y-5 temperature scale: 80–688 at y 470, 16–28 °C in 0.5 steps (25.33 px) ---

    private const val OLCEK_SOL = 80f
    private const val OLCEK_SAG = 688f
    private const val OLCEK_Y = 470f
    private const val OLCEK_ALT = 16.0
    private const val OLCEK_UST = 28.0

    /** x of a set temperature on the scale; LOW/HI are the ends. Null = nothing to mark. */
    fun olcekX(derece: Double): Float =
        OLCEK_SOL + ((derece.coerceIn(OLCEK_ALT, OLCEK_UST) - OLCEK_ALT) / (OLCEK_UST - OLCEK_ALT) * (OLCEK_SAG - OLCEK_SOL)).toFloat()

    fun sicaklikOlcegi(c: Canvas, p: Palet, isaretX: Float?) {
        c.drawLine(OLCEK_SOL, OLCEK_Y, OLCEK_SAG, OLCEK_Y, kalem(saydam(p.hud, 0.35f), 1f))
        yazi.typeface = Yazi.mono
        yazi.textSize = 12f
        yazi.color = p.hud
        for (i in 0..24) {
            val x = OLCEK_SOL + i * (OLCEK_SAG - OLCEK_SOL) / 24f
            val buyuk = i % 4 == 0
            val dolu = isaretX != null && x <= isaretX + 0.5f
            val renk = when {
                dolu -> saydam(p.acc, 0.95f)
                buyuk -> saydam(p.hud, 0.7f)
                else -> saydam(p.hud, 0.4f)
            }
            c.drawLine(x, if (buyuk) OLCEK_Y - 12f else OLCEK_Y - 6f, x, OLCEK_Y, kalem(renk, 1.2f))
            if (buyuk && i in 4..20) c.drawText((16 + i / 2).toString(), x, 494f, yazi)
        }
        yazi.textAlign = Paint.Align.RIGHT
        c.drawText("LOW", 66f, 475f, yazi)
        yazi.textAlign = Paint.Align.LEFT
        c.drawText("HI", 702f, 475f, yazi)
        yazi.textAlign = Paint.Align.CENTER
        if (isaretX == null) return
        c.drawLine(OLCEK_SOL, OLCEK_Y, isaretX, OLCEK_Y, kalem(p.acc, 2f))
        dolgu.shader = null
        dolgu.color = saydam(p.acc, 0.18f)
        c.drawCircle(isaretX, OLCEK_Y, 11f, dolgu)
        dolgu.color = p.acc
        c.drawCircle(isaretX, OLCEK_Y, 4f, dolgu)
    }

    /** Y-5 fan bars: 8 bars in [r], heights 14 + 3·i bottom-aligned, gap 10; the first [fan] lit. */
    fun fanCubuklari(c: Canvas, p: Palet, r: RectF, fan: Int?) {
        val bosluk = 10f
        val w = (r.width() - bosluk * 7) / 8f
        for (i in 0 until 8) {
            val x = r.left + i * (w + bosluk)
            val h = 14f + 3f * i
            dolgu.shader = null
            dolgu.color = if (fan != null && i < fan) p.acc else saydam(p.hud, 0.3f)
            c.drawRoundRect(x, r.bottom - h, x + w, r.bottom, 3f, 3f, dolgu)
        }
    }
}

/**
 * The reference's glass cards without live blur (rules: no blur on AC8227L): a vertical
 * gradient from the highlight (`ghi` over `glass`) to `glass`, and a 1 px `gline` edge.
 * [ton]: a tinted card (Y-14 danger, confirmation ok) — the tint at [tonUst]/[tonAlt] over the base.
 */
object Cam {
    fun arka(p: Palet, yaricap: Float, ton: Int? = null, tonUst: Float = 0f, tonAlt: Float = 0f): Drawable {
        // Y-14's tinted card sits on rgba(20,8,10,.55) behind an 18 px blur; without the blur the
        // arc and ticks would show through, so the base is denser here (.82) to keep the text clean.
        val taban = when {
            ton == null -> p.glass
            p.gunduz -> ustune(android.graphics.Color.argb(150, 255, 255, 255), p.glass)
            else -> android.graphics.Color.argb(209, 20, 8, 10)
        }
        val ust = if (ton != null) ustune(saydam(ton, tonUst), taban) else ustune(p.ghi, p.glass)
        val alt = if (ton != null) ustune(saydam(ton, tonAlt), taban) else p.glass
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(ust, alt)).apply {
            cornerRadius = uf(yaricap)
            setStroke(u(1).coerceAtLeast(1), p.gline)
        }
    }
}
