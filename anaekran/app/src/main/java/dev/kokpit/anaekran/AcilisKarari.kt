package dev.kokpit.anaekran

import android.os.Bundle

/**
 * Cold start or warm re-creation (0.2.2 review R2 Ö-1): does onCreate play the intro clip?
 *
 * The clip belongs to Kokpit's own opening: the first start after install, after a reboot of the
 * unit, and after the car was switched off and on. It does NOT belong to a return: when Kokpit was
 * behind CarPlay (or any app) and the system killed its process for memory, the next return
 * re-creates the activity from its saved state — that is a return, and replaying 2.4 s of clip
 * there is what the user asked to avoid ("açılış klibi tekrar oynamaz").
 *
 * Criterion: warm only if ALL of these hold, otherwise cold (the clip plays):
 *  1. there is a saved state written by this app's onSaveInstanceState ([kaydet]) — the system
 *     keeps it only while the task lives; a fresh start, a reboot or a finished task has none;
 *  2. both clocks moved forward since the save — same boot (elapsedRealtime and uptimeMillis
 *     restart at 0 on every boot);
 *  3. the unit did not sleep in between: elapsedRealtime counts deep sleep, uptimeMillis does not,
 *     so their difference grows by the time spent asleep. Less than [UYKU_SINIRI_MS] of sleep.
 *     Switching the car off puts these units to sleep (or shuts them down: then 1 or 2 fails), so a
 *     car start after a stop is cold even when the process and the task survived.
 * No limit on the plain time away: a long CarPlay drive with the screen on is still a return.
 *
 * Decision — why not just `savedInstanceState != null`: a unit that kills apps when the car is
 * switched off (and keeps the task) would re-create Kokpit with a saved state at the next car start,
 * and the clip would never play there again. Cost if wrong: if the unit stays fully awake while the
 * car is off and the process is killed meanwhile, that car start shows no clip (the screen comes at
 * once); if the unit sleeps for a short moment while CarPlay is in front, that return plays the clip.
 * The diagnostics line "Açılış:" shows what was decided and why, for the car test.
 */
object AcilisKarari {

    /** elapsedRealtime at the save, ms. */
    const val ANAHTAR_GERCEK = "kokpit.acilis.gercekMs"

    /** uptimeMillis at the save, ms. */
    const val ANAHTAR_CALISMA = "kokpit.acilis.calismaMs"

    /** More deep sleep than this between save and re-creation: the unit slept (car off), cold. */
    const val UYKU_SINIRI_MS = 60_000L

    /** What onCreate found. [sicak] true: skip the clip. Times for the diagnostics (null: cold, no state). */
    data class Sonuc(val sicak: Boolean, val gecenMs: Long?, val uykuMs: Long?)

    fun kaydet(b: Bundle, gercekMs: Long, calismaMs: Long) {
        b.putLong(ANAHTAR_GERCEK, gercekMs)
        b.putLong(ANAHTAR_CALISMA, calismaMs)
    }

    fun karar(kayit: Bundle?, gercekMs: Long, calismaMs: Long): Sonuc {
        if (kayit == null || !kayit.containsKey(ANAHTAR_GERCEK) || !kayit.containsKey(ANAHTAR_CALISMA)) {
            return Sonuc(sicak = false, gecenMs = null, uykuMs = null)
        }
        val gecen = gercekMs - kayit.getLong(ANAHTAR_GERCEK)
        val calisan = calismaMs - kayit.getLong(ANAHTAR_CALISMA)
        if (gecen < 0 || calisan < 0) return Sonuc(sicak = false, gecenMs = null, uykuMs = null) // another boot
        val uyku = gecen - calisan
        return Sonuc(sicak = uyku < UYKU_SINIRI_MS, gecenMs = gecen, uykuMs = uyku)
    }
}
