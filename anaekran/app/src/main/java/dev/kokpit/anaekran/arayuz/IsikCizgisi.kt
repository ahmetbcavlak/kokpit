package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import dev.kokpit.anaekran.tema.Palet

/**
 * The accent glow above the bottom bar (design y 861–931) and the 2 px light line at y 931.
 * Static: shaders are built on size/theme/mode change only, nothing animates or invalidates.
 * Not clickable, so touches fall through to the screen below.
 *
 * The line points at what needs attention, as in the references:
 *  - [Mod.NORMAL] Y-2/Y-3/Y-5: accent, centred (30–50–70 %), glow 0.14, line 0.8;
 *  - [Mod.KAPI]   Y-4: accent under the door (57–71–85 %), glow 0.28, line 1;
 *  - [Mod.BAGAJ]  Y-10: accent under the boot (63–77–91 %), glow 0.28, line 1;
 *  - [Mod.ALARM]  Y-14 (door/boot open while driving): danger, nearly full width (12–88 %),
 *    glow 0.25, line 1.
 */
class IsikCizgisi(context: Context) : View(context) {

    enum class Mod { NORMAL, KAPI, BAGAJ, ALARM }

    private val parilti = Paint()
    private val cizgi = Paint()
    private val matris = Matrix()
    private var palet = Palet.GECE
    var mod: Mod = Mod.NORMAL
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun temaUygula(p: Palet) {
        if (p.acc == palet.acc && p.danger == palet.danger) return
        palet = p
        golgeleriKur(width, height)
        invalidate()
    }

    fun modUygula(m: Mod) {
        if (m == mod) return
        mod = m
        golgeleriKur(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = golgeleriKur(w, h)

    private fun golgeleriKur(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val renk = if (mod == Mod.ALARM) palet.danger else palet.acc
        val bos = renk and 0x00FFFFFF
        val merkez = when (mod) {
            Mod.KAPI -> 0.71f
            Mod.BAGAJ -> 0.77f
            else -> 0.5f
        }
        val (parlaklik, cizgiOpak) = when (mod) {
            Mod.NORMAL -> 0.14f to 0.8f
            Mod.ALARM -> 0.25f to 1f
            else -> 0.28f to 1f
        }
        val glowH = h - uf(2)
        // radial-gradient(40% 100% at <merkez> 100%): an ellipse with radii 0.4·w and the glow height,
        // centred on the line. One shader with its real radius, stretched vertically by the local matrix.
        val rx = w * 0.4f
        parilti.shader = RadialGradient(0f, 0f, rx, saydam(renk, parlaklik), bos, Shader.TileMode.CLAMP).apply {
            matris.reset()
            matris.setScale(1f, glowH / rx)
            matris.postTranslate(w * merkez, glowH)
            setLocalMatrix(matris)
        }
        val tam = saydam(renk, cizgiOpak)
        val (renkler, yerler) = if (mod == Mod.ALARM) {
            intArrayOf(bos, tam, tam, bos) to floatArrayOf(0f, 0.12f, 0.88f, 1f)
        } else {
            val yari = if (mod == Mod.NORMAL) 0.2f else 0.14f
            intArrayOf(bos, bos, tam, bos, bos) to floatArrayOf(0f, merkez - yari, merkez, merkez + yari, 1f)
        }
        cizgi.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, renkler, yerler, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cizgiY = height - uf(2)
        canvas.drawRect(0f, 0f, w, cizgiY, parilti)
        canvas.drawRect(0f, cizgiY, w, height.toFloat(), cizgi)
    }

    companion object {
        /** Design px: glow 70 + line 2 (tokens.json isik_cizgisi_ust 931, alt_cubuk_ust 932 overlaps by 1). */
        const val YUKSEKLIK = 72
    }
}
