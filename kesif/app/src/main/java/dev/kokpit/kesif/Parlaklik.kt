package dev.kokpit.kesif

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout

/**
 * "Parlaklık" (Keşif 0.9, wave B: theme by the sun). Reads Settings.System.SCREEN_BRIGHTNESS and its
 * mode. With "Modify system settings" granted, and after the user confirms: one step down, then the
 * old value back after 3 s. The old value is saved first, so if Keşif is closed in between it is put
 * back at the next start ([restorePending]). The mode is never touched.
 */
object Brightness {
    const val STEP = 26 // about a tenth of 0–255
    const val HOLD_MS = 3000L
    private const val PREFS = "parlaklik"

    @Volatile var testing = false; private set
    @Volatile var last: String = ""; private set

    fun value(ctx: Context): Int? = runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()

    fun mode(ctx: Context): String = when (runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull()) {
        Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC -> "otomatik"
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL -> "elle"
        null -> "okunamadı"
        else -> "bilinmiyor"
    }

    /** For tests only (Robolectric has no switch for this permission). */
    @Volatile internal var canWriteForTest: Boolean? = null

    fun canWrite(ctx: Context) = canWriteForTest ?: runCatching { Settings.System.canWrite(ctx) }.getOrDefault(false)

    fun read(ctx: Context): List<Pair<String, String>> = listOf(
        "parlaklık" to (value(ctx)?.let { "$it / 255" } ?: "okunamadı"),
        "mod" to mode(ctx),
        "ayar değiştirme izni" to if (canWrite(ctx)) "var" else "yok",
    )

    fun openPermission(a: Activity) {
        Yol.row(a, "parlaklik", "akis", "izin ayarı açıldı", if (canWrite(a)) "var" else "yok")
        runCatching { a.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${a.packageName}"))) }
            .onFailure { Yol.row(a, "parlaklik", "akis", "izin ayarı açılamadı", it.javaClass.simpleName) }
    }

    /** One step down, the old value back after [HOLD_MS]. false when not allowed or already running. */
    fun stepTest(ctx: Context, done: () -> Unit): Boolean {
        val app = ctx.applicationContext
        if (testing || !canWrite(app)) return false
        val old = value(app) ?: return false
        val new = (old - STEP).coerceAtLeast(1)
        if (new == old) { last = "Parlaklık zaten en düşükte ($old)"; return false }
        testing = true
        // Saved before writing: the restore survives Keşif being closed.
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("old", old).commit()
        val main = Handler(Looper.getMainLooper())
        val wrote = runCatching { Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, new) }.getOrDefault(false)
        Yol.row(app, "parlaklik", "deneme", "kısıldı", "$old → $new · yazma ${if (wrote) "tamam" else "olmadı"} · mod ${mode(app)}")
        main.postDelayed({ Yol.row(app, "parlaklik", "deneme", "300 ms sonra okunan", "${value(app)}") }, 300)
        main.postDelayed({
            val back = restore(app, "3 sn sonra")
            last = "$old → $new → ${back ?: "?"}" + if (back == old) " · eski değere döndü" else " · DİKKAT: eski değer $old"
            testing = false
            done()
        }, HOLD_MS)
        return true
    }

    /** Writes the saved old value back, if any; returns what reads back. */
    fun restore(ctx: Context, why: String): Int? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("old")) return value(ctx)
        val old = p.getInt("old", -1)
        val ok = old >= 0 && runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, old) }.getOrDefault(false)
        val back = value(ctx)
        Yol.row(ctx, "parlaklik", "deneme", "geri yazıldı ($why)", "$old · okunan $back · ${if (ok) "tamam" else "yazılamadı"}")
        if (ok) p.edit().remove("old").commit()
        return back
    }

    /** At start: a test interrupted by Keşif closing is finished here. */
    fun restorePending(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains("old") && !testing && canWrite(ctx)) restore(ctx, "Keşif yeniden açıldı")
    }
}

class BrightnessPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val now = ui.label("", 16f, Palette.text, mono = true)
    private val result = ui.label("", 14f, Palette.text, mono = true)
    private val test = ui.button("Bir kademe kıs, 3 sn sonra geri al", primary = true) { confirm() }
    private var logged = false

    init {
        val (r, content) = ui.frame("6 · Parlaklık", "Okur · izin varsa bir kademe kısıp geri alır", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Önce değeri oku. “Ayarları değiştirme” izni verirsen bir kademe (≈%10) kısıp 3 sn sonra eski değere döner. Mod (elle/otomatik) değiştirilmez.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(now.apply { setPadding(0, 0, 0, ui.dp(10)) })
            addView(ui.button("Ayarları değiştirme iznini aç", primary = false) { Brightness.openPermission(a) },
                LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(56)).apply { bottomMargin = ui.dp(10) })
            addView(test, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(60)))
        }
        ui.card(content) {
            addView(ui.label("Son deneme", 17f, Palette.accent, bold = true))
            addView(result.apply { setPadding(0, ui.dp(6), 0, 0) })
        }
    }

    override fun onShow() {
        if (!logged) { logged = true; Yol.rows(a, "parlaklik", "okuma", Brightness.read(a)) }
    }

    private fun confirm() {
        android.app.AlertDialog.Builder(a)
            .setTitle("Parlaklık denemesi")
            .setMessage("Ekran ${Brightness.STEP}/255 kadar kararacak ve 3 sn sonra ${Brightness.value(a)} değerine dönecek.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Dene") { _, _ ->
                if (Brightness.stepTest(a) { tick() }) result.text = "Kısıldı; 3 sn sonra geri gelecek…"
                else result.text = Brightness.last.ifEmpty { "Denenemedi: izin yok ya da değer okunamadı." }
            }.show()
    }

    override fun tick() {
        now.text = Brightness.read(a).joinToString("\n") { "${it.first}: ${it.second}" } +
            if (Brightness.mode(a) == "otomatik") "\nMod otomatik: değer yazılsa da ekran değişmeyebilir; bu da bir bulgu." else ""
        val ok = Brightness.canWrite(a) && !Brightness.testing
        test.isEnabled = ok; test.alpha = if (ok) 1f else 0.45f
        if (!Brightness.testing && Brightness.last.isNotEmpty()) result.text = Brightness.last
        else if (result.text.isEmpty()) result.text = "Henüz denenmedi."
    }
}
