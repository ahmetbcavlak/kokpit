package dev.kokpit.anaekran

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.Context
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.kokpit.anaekran.arayuz.Arkaplan
import dev.kokpit.anaekran.ekran.Ipuclari
import dev.kokpit.anaekran.hareket.HareketBaglayici
import dev.kokpit.anaekran.hareket.KareKaynagi
import dev.kokpit.anaekran.hareket.Oynatici
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.time.Duration
import java.util.concurrent.Executor

/** Shared set-up for the Robolectric tests. */
object TestYardim {

    /**
     * Worker-thread jobs run inline; their main-thread results wait for [bosalt]. Also wires
     * MainActivity with the real motion hook on a fake player (no MediaPlayer, no Choreographer
     * loop) and without the data hook (no GPS timeouts rewriting the test's data), and forgets
     * the closed hints.
     */
    fun esZamanli() {
        Arkaplan.yurutucu = Executor { it.run() }
        dev.kokpit.anaekran.uygulama.UygulamaListesi.yurutucu = Executor { it.run() }
        oynaticilar.clear()
        Baglayicilar.uret = { listOf(sahteHareket()) }
        Ipuclari.sifirla()
    }

    /** A player that never plays: KlipOynatici skips it after 1.5 s and fades. */
    class SahteOynatici(c: Context) : Oynatici {
        override val gorunum = View(c)
        var klip = 0
        var birakildi = false
        var dinleyici: Oynatici.Dinleyici? = null
        override fun hazirla(klipHam: Int, d: Oynatici.Dinleyici) { klip = klipHam; dinleyici = d }
        override fun birak(): Long { birakildi = true; return 0L }
    }

    /** Every fake player created since [esZamanli], oldest first. */
    val oynaticilar = ArrayList<SahteOynatici>()

    fun sahteHareket() = HareketBaglayici(
        oynaticiUret = { SahteOynatici(it).also { o -> oynaticilar += o } },
        kareKaynagi = object : KareKaynagi {
            override fun iste(cb: Choreographer.FrameCallback) {}
            override fun birak(cb: Choreographer.FrameCallback) {}
        },
    )

    fun bosalt() = shadowOf(Looper.getMainLooper()).idle()

    fun bekle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    fun ac(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { bosalt() }

    /** Opens the activity and cuts the intro (tap), so the base screen is in front. */
    fun acVeGec(): ActivityController<MainActivity> = ac().also {
        it.get().hareket?.kes()
        bekle(600)
    }

    /** Lets every clip / fade / transition finish. */
    fun gecisleriBitir() = bekle(3000)

    /** Visible texts under [v] (visibility checked up the tree inside [v]). */
    fun gorunurMetinler(v: View): List<String> {
        val out = ArrayList<String>()
        fun gez(x: View) {
            if (x.visibility != View.VISIBLE) return
            if (x is TextView) out += x.text.toString()
            if (x is ViewGroup) for (i in 0 until x.childCount) gez(x.getChildAt(i))
        }
        gez(v)
        return out
    }

    /** Installs a fake app with one launcher activity. */
    fun uygulamaKur(paket: String, ad: String) {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val ai = ApplicationInfo().apply { packageName = paket; nonLocalizedLabel = ad }
        val act = ActivityInfo().apply {
            packageName = paket
            name = "$paket.Ana"
            applicationInfo = ai
            nonLocalizedLabel = ad
            exported = true
        }
        shadowOf(pm).installPackage(PackageInfo().apply { packageName = paket; applicationInfo = ai })
        shadowOf(pm).addOrUpdateActivity(act)
        shadowOf(pm).addIntentFilterForActivity(
            ComponentName(paket, act.name),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }
}
