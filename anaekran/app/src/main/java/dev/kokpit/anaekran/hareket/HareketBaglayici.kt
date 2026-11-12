package dev.kokpit.anaekran.hareket

import android.app.Activity
import android.content.Context
import android.widget.FrameLayout
import dev.kokpit.anaekran.Baglayici
import dev.kokpit.anaekran.Katmanlar
import dev.kokpit.anaekran.ekran.Gecis
import dev.kokpit.anaekran.tema.Palet
import dev.kokpit.anaekran.tema.Tema
import dev.kokpit.anaekran.tema.TemaDinleyici

/**
 * Motion subsystem: intro clip, park ↔ drive clips / cross-fades, screen transition and the
 * frame-rate counter. It does not read or change the scene layer, the screens or
 * MainActivity's state; the caller hands in pictures and reacts in `bitti`.
 *
 * Wiring (task 4):
 *  1. `Baglayicilar.liste()`: add one instance, and keep a reference (`val hareket = HareketBaglayici()`).
 *     [kur] puts the counter into `katmanlar.kareHizi`; [basla]/[dur] run it with onResume/onPause;
 *     [birak] frees the player.
 *  2. `katmanlar.ekranlar.gecis = hareket.gecis` (right after [kur]).
 *  3. Intro, in onCreate after [kur] (replaces `acilisBitti`/`ACILIS_MS` and the placeholder's tap):
 *     `hareket.acilisiOynat(hedefId) { ekranlar.goster(Ekran.PARK) }` — `hedefId` is the
 *     picture the scene layer shows right after `bitti` (e.g. `SahneKatmani.kaynak(Ekran.PARK, gunduz)`).
 *     The 1.6 s are counted from this call. Taps: a transparent shield cuts on tap by itself;
 *     route other taps (e.g. the ACILIS screen's click) to [kes] as well — repeated calls are harmless.
 *  4. Park ↔ drive, call BEFORE switching the screen/scene:
 *     night: `hareket.gecisOynat(Klip.SURUS, eskiId, yeniId) { … }` (back: `Klip.SURUS_GERI`);
 *     day (no day clip): `hareket.gecisOynat(null, eskiId, yeniId) { … }` — a plain 350 ms fade.
 *     `eskiId` = the scene on screen now (shown at once as a cover, so the scene layer may switch
 *     immediately after the call), `yeniId` = the scene after the change.
 *  5. In every `bitti`, the scene layer must show the target by the time `bitti` returns: the
 *     overlay is removed in the same main-thread turn. With the `Int` overloads the target was
 *     decoded through `Gorseller`, so `SahneKatmani.goster` gets it from the cache synchronously.
 *     `Sonuc.KESILDI` means a newer call took over. Starting a new call from any callback is
 *     safe (a run started from a KESILDI callback is itself ended with KESILDI).
 *  6. Scene layer while a clip plays (the opaque video covers it, but HWUI would still fill it
 *     every frame):
 *     `hareket.videoGorunurlugu = { v -> katmanlar.sahne.visibility = if (v) View.INVISIBLE else View.VISIBLE }`
 *     — `true` comes when the video's first frame is on screen, `false` before the video is
 *     removed (always before `bitti`, while the opaque target overlay covers the screen), in pairs.
 *     INVISIBLE, not GONE: the scene keeps its size and its bitmap. Can be set before or after [kur].
 *
 * The counter measures fps over the clip / fade / screen-transition spans these components
 * report (HareketIzci); nothing to wire for that.
 */
