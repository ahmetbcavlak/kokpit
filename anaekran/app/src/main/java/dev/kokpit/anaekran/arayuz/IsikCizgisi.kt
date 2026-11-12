package dev.kokpit.anaekran.arayuz

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import dev.kokpit.anaekran.tema.Palet

/**
 * The accent glow above the bottom bar (design y 861–931) and the 2 px light line at y 931.
 * Static: shaders are built on size/theme change only, nothing animates or invalidates.
 * Not clickable, so touches fall through to the screen below.
 */
class IsikCizgisi(context: Context) : View(context) {

    private val parilti = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cizgi = Paint()
    private var renk = Palet.GECE.acc

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun temaUygula(p: Palet) {
        if (p.acc == renk) return
        renk = p.acc
        golgeleriKur(width, height)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = golgeleriKur(w, h)

    private fun golgeleriKur(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val bos = renk and 0x00FFFFFF
        // radial-gradient(40% 100% at 50% 100%, acc, transparent), opacity .14 — drawn in a unit circle, scaled.
        parilti.shader = RadialGradient(0f, 0f, 1f, renkAlfa(renk, 0.14f), bos, Shader.TileMode.CLAMP)
        // linear-gradient(90deg, transparent 30%, acc 50%, transparent 70%), opacity .8
        cizgi.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(bos, bos, renkAlfa(renk, 0.8f), bos, bos),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cizgiY = height - uf(2)
        canvas.save()
        canvas.clipRect(0f, 0f, w, cizgiY)
        canvas.translate(w / 2f, cizgiY)
        canvas.scale(w * 0.4f, cizgiY)
        canvas.drawCircle(0f, 0f, 1f, parilti)
        canvas.restore()
        canvas.drawRect(0f, cizgiY, w, height.toFloat(), cizgi)
    }

    private fun renkAlfa(c: Int, a: Float) = Color.argb(Math.round(Color.alpha(c) * a), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        /** Design px: glow 70 + line 2 (tokens.json isik_cizgisi_ust 931, alt_cubuk_ust 932 overlaps by 1). */
        const val YUKSEKLIK = 72
    }
}
