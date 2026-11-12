package dev.kokpit.anaekran.veri

import android.os.Bundle
import java.util.Locale

/**
 * Turns the vendor's climate broadcasts into [Klima] and an outside temperature.
 *
 * All broadcast and extra names live here, so the first test on the car can correct them in one
 * place. What is confirmed and what is not:
 *  - Actions ACTION_AIR_INFO, hcn.air.outdoor_temp, ACTION_OUTDOOR_TEMP_DATA: the ones Kokpit Keşif
 *    (LiveProbe) listens to; climate over ACTION_AIR_INFO was seen on the car on 12 Nov.
 *  - Extras KEY_VALUE_LEFT_TEMP, _RIGHT_TEMP, _WIND_LEVEL, _ACFLAG, _BIGAUTOFLAG: Keşif's climate card
 *    (LiveProbe.kt), the source of the 12 Nov result. Their value encoding (plain degrees? a code for
 *    LOW/HI? 0/1 flags? fan 0–8 as raw numbers?) is NOT recorded anywhere, so only unambiguous values
 *    are accepted and everything else stays null.
 *  - Air direction, demist: present on the unit (12 Nov) but no confirmed extra name, so [Klima.havaYonu]
 *    and [Klima.buzCozme] are never filled in 0.1.
 *  - Single zone: [Klima.sicaklik] is LEFT_TEMP only. RIGHT_TEMP is kept in the raw diagnostics: the unit
 *    is single-zone ("DUAL hep 0", the passenger side follows the driver's), so a second number on screen
 *    would only invite a difference that the car does not have; if LEFT_TEMP is missing the value is unknown.
 *  - Outside-temperature extra names: ASSUMED ([DIS_SICAKLIK]).
 * [Teshis.klimaHam] shows every raw key on the car, so names and encodings can be fixed after the first look.
 *
 * Rule: an unknown or out-of-range value becomes null, never a default "off" or 0.
 */
object KlimaCozucu {

    const val EYLEM_KLIMA = "ACTION_AIR_INFO"
    const val EYLEM_DIS_SICAKLIK = "hcn.air.outdoor_temp"
    const val EYLEM_DIS_SICAKLIK_2 = "ACTION_OUTDOOR_TEMP_DATA"
    val EYLEMLER = listOf(EYLEM_KLIMA, EYLEM_DIS_SICAKLIK, EYLEM_DIS_SICAKLIK_2)

    // Confirmed names (Keşif climate card).
    const val SOL_SICAKLIK = "KEY_VALUE_LEFT_TEMP"
    /** Diagnostics only (single zone). */
    const val SAG_SICAKLIK = "KEY_VALUE_RIGHT_TEMP"
    const val FAN = "KEY_VALUE_WIND_LEVEL"
    const val AC = "KEY_VALUE_ACFLAG"
    const val OTO = "KEY_VALUE_BIGAUTOFLAG"

    /** Assumed names for the outside temperature; the first three are also looked for inside ACTION_AIR_INFO. */
    val DIS_SICAKLIK = listOf(
        "hcn.air.outdoor_temp", "KEY_VALUE_OUTDOOR_TEMP", "KEY_VALUE_OUTSIDE_TEMP",
        "outdoor_temp", "OUTDOOR_TEMP", "temp", "value",
    )

    /** The keys that make a broadcast a climate state message. */
    private val KLIMA_ANAHTARLARI = listOf(SOL_SICAKLIK, SAG_SICAKLIK, FAN, AC, OTO)

    /**
     * Plausible set temperatures in °C. Kept wide on purpose: the unit's real range and step are not
     * confirmed; a value outside (e.g. a coded LOW/HI or a half-degree count) stays null and visible in [Teshis].
     */
    private val AYAR_ARALIGI = 10.0..35.0
    private val DIS_ARALIGI = -45.0..65.0
    const val FAN_EN_COK = 8

    /**
     * Climate from one ACTION_AIR_INFO extras bundle. null = the bundle carries none of the climate
     * keys (then the current climate is left as it is). A present bundle is read as a full state:
     * a key missing from it is unknown (null), not "unchanged".
     */
    fun klima(b: Bundle): Klima? {
        if (KLIMA_ANAHTARLARI.none { b.containsKey(it) }) return null
        return Klima(
            sicaklik = sicaklik(deger(b, SOL_SICAKLIK)),
            fan = fan(deger(b, FAN)),
            havaYonu = null, // no confirmed key
            ac = bayrak(deger(b, AC)),
            oto = bayrak(deger(b, OTO)),
            buzCozme = null, // no confirmed key
        )
    }

    /** Outside temperature inside ACTION_AIR_INFO, if one of the known names is present. */
    fun disSicaklikKlimadan(b: Bundle): Okunan? =
        DIS_SICAKLIK.take(3).firstOrNull { b.containsKey(it) }?.let { Okunan(disSicaklik(deger(b, it))) }

    /**
     * Outside temperature from its own broadcast: a known extra name, or, failing that, the only
     * extra of the bundle. null = nothing to read (the current value stays).
     */
    fun disSicaklikYayini(b: Bundle?): Okunan? {
        if (b == null || b.isEmpty) return null
        val ad = DIS_SICAKLIK.firstOrNull { b.containsKey(it) } ?: b.keySet().singleOrNull() ?: return null
        return Okunan(disSicaklik(deger(b, ad)))
    }

    /** A value that was present in the broadcast; [deger] is null when it could not be read. */
    data class Okunan(val deger: Double?)

    // --- single values ---

    fun sicaklik(v: Any?): Sicaklik? {
        if (v is String) {
            when (v.trim().uppercase(Locale.ROOT)) {
                "LO", "LOW" -> return Sicaklik.Low
                "HI", "HIGH" -> return Sicaklik.Hi
            }
        }
        val d = sayi(v) ?: return null
        return if (d in AYAR_ARALIGI) Sicaklik.Derece(d) else null
    }

    fun fan(v: Any?): Int? {
        val d = sayi(v) ?: return null
        if (d != Math.floor(d)) return null
        val n = d.toInt()
        return if (n in 0..FAN_EN_COK) n else null
    }

    /** 0/1 or a real boolean; anything else is unknown. */
    fun bayrak(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        null -> null
        else -> when (sayi(v)) {
            0.0 -> false
            1.0 -> true
            else -> if (v is String) v.trim().lowercase(Locale.ROOT).toBooleanStrictOrNull() else null
        }
    }

    fun disSicaklik(v: Any?): Double? {
        val d = sayi(v) ?: return null
        return if (d in DIS_ARALIGI) d else null
    }

    private fun sayi(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> v.trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun deger(b: Bundle, k: String): Any? = b.get(k)

    /** "KEY=value, KEY=value", sorted; arrays joined with commas (same as Keşif's LiveProbe). */
    fun duzle(b: Bundle?): String {
        if (b == null) return "(extras yok)"
        return b.keySet().sorted().joinToString(", ") { k ->
            val v = runCatching {
                when (val x = deger(b, k)) {
                    is IntArray -> x.joinToString(",")
                    is ByteArray -> x.joinToString(",")
                    is FloatArray -> x.joinToString(",")
                    is Array<*> -> x.joinToString(",")
                    else -> x.toString()
                }
            }.getOrDefault("?")
            "$k=$v"
        }.ifEmpty { "(boş)" }
    }
}