class HareketBaglayici(
    private val oynaticiUret: (Context) -> Oynatici = { DokuOynatici(it) },
    private val yukleyici: GorselYukleyici = GorselYukleyici.VARSAYILAN,
    private val kareKaynagi: KareKaynagi = KareKaynagi.CHOREOGRAPHER,
) : Baglayici, TemaDinleyici {

    private val yumusakGecis = YumusakGecis()

    /** Screen transition for `EkranYoneticisi.gecis`. */
    val gecis: Gecis get() = yumusakGecis

    /** See wiring step 6. */
    var videoGorunurlugu: ((Boolean) -> Unit)? = null
        set(value) {
            field = value
            oynatici?.videoGorunurlugu = value
        }

    var oynatici: KlipOynatici? = null
        private set

    var sayac: KareHiziSayaci? = null
        private set

    override fun kur(activity: Activity, katmanlar: Katmanlar) {
        val s = KareHiziSayaci(activity, kareKaynagi)
        s.temaUygula(Tema.palet)
        // The slot has a fixed 96×20 design-px size; a single line that keeps its height makes
        // setText invalidate only, without requestLayout (TextView.checkForRelayout).
        katmanlar.kareHizi.addView(
            s,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        sayac = s
        yumusakGecis.izci = s
        oynatici = KlipOynatici(katmanlar.video, katmanlar.kok, oynaticiUret, yukleyici).also {
            it.izci = s
            it.videoGorunurlugu = videoGorunurlugu
        }
        Tema.dinle(this)
    }

    override fun basla() {
        sayac?.basla()
    }

    /**
     * The clip is not cut here: a permission dialog pauses us while our window stays visible,
     * and must not cut the intro. When another app really comes to the front, the window goes
     * out of sight and the player releases the decoder by itself (Oynatici.Dinleyici.pencereGizlendi).
     */
    override fun dur() {
        sayac?.dur()
    }

    override fun birak() {
        sayac?.dur()
        yumusakGecis.izci = null
        oynatici?.birak()
        Tema.birak(this)
    }

    override fun temaDegisti(palet: Palet) {
        sayac?.temaUygula(palet)
    }

    /**
     * Intro: `klip_giris` plays, the fade to [hedef] (350 ms, calm curve) starts 1.6 s after
     * this call (or when the clip ends, if later); a tap cuts with a 150 ms fade.
     * [bitti] once: TAMAM, DOKUNULDU, ATLANDI (clip failed: faded straight to [hedef]) or KESILDI.
     */
    fun acilisiOynat(hedef: Goruntu, bitti: (KlipOynatici.Sonuc) -> Unit) {
        gerekli().oynat(Klip.GIRIS, null, hedef, ACILIS_TUT_MS, ACILIS_GECIS_MS, dokunmaKalkani = true, bitti = bitti)
    }

    /** [acilisiOynat] with a drawable resource id. */
    fun acilisiOynat(hedefId: Int, bitti: (KlipOynatici.Sonuc) -> Unit) = acilisiOynat(Goruntu.Kaynak(hedefId), bitti)

    /**
     * Park ↔ drive. With a [klip]: plays it, fades to [yeni] in 200 ms as soon as it ends.
     * Without: plain cross-fade [eski] → [yeni] in 350 ms. A call while another runs ends that
     * one at once (its `bitti(KESILDI)`) and starts from its target.
     */
    fun gecisOynat(klip: Klip?, eski: Goruntu?, yeni: Goruntu, bitti: (KlipOynatici.Sonuc) -> Unit) {
        val ms = if (klip == null) DUZ_GECIS_MS else KLIP_GECIS_MS
        gerekli().oynat(klip, eski, yeni, 0L, ms, dokunmaKalkani = false, bitti = bitti)
    }

    /** [gecisOynat] with drawable resource ids. */
    fun gecisOynat(klip: Klip?, eskiId: Int, yeniId: Int, bitti: (KlipOynatici.Sonuc) -> Unit) =
        gecisOynat(klip, Goruntu.Kaynak(eskiId), Goruntu.Kaynak(yeniId), bitti)

    /** Tap: cut the running clip / fade quickly. No-op when idle. */
    fun kes() {
        oynatici?.kes()
    }

    private fun gerekli(): KlipOynatici = checkNotNull(oynatici) { "HareketBaglayici.kur() önce çağrılmalı" }

    companion object {
        const val ACILIS_TUT_MS = 1600L
        const val ACILIS_GECIS_MS = 350L
        const val KLIP_GECIS_MS = 200L
        const val DUZ_GECIS_MS = 350L
    }
}
