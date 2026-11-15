package dev.kokpit.anaekran.veri

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Door/boot state from the vendor's system properties (K-116): `persist.vendor.cem.cardoor1..5`
 * (1 front left, 2 front right, 3 rear left, 4 rear right, 5 boot; "1" closed, "2" open, empty = unknown).
 *
 * Source: com.hcn.autocan 3.0.1, CanDoorWindow.dealDoorInfo() sets them from the CAN frame. It runs
 * even while the popup is not shown (Settings.System "can_ignore_doorinfo" = 0, "kapı bilgisini göster:
 * Kapalı"), so the properties are current with the popup switched off. Util.cleanAvmDoor() may pull all
 * five to "1" at once. That is taken as a real close (K-116 accepts it: the vendor's own state says
 * closed), so a "2" → "1" there shows the screens' "Kapı kapandı." confirmation, and that is right:
 * this is the source, there is nothing better to check it against.
 *
 * Read only. Properties are read, never set; can_ignore_doorinfo is read with Settings.System.getInt
 * only. No command goes to the car (K-29).
 *
 * How it reads:
 *  - android.os.SystemProperties.get(String) by reflection, the Method looked up once. API 27 has no
 *    hidden-API restriction; from API 28 SystemProperties.get is on the "greylist" (allowed, logged),
 *    and later lists may block it: then the lookup or the call fails and the reader falls back below.
 *  - fallback `getprop <name>` through ProcessBuilder: the binary and its one argument only, no shell,
 *    no user input (the name must be one of [ADLAR]); the output is read after the process ended within
 *    [GETPROP_ZAMAN_ASIMI_MS], and the process is destroyed in every case. Five processes per pass is
 *    costly on the AC8227L, so this fallback reads every [GETPROP_ARALIK_MS] (and only every
 *    [BEKLEME_ARALIK_MS] while no value has been seen yet).
 *
 * Source choice ([birincil]): the properties are "readable" once a pass read at least one of the five
 * as non-empty (the read itself worked, and the vendor publishes the key). Seen once is enough, for
 * the process's life. From then on this class is the only writer of kapiAcik/bagajAcik/kapilar, and
 * KapiServisi (accessibility) only logs. Until then the accessibility path of 0.2 writes as before.
 *
 * Only a change is written to [AracVerisi] (the same values never wake a listener, 0.1's invariant;
 * they are not even posted). Raw values go to [Teshis] on every pass (plain field writes).
 *
 * Life cycle (VeriBaglayici): [basla] in onResume starts it with a read at once, [dur] in onPause stops
 * it. A door warning does not matter while another app is in front, but on return the state must be
 * right at once, hence the immediate read; reading in the background would cost CPU for nothing.
 *
 * While stopped the door state is UNKNOWN, not the last value (0.2.2 review R1 ÖNEMLİ-1): [dur]
 * writes kapiAcik/bagajAcik/kapilar = null when the properties are the source, and the first pass
 * after [basla] writes the current values whatever was written before. So a door that closed while
 * another app was in front gives null → false on return, which is not a "Kapı kapandı." (that is
 * true → false only, UyariKatmani), and a stale "Kapı açık" is never on screen after a return; a door
 * still open gives null → true, the warning as usual. A pass already running when [dur] comes is
 * dropped where its values land (main thread, generation check), so it cannot overwrite the null.
 * Decision on the long-press silence (UyariKatmani.sustur): the null is a change of value, so a
 * warning the user silenced comes back on return if the door is still open. Why: the silence is
 * for the warning in front of the user now; after leaving the app (often a long CarPlay drive) the
 * door may have been closed and opened again, and an open door on return — maybe while driving —
 * must be seen. Cost if wrong: with a stuck property ("2" that never clears) one more long press
 * after each return.
 *
 * Decision (R1 KÜÇÜK-6): [birincil] is sticky for the process. If the properties become unreadable
 * later (every read null), doors and boot stay unknown and the accessibility path does NOT take
 * over again: one source per process run, no flapping between two sources with different timing
 * (a switch back could itself show a false "Kapı kapandı."). Teshis shows "okuma okunamıyor" then.
 * Cost if wrong: no door warning for the rest of that run; the next process start picks again.
 *
 * Threads: every pass runs on one worker (HandlerThread "kokpit-kapi-ozellik"), never on main.
 */
class KapiOzellikleri internal constructor(
    private val okuyucuSec: () -> OzellikOkuyucu,
    private val ayar: AyarOkuyucu,
    private val zamanlayici: Zamanlayici,
    private val saat: () -> Long = { AracVerisi.saat() },
    /** The getprop reader used when reflection stops working at run time. */
    private val yedekOkuyucu: () -> OzellikOkuyucu = { GetpropOkuyucu() },
) {

    constructor(context: Context) : this(
        { varsayilanOkuyucu() },
        SistemAyari(context.applicationContext.contentResolver),
        IsciZamanlayici(),
    )

    /** One reading of the properties, already resolved, as it was last handed to AracVerisi. */
    private data class Yazilan(val kapilar: List<Boolean?>, val bagaj: Boolean?)

    private val nesil = AtomicInteger(0)

    // Worker thread only.
    private var okuyucu: OzellikOkuyucu? = null
    private var sonHam: List<String?>? = null
    private var sonYazilan: Yazilan? = null
    /** Generation [sonYazilan] belongs to: a new [basla] writes its first values in any case. */
    private var yazilanNesil = -1
    private var sonrakiAyar = 0L

    /** For tests: the reader in use ("yansıma" / "getprop"), null before the first pass. */
    internal val okuyucuAdi: String? get() = okuyucu?.ad

    /** onResume: a pass at once, then every [aralik] ms. */
    fun basla() {
        val n = nesil.incrementAndGet()
        zamanlayici.temizle()
        zamanlayici.sonra(0L, gecis(n))
    }

    /**
     * onPause: no more passes, and the door state becomes unknown (see the class notes). A pass
     * already running finishes its read, but its write is dropped on main (generation changed).
     * Main thread (VeriBaglayici); the null write runs at once there.
     */
    fun dur() {
        nesil.incrementAndGet()
        zamanlayici.temizle()
        AracVerisi.guncelle {
            // Checked where it lands: with the accessibility path as source this class owns nothing.
            if (birincil) {
                kapiAcik(null)
                bagajAcik(null)
                kapilar(AracDurumu.KAPILAR_BILINMIYOR)
            }
        }
    }

    /** onDestroy: stopped for good, worker thread ended. */
    fun birak() {
        dur()
        zamanlayici.bitir()
    }

    private fun gecis(n: Int): Runnable = Runnable {
        if (n != nesil.get()) return@Runnable
        val sonraki = try {
            tara(n)
        } catch (t: Throwable) {
            Teshis.hata("kapı özellikleri", t)
            BEKLEME_ARALIK_MS
        }
        if (n == nesil.get()) zamanlayici.sonra(sonraki, gecis(n))
    }

    /** One pass on the worker, of generation [n]. Returns the delay until the next one. */
    private fun tara(n: Int): Long {
        val simdi = saat()
        if (simdi >= sonrakiAyar) {
            sonrakiAyar = simdi + AYAR_ARALIK_MS
            Teshis.canIgnoreDoorinfo = ayar.oku()
        }

        var o = okuyucu ?: okuyucuSec().also { okuyucu = it }
        var ham = ADLAR.map { o.oku(it) }
        // Reflection found but the call fails (a later hidden-API list): fall back to getprop for good.
        if (ham.all { it == null } && o is YansimaOkuyucu) {
            Teshis.kapiOlayi("özellik: yansıma çalışmadı, getprop'a geçildi")
            o = yedekOkuyucu().also { okuyucu = it }
            ham = ADLAR.map { o.oku(it) }
        }

        Teshis.kapiOzellikHam = ham
        Teshis.kapiOzellikOkuma = if (ham.all { it == null }) "okunamıyor" else o.ad
        val once = sonHam
        if (once == null || once != ham) {
            if (once != null) Teshis.kapiOzellikDegisim = simdi
            Teshis.kapiOlayi("özellik " + ham.withIndex().joinToString(" ") { (i, v) ->
                val eski = once?.get(i)
                if (once != null && eski != v) "${i + 1}=${Teshis.hamMetin(eski)}→${Teshis.hamMetin(v)}" else "${i + 1}=${Teshis.hamMetin(v)}"
            })
            sonHam = ham
        }

        if (!birincil && KapiOzellikCozumu.okunabilir(ham)) {
            birincil = true
            Teshis.kapiOlayi("kaynak: sistem özellikleri (erişilebilirlik artık yazmaz)")
        }
        if (birincil) yaz(ham, n)

        return when {
            o !is GetpropOkuyucu -> ARALIK_MS
            birincil -> GETPROP_ARALIK_MS
            else -> BEKLEME_ARALIK_MS
        }
    }

    private fun yaz(ham: List<String?>, n: Int) {
        val cozulen = ham.map { KapiOzellikCozumu.deger(it) }
        val yeni = Yazilan(cozulen.subList(0, 4).toList(), cozulen[4])
        // First pass after basla(): dur() wrote null meanwhile, so the values go out even if unchanged.
        if (n != yazilanNesil) {
            yazilanNesil = n
            sonYazilan = null
        }
        val zorla = yenidenYaz
        if (!zorla && yeni == sonYazilan) return
        yenidenYaz = false
        sonYazilan = yeni
        // Values computed here, on the worker; the block only hands them over (it runs later on main).
        val kapilar = yeni.kapilar
        val kapi = KapiOzellikCozumu.kapiAcik(kapilar)
        val bagaj = yeni.bagaj
        AracVerisi.guncelle {
            // dur() came after this pass started: its null stays (the values are for a stopped run).
            if (n == nesil.get()) {
                kapilar(kapilar)
                kapiAcik(kapi)
                bagajAcik(bagaj)
                kapiKaynagi(KapiKaynagi.OZELLIK)
            }
        }
    }

    // --- Readers ---

    /** Reads one system property. Returns its value ("" = not set) or null when the read itself failed. */
    internal interface OzellikOkuyucu {
        /** For diagnostics: "yansıma" or "getprop". */
        val ad: String
        fun oku(ozellik: String): String?
    }

    /** android.os.SystemProperties.get(String), looked up once. */
    internal class YansimaOkuyucu(private val get: Method) : OzellikOkuyucu {
        override val ad = "yansıma"
        override fun oku(ozellik: String): String? = try {
            (get.invoke(null, ozellik) as? String)?.trim().orEmpty()
        } catch (t: Throwable) {
            null
        }

        companion object {
            /** null when the class or method cannot be found. Only "get" is ever looked up. */
            @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
            fun olustur(): YansimaOkuyucu? = try {
                YansimaOkuyucu(Class.forName("android.os.SystemProperties").getMethod("get", String::class.java))
            } catch (t: Throwable) {
                null
            }
        }
    }

    /**
     * `getprop <name>`: one process per property, no shell. [baslat] starts the process (tests pass a
     * fake); the default is the only ProcessBuilder of the app (YasakCagriTest allows it here alone).
     */
    internal class GetpropOkuyucu(
        private val baslat: (String) -> Process = { ad -> ProcessBuilder("getprop", ad).redirectErrorStream(true).start() },
        private val zamanAsimiMs: Long = GETPROP_ZAMAN_ASIMI_MS,
    ) : OzellikOkuyucu {
        override val ad = "getprop"

        override fun oku(ozellik: String): String? {
            // Only the five fixed names ever reach the process; nothing else is accepted.
            if (ozellik !in ADLAR) return null
            var p: Process? = null
            return try {
                val surec = baslat(ozellik)
                p = surec
                runCatching { surec.outputStream.close() }
                if (!surec.waitFor(zamanAsimiMs, TimeUnit.MILLISECONDS)) return null
                if (surec.exitValue() != 0) return null
                // The process has ended: what it wrote is in the pipe, reading cannot block. Bounded anyway.
                val tampon = ByteArray(CIKTI_SINIRI)
                var n = 0
                surec.inputStream.use { girdi ->
                    while (n < tampon.size) {
                        val r = girdi.read(tampon, n, tampon.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                String(tampon, 0, n, Charsets.UTF_8).trim()
            } catch (e: InterruptedException) {
                // Nobody interrupts this worker today; keep the flag for whoever does.
                Thread.currentThread().interrupt()
                null
            } catch (t: Throwable) {
                null
            } finally {
                // Closed here, not left to destroy() (R1 KÜÇÜK-7a): the pipes' descriptors on every path.
                p?.let { s ->
                    runCatching { s.inputStream.close() }
                    runCatching { s.errorStream.close() }
                    runCatching { s.destroy() }
                }
            }
        }
    }

    /** can_ignore_doorinfo for diagnostics: "0", "1", … or "okunamadı (…)". */
    internal fun interface AyarOkuyucu {
        fun oku(): String
    }

    /** Settings.System.getInt only; nothing is ever written. */
    internal class SistemAyari(private val cr: ContentResolver) : AyarOkuyucu {
        override fun oku(): String = try {
            Settings.System.getInt(cr, AYAR_CAN_IGNORE_DOORINFO).toString()
        } catch (e: Settings.SettingNotFoundException) {
            "okunamadı (ayar yok)"
        } catch (e: SecurityException) {
            "okunamadı (izin yok)"
        } catch (t: Throwable) {
            "okunamadı"
        }
    }

    /** Runs the passes; tests pass one that only records. */
    internal interface Zamanlayici {
        fun sonra(gecikmeMs: Long, r: Runnable)
        fun temizle()
        fun bitir()
    }

    /**
     * One HandlerThread, started on first use. After [bitir] it never starts another one (R1
     * KÜÇÜK-5): a pass that checked its generation just before birak() and then asks for the next
     * pass must not open a thread nobody would end.
     */
    internal class IsciZamanlayici : Zamanlayici {
        private var isci: HandlerThread? = null
        private var handler: Handler? = null
        private var bitti = false

        /** Tests: a worker thread exists now. */
        internal val isciVar: Boolean @Synchronized get() = isci != null

        @Synchronized
        override fun sonra(gecikmeMs: Long, r: Runnable) {
            if (bitti) return
            val h = handler ?: HandlerThread("kokpit-kapi-ozellik").let { t ->
                t.start()
                isci = t
                Handler(t.looper).also { handler = it }
            }
            h.postDelayed(r, gecikmeMs)
        }

        @Synchronized
        override fun temizle() {
            handler?.removeCallbacksAndMessages(null)
        }

        @Synchronized
        override fun bitir() {
            bitti = true
            handler?.removeCallbacksAndMessages(null)
            isci?.quitSafely()
            isci = null
            handler = null
        }
    }

    companion object {
        private const val ON_EK = "persist.vendor.cem.cardoor"

        /** cardoor1..5: front left, front right, rear left, rear right, boot. */
        val ADLAR: List<String> = (1..5).map { "$ON_EK$it" }

        const val AYAR_CAN_IGNORE_DOORINFO = "can_ignore_doorinfo"

        /** Reflection: 5 native reads per pass, cheap. */
        const val ARALIK_MS = 250L
        /** getprop: 5 processes per pass; four times a second would cost the AC8227L too much. */
        const val GETPROP_ARALIK_MS = 1_000L
        /** getprop before any value was seen (the vendor may not publish the keys at all), and after an error. */
        const val BEKLEME_ARALIK_MS = 5_000L
        const val GETPROP_ZAMAN_ASIMI_MS = 500L
        const val AYAR_ARALIK_MS = 2_000L
        /** PROP_VALUE_MAX is 92 bytes; more than this is not a property value. */
        private const val CIKTI_SINIRI = 256

        /**
         * The properties are the door source (sticky for the process). KapiServisi reads it, on its worker
         * and again inside its main-thread write block, so a write of the accessibility path that was
         * posted before the switch is dropped.
         */
        @Volatile var birincil: Boolean = false
            internal set

        /** Set by [KapiServisi.uygulamaIciSifirla]: the next pass writes its values even if unchanged. */
        @Volatile internal var yenidenYaz: Boolean = false

        internal fun varsayilanOkuyucu(): OzellikOkuyucu = YansimaOkuyucu.olustur() ?: GetpropOkuyucu()

        /** Tests only. */
        internal fun testSifirla() {
            birincil = false
            yenidenYaz = false
        }
    }
}

/** Pure resolution of the raw property values. */
object KapiOzellikCozumu {

    /** "1" closed (false), "2" open (true), empty / anything else / not read = unknown (null). */
    fun deger(ham: String?): Boolean? = when (ham?.trim()) {
        "1" -> false
        "2" -> true
        else -> null
    }

    /** Doors 1–4: any open → true; all closed → false; otherwise (some unknown, none open) null. */
    fun kapiAcik(kapilar: List<Boolean?>): Boolean? = when {
        kapilar.any { it == true } -> true
        kapilar.isNotEmpty() && kapilar.all { it == false } -> false
        else -> null
    }

    /** The read worked and at least one of the five properties is not empty (all five are counted). */
    fun okunabilir(ham: List<String?>): Boolean = ham.any { !it.isNullOrEmpty() }
}
