package dev.kokpit.anaekran.ekran

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView
import dev.kokpit.anaekran.R
import dev.kokpit.anaekran.arayuz.Cam
import dev.kokpit.anaekran.arayuz.MutlakDuzen
import dev.kokpit.anaekran.arayuz.metin
import dev.kokpit.anaekran.arayuz.renkle
import dev.kokpit.anaekran.arayuz.u
import dev.kokpit.anaekran.arayuz.yaz
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Yazi
import java.util.EnumSet

/** A calm one-off hint on the park screen about something the app needs from the user. */
enum class Ipucu(val metin: Int) {
    /** The door service is off: door/boot warnings cannot arrive. */
    ERISILEBILIRLIK(R.string.kapi_servisi_ipucu),

    /** No location permission: no GPS speed, no park/drive switch. */
    KONUM(R.string.konum_izni_ipucu),
}

/**
 * Which hints the user has closed in this process ("aynı oturumda tekrar gösterme"): a hint
 * that was closed, or tapped to open Settings, is not shown again until the app process
 * restarts. Nothing is written to disk. Main thread only.
 */
object Ipuclari {
    private val kapatilan = EnumSet.noneOf(Ipucu::class.java)

    fun kapat(i: Ipucu) {
        kapatilan.add(i)
    }

    fun kapatildi(i: Ipucu) = i in kapatilan

    /** The hint to show now, one at a time (door service first), or null. */
    fun sec(erisilebilirlikEtkin: Boolean, konumIzni: Boolean): Ipucu? = when {
        !erisilebilirlikEtkin && !kapatildi(Ipucu.ERISILEBILIRLIK) -> Ipucu.ERISILEBILIRLIK
        !konumIzni && !kapatildi(Ipucu.KONUM) -> Ipucu.KONUM
        else -> null
    }

    /** Tests only. */
    internal fun sifirla() = kapatilan.clear()
}

/**
 * The hint card: a glass card (720×88 at the park screen's free lower area), info icon, the
 * hint text and an X. Tapping the card opens the matching Settings page (an intent only —
 * nothing is written); the X just closes it. Both close it for the session.
 */
@SuppressLint("ViewConstructor")
class IpucuKarti(context: Context, private val dokunuldu: (Ipucu, ayarlariAc: Boolean) -> Unit) : MutlakDuzen(context) {

    var ipucu: Ipucu? = null
        private set

    private val simge = ImageView(context).apply {
        setImageResource(R.drawable.ic_bilgi)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val yazi = context.metin(Yazi.sans, 16, Palet.GECE.tx).apply {
        maxLines = 2
        setLineSpacing(0f, 1.25f)
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    val kapatDugmesi = ImageView(context).apply {
        setImageResource(R.drawable.ic_kapat)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(u(22), u(22), u(22), u(22))
        contentDescription = context.getString(R.string.kapat)
        setOnClickListener { ipucu?.let { dokunuldu(it, false) } }
    }

    init {
        isClickable = true
        setOnClickListener { ipucu?.let { dokunuldu(it, true) } }
        ekle(simge, 24, 32, 24, 24)
        ekle(yazi, 64, 0, 568, 88)
        ekle(kapatDugmesi, 644, 6, 76, 76)
        visibility = GONE
    }

    fun goster(i: Ipucu?) {
        ipucu = i
        if (i == null) {
            visibility = GONE
            return
        }
        yazi.yaz(context.getString(i.metin))
        contentDescription = "${context.getString(i.metin)} ${context.getString(R.string.ipucu_ac)}"
        visibility = VISIBLE
    }

    fun tema(p: Palet) {
        background = RippleDrawable(ColorStateList.valueOf(p.hair), Cam.arka(p, 22f), null)
        yazi.renkle(p.tx)
        simge.imageTintList = ColorStateList.valueOf(p.acc)
        kapatDugmesi.imageTintList = ColorStateList.valueOf(p.mu)
    }
}
