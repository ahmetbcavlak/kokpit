package dev.kokpit.kesif

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout

/**
 * Keşif 0.9: measurements for the Faz 6 roadmap (K-133). Six pages, one CSV: kayit/yol-olcumleri.csv
 * (iPad: /yol.csv). Every row says which page wrote it.
 *
 * Nothing here changes a setting for good: the only write is the brightness test, which puts the old
 * value back after 3 s (or at the next start if Keşif was closed in between).
 */
object Yol {
    val log = CsvLog("yol-olcumleri.csv", "zaman,sayfa,tur,anahtar,deger,not")

    fun row(ctx: Context, page: String, kind: String, key: String, value: String, note: String = "", at: Long = System.currentTimeMillis()) =
        log.post(ctx, at, listOf(page, kind, key, value, note))

    fun rows(ctx: Context, page: String, kind: String, lines: List<Pair<String, String>>, at: Long = System.currentTimeMillis()) =
        log.post(ctx, lines.map { at to listOf(page, kind, it.first, it.second, "") })

    /** An uptime-clock time (MotionEvent, AccessibilityEvent, Choreographer in ms) on the elapsedRealtime clock. */
    fun uptimeToElapsed(uptimeMs: Long): Long = SystemClock.elapsedRealtime() - (SystemClock.uptimeMillis() - uptimeMs)

    val pages = listOf(
        "internet" to "İnternet",
        "guncelle" to "Güncellemeyi dene",
        "acilis" to "Açılış zaman çizelgesi",
        "dokunus" to "Dokunuş",
        "ses" to "Kısa ses",
        "parlaklik" to "Parlaklık",
    )
}

/** The hub: six buttons, one per measurement. */
class YolPage(private val a: Activity, onBack: () -> Unit, private val open: (String) -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val status = ui.label("", 14f, Palette.muted)

    init {
        val (r, content) = ui.frame("Yol haritası ölçümleri", "Keşif 0.9 · Faz 6 (K-133) · yol-olcumleri.csv", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Kontrol listesindeki “Yol haritası için ölçümler” sırasıyla. Hiçbir ayar kalıcı değişmez; parlaklık denemesi eski değere döner. Sürüşte deneme yok.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(10)) })
            for ((i, p) in Yol.pages.withIndex()) {
                addView(ui.button("${i + 1} · ${p.second}", primary = i == 0, size = 19f) { open(p.first) },
                    LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(66)).apply { bottomMargin = ui.dp(10) })
            }
            addView(status)
        }
    }

    override fun tick() {
        val f = Yol.log.file(a)
        status.text = "Tek dosya: yol-olcumleri.csv · iPad’de /yol.csv" +
            (if (f.exists()) " · ${f.length() / 1024} KB" else " · henüz satır yok") +
            (Yol.log.lastError?.let { " · yazma hatası: $it" } ?: "")
    }
}
