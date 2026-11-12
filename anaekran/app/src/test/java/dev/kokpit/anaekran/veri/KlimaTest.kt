package dev.kokpit.anaekran.veri

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** ACTION_AIR_INFO and outside-temperature decoding, without a screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class KlimaTest {

    @Before
    fun hazirla() {
        AracVerisi.saat = { 500L }
    }

    @After
    fun temizle() {
        AracVerisi.sifirla()
        AracVerisi.saat = { android.os.SystemClock.elapsedRealtime() }
        Teshis.sifirla()
    }

    private fun bosalt() = shadowOf(Looper.getMainLooper()).idle()

    private fun hava(vararg ciftler: Pair<String, Any?>) = Intent(KlimaCozucu.EYLEM_KLIMA).apply {
        putExtras(paket(*ciftler))
    }

    private fun paket(vararg ciftler: Pair<String, Any?>) = Bundle().apply {
        for ((k, v) in ciftler) when (v) {
            is Int -> putInt(k, v)
            is Float -> putFloat(k, v)
            is Double -> putDouble(k, v)
            is String -> putString(k, v)
            is Boolean -> putBoolean(k, v)
            null -> putString(k, null)
            else -> error("type")
        }
    }

    @Test
    fun tamYayin() {
        val b = paket(
            KlimaCozucu.SOL_SICAKLIK to 21.5f, KlimaCozucu.SAG_SICAKLIK to 21.5f,
            KlimaCozucu.FAN to 3, KlimaCozucu.AC to 1, KlimaCozucu.OTO to 0,
            "KEY_VALUE_UPWINDFLAG" to 0, "KEY_VALUE_PARALLELWINDFLAG" to 1, "KEY_VALUE_DOWNWINDFLAG" to 1,
            "KEY_VALUE_FRONTDEFROSTFLAG" to 0, "KEY_VALUE_DUALFLAG" to 0,
        )
        // Direction/demist keys (names guessed, unconfirmed) are not decoded: those fields stay null.
        assertEquals(
            Klima(Sicaklik.Derece(21.5), 3, havaYonu = null, ac = true, oto = false, buzCozme = null),
            KlimaCozucu.klima(b),
        )
    }

    @Test
    fun lowHi() {
        assertEquals(Sicaklik.Low, KlimaCozucu.sicaklik("LO"))
        assertEquals(Sicaklik.Low, KlimaCozucu.sicaklik(" low "))
        assertEquals(Sicaklik.Hi, KlimaCozucu.sicaklik("HI"))
        assertEquals(Sicaklik.Hi, KlimaCozucu.sicaklik("High"))
        assertEquals(Sicaklik.Derece(22.0), KlimaCozucu.sicaklik(22))
        assertEquals(Sicaklik.Derece(22.5), KlimaCozucu.sicaklik("22,5"))
        // Coded values we cannot read stay unknown, never a guess.
        assertNull(KlimaCozucu.sicaklik(255))
        assertNull(KlimaCozucu.sicaklik(0))
        assertNull(KlimaCozucu.sicaklik("--"))
        assertNull(KlimaCozucu.sicaklik(true))

        val k = KlimaCozucu.klima(paket(KlimaCozucu.SOL_SICAKLIK to "HI"))!!
        assertEquals(Sicaklik.Hi, k.sicaklik)
    }

    @Test
    fun eksikAnahtarBilinmiyor() {
        val k = KlimaCozucu.klima(paket(KlimaCozucu.SOL_SICAKLIK to 22))!!
        assertEquals(Sicaklik.Derece(22.0), k.sicaklik)
        assertNull(k.fan)
        assertNull(k.ac) // not "off"
        assertNull(k.oto)
        assertNull(k.havaYonu)
        assertNull(k.buzCozme)

        // Single zone: RIGHT_TEMP is diagnostics only; without LEFT_TEMP the temperature is unknown.
        assertNull(KlimaCozucu.klima(paket(KlimaCozucu.SAG_SICAKLIK to 19))!!.sicaklik)
        // Unconfirmed key names do not make a climate message.
        assertNull(KlimaCozucu.klima(paket("KEY_VALUE_UPWINDFLAG" to 1)))
        // No climate key at all: not a climate message.
        assertNull(KlimaCozucu.klima(paket("BASKA" to 1)))
    }

    @Test
    fun fanSinirlari() {
        assertEquals(0, KlimaCozucu.fan(0))
        assertEquals(8, KlimaCozucu.fan(8))
        assertEquals(3, KlimaCozucu.fan("3"))
        assertNull(KlimaCozucu.fan(9))
        assertNull(KlimaCozucu.fan(-1))
        assertNull(KlimaCozucu.fan(2.5))
        assertNull(KlimaCozucu.fan(null))
    }

    @Test
    fun bayrak() {
        assertEquals(true, KlimaCozucu.bayrak(1))
        assertEquals(false, KlimaCozucu.bayrak("0"))
        assertEquals(true, KlimaCozucu.bayrak(true))
        assertNull(KlimaCozucu.bayrak(2))
        assertNull(KlimaCozucu.bayrak("evet"))
    }

    @Test
    fun yayinSozlesmeyeYazar_teshisHamTutar() {
        KlimaKaynagi.isle(hava(KlimaCozucu.SOL_SICAKLIK to 23, KlimaCozucu.FAN to 8, KlimaCozucu.AC to 1))
        bosalt()
        val d = AracVerisi.durum
        assertEquals(Sicaklik.Derece(23.0), d.klima?.sicaklik)
        assertEquals(8, d.klima?.fan)
        assertEquals(true, d.klima?.ac)
        assertNull(d.klima?.oto)
        assertEquals(500L, d.klimaZamani)
        assertEquals("KEY_VALUE_ACFLAG=1, KEY_VALUE_LEFT_TEMP=23, KEY_VALUE_WIND_LEVEL=8", Teshis.klimaHam)
        assertEquals(500L, Teshis.klimaHamZamani)

        // An ACTION_AIR_INFO without climate keys leaves the climate alone (diagnostics still record it).
        AracVerisi.saat = { 900L }
        KlimaKaynagi.isle(hava("BASKA" to 5))
        bosalt()
        assertEquals(500L, AracVerisi.durum.klimaZamani)
        assertEquals("BASKA=5", Teshis.klimaHam)
        assertEquals(2, Teshis.klimaYayinSayisi)

        // A broken vendor intent never throws.
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_KLIMA))
        bosalt()
        assertEquals("(extras yok)", Teshis.klimaHam)
    }

    @Test
    fun disSicaklik() {
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK).putExtra("hcn.air.outdoor_temp", 14.5f))
        bosalt()
        assertEquals(14.5, AracVerisi.durum.disSicaklik!!, 0.001)

        // The other name on record (Keşif's LiveProbe) is read too, as a string.
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK_2).putExtra("ACTION_OUTDOOR_TEMP_DATA", "-3"))
        bosalt()
        assertEquals(-3.0, AracVerisi.durum.disSicaklik!!, 0.0)

        // K-74: an unknown single extra is NOT taken as the value; it is only shown raw.
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK_2).putExtra("x", "7"))
        bosalt()
        assertEquals(-3.0, AracVerisi.durum.disSicaklik!!, 0.0)
        assertTrue(Teshis.disSicaklikHam!!.startsWith("ACTION_OUTDOOR_TEMP_DATA: x=7"))

        // Guessed names ("temp", "value", other KEY_VALUE_* forms) are not read either.
        for (ad in listOf("temp", "value", "KEY_VALUE_OUTDOOR_TEMP", "KEY_VALUE_OUTSIDE_TEMP", "outdoor_temp", "OUTDOOR_TEMP")) {
            KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK).putExtra(ad, 21))
            KlimaKaynagi.isle(hava(ad to 21))
        }
        bosalt()
        assertEquals(-3.0, AracVerisi.durum.disSicaklik!!, 0.0)

        // Out of range: unknown, not the old value.
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK).putExtra("hcn.air.outdoor_temp", 145))
        bosalt()
        assertNull(AracVerisi.durum.disSicaklik)

        // Nothing readable (no extras, or several unknown extras): the value is left alone.
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK).putExtra("hcn.air.outdoor_temp", 9))
        bosalt()
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK))
        KlimaKaynagi.isle(Intent(KlimaCozucu.EYLEM_DIS_SICAKLIK).putExtra("a", 1).putExtra("b", 2))
        bosalt()
        assertEquals(9.0, AracVerisi.durum.disSicaklik!!, 0.0)

        // Inside ACTION_AIR_INFO.
        KlimaKaynagi.isle(hava("hcn.air.outdoor_temp" to 12))
        bosalt()
        assertEquals(12.0, AracVerisi.durum.disSicaklik!!, 0.0)
    }
}
