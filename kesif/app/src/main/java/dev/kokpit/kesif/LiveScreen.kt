package dev.kokpit.kesif

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The live vehicle-data screen, laid out for the 768×1024 portrait head unit.
 * Views are built once; [tick] only changes their text and colours.
 */
class LiveScreen(private val a: Activity, private val onReport: () -> Unit, private val address: () -> String?) {

    private inner class Card(val signal: LiveProbe.Signal?, title: String) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val bgShape = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(16).toFloat() }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleView = label(title, 15f, Palette.muted, bold = true)
        val item = label(signal?.item ?: "", 14f, Palette.faint)
        val status = label("", 13f, Palette.faint)
        val big = label("—", 28f, Palette.text, bold = true)
        val detail = label("", 12f, Palette.muted, mono = true)
        val meta = label("", 12f, Palette.faint)

        init {
            box.background = bgShape
            head.addView(titleView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            head.addView(item)
            box.addView(head)
            box.addView(status.apply { setPadding(0, dp(2), 0, dp(6)) })
            box.addView(big)
        }

        fun finish(extra: View? = null, extraH: Int = 0) {
            extra?.let { box.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, extraH).apply { topMargin = dp(8); bottomMargin = dp(6) }) }
            box.addView(detail.apply { setPadding(0, dp(6), 0, 0) })
            box.addView(meta.apply { setPadding(0, dp(4), 0, 0) })
        }

        fun state(sv: LiveProbe.SignalView, now: Long) {
            val c = Palette.of(sv.status)
            status.text = "● " + LiveProbe.statusText(sv.status)
            status.setTextColor(c)
            meta.text = if (sv.changes == 0) "değişim yok" else "${sv.changes} değişim · son: ${LiveProbe.ago(sv.lastChangeAt, now)}"
            // A short amber edge right after a change, so the eye catches which card reacted.
            val fresh = sv.lastChangeAt > 0 && now - sv.lastChangeAt < 1500
            bgShape.setStroke(dp(3), if (fresh) Palette.accent else Color.TRANSPARENT)
            detail.text = sv.keys.joinToString("\n") { k ->
                val v = k.value.ifEmpty { "—" }
                val n = if (k.changes > 0) " ·${k.changes}" else ""
                val short = LiveProbe.shortKey(k.key)
                val tail = if (short == k.label) "" else "  " + short.substringAfterLast('/')
                "${k.label.take(12)} $v$n$tail"
            }.ifEmpty { "Bu sinyal için anahtar henüz görülmedi." }
        }
    }

    /** Fixed bar on top (recording and markers), cards scroll underneath. */
    val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.bg) }
    internal val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(32)) }

    private val sig = LiveProbe.signals.associateBy { it.id }
    private val doors = Card(sig["kapi"], "Kapılar ve bagaj")
    private val speed = Card(sig["hiz"], "Hız")
    private val gear = Card(sig["vites"], "Vites")
    private val wheel = Card(sig["direksiyon"], "Direksiyon")
    private val lights = Card(sig["far"], "Far, sinyal, dörtlü")
    private val radar = Card(sig["radar"], "Park sensörü")
    private val air = Card(sig["klima"], "Klima")
    private val unknown = Card(null, "Listede olmayan, değişen değişkenler")

    private val doorsView = DoorsView(a)
    private val wheelView = WheelView(a)
    private val radarView = RadarView(a)

    private val recPill = label("", 15f, Palette.muted, bold = true)
    private val recButton = button("Kaydı başlat", primary = true) { toggleRecording() }
    private val recInfo = label("", 13f, Palette.muted)
    private val diag = label("", 12f, Palette.faint, mono = true)
    private val addressView = label("", 16f, Palette.text)
    private val qr = ImageView(a)
    private val pkgInfo = label("Paketler sayılıyor…", 14f, Palette.muted)
    private val pkgStatus = label("", 14f, Palette.text)
    private val pkgButton = button("Paketleri dışa aktar", primary = false) { exportPackages() }
    private val winStatus = label("", 14f, Palette.text)
    private val svcResult = label("Henüz denenmedi", 13f, Palette.text, mono = true)
    private val svcButton = button("CanService bağlanma testi", primary = false) { serviceTest() }
    private var qrFor: String? = null

    init { build() }

    private fun build() {
        // ---- fixed top bar ----
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Palette.surface)
        }
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Keşif ${BuildConfigCompat.version} · Canlı araç verisi", 20f, Palette.text, bold = true))
                addView(recInfo)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(recPill, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(12) })
            addView(recButton, LinearLayout.LayoutParams(dp(170), dp(54)))
            bar.addView(this)
        }
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            val items = listOf("3.1\nKapı", "3.3\nFar", "3.4\nDireksiyon", "3.5\nPark", "3.6\nVites", "3.7\nKlima", "9.4\nHız", "Serbest\nişaret")
            items.forEachIndexed { i, t ->
                addView(button(t, primary = false, size = 14f) { mark(t) },
                    LinearLayout.LayoutParams(0, dp(58), 1f).apply { if (i < items.size - 1) rightMargin = dp(6) })
            }
            bar.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(a).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- cards ----
        content.addView(label("Gri: veri yok · Kehribar: değer var ama bu oturumda değişmedi, eski olabilir · Yeşil: değişti. Sen bir şey yapınca yeşile dönüyorsa veri canlı. İşarete dokunmak kaydı da başlatır.", 13f, Palette.faint)
            .apply { setPadding(0, 0, 0, dp(8)) })

        doors.finish(doorsView, dp(104))
        speed.finish()
        gear.finish()
        wheel.finish(wheelView, dp(64))
        lights.finish()
        radar.finish(radarView, dp(64))
        air.finish()
        unknown.finish()
        unknown.big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)

        row(doors.box to 1.15f, speed.box to 1f, gear.box to 1f)
        row(wheel.box to 1f, lights.box to 1f, radar.box to 1.4f)
        row(air.box to 1f)
        row(unknown.box to 1f)

        // iPad
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(16).toFloat() }
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("iPad’de canlı izle ve kaydı indir", 17f, Palette.accent, bold = true))
                addView(addressView.apply { setPadding(0, dp(6), 0, 0) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(qr, LinearLayout.LayoutParams(dp(120), dp(120)))
            content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        // Package export
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(16).toFloat() }
            addView(label("Paketleri dışa aktar", 17f, Palette.accent, bold = true))
            addView(label("com.hcn.* ve Ayarlar APK’ları (bölünmüş parçalar dahil), AutoSetting ve MtkSettings oat klasörleri, /data/config (5 MB üstü ve .apk hariç) ve getprop çıktısı kayıt klasörüne kopyalanır; okunamayanlar paketler.csv’ye yazılır. iPad’den /paketler adresinden indirilir. Sistem dosyalarını yalnız okur; kopyalar bu uygulamanın klasörüne yazılır ve en az aşağıdaki boyut kadar yer kaplar.", 13f, Palette.faint).apply { setPadding(0, dp(4), 0, dp(6)) })
            addView(pkgInfo)
            addView(pkgStatus.apply { setPadding(0, dp(4), 0, dp(8)) })
            addView(pkgButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(60)))
            content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        // Counting the packages asks PackageManager for every app; done once, off the main thread.
        kotlin.concurrent.thread(isDaemon = true) {
            val src = runCatching { PackageExporter.sources(a) }.getOrDefault(emptyList())
            val text = "${src.map { it.pkg }.distinct().size} paket · ${src.size} dosya · ${PackageExporter.mb(src.sumOf { it.bytes })}" +
                (src.count { !it.readable }.takeIf { it > 0 }?.let { " · $it dosya okunamıyor" } ?: "")
            a.runOnUiThread { pkgInfo.text = text }
        }

        // Diagnostics: window log and service check
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Palette.surface); cornerRadius = dp(16).toFloat() }
            addView(label("Kapı uyarısı penceresi günlüğü", 17f, Palette.accent, bold = true))
            addView(label("com.hcn.autocan’ın açtığı pencereleri (ör. CanDoorWindow) saatiyle pencere-olaylari.csv’ye yazar. Android tüm uygulamaların pencere olaylarını iletir; diğerlerini görür ama yazmaz. Hiçbir şeye dokunmaz. Bir kez Erişilebilirlik ayarlarından açılması gerekir.", 13f, Palette.faint).apply { setPadding(0, dp(4), 0, dp(6)) })
            addView(winStatus.apply { setPadding(0, 0, 0, dp(8)) })
            addView(button("Erişilebilirlik ayarlarını aç", primary = false) {
                runCatching { a.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    .onFailure { Toast.makeText(a, "Ayarlar açılamadı", Toast.LENGTH_SHORT).show() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
            addView(label("CanService erişilebilirliği", 17f, Palette.accent, bold = true).apply { setPadding(0, dp(16), 0, 0) })
            addView(label("Tek bir bindService() denemesi: bağlantı gelirse hemen kapatılır, hiçbir metot çağrılmaz; servis çalışmıyorsa başlatılmaz. Servis çalışıyorsa üreticinin bağlanma ve ayrılma kodu bir kez çalışır.", 13f, Palette.faint).apply { setPadding(0, dp(4), 0, dp(6)) })
            addView(svcResult.apply { setPadding(0, 0, 0, dp(8)) })
            addView(svcButton, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
            content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }

        content.addView(button("Ekran raporu (0.2’deki kimlik kartı)", primary = false) { onReport() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(60)).apply { bottomMargin = dp(10) })
        content.addView(diag)
    }

    private fun column(vararg v: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        v.forEachIndexed { i, child ->
            addView(child, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { if (i < v.size - 1) bottomMargin = dp(12) })
        }
    }

    private fun row(vararg cells: Pair<View, Float>) {
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            cells.forEachIndexed { i, (v, w) ->
                addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, w).apply { if (i < cells.size - 1) rightMargin = dp(10) })
            }
            content.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }

    private fun toggleRecording() {
        if (Recorder.recording) Recorder.stop() else Recorder.start()
        tick()
    }

    private fun serviceTest() {
        svcResult.text = "Deneniyor…"
        ServiceProbe.runBindTest(a) {
            a.runOnUiThread {
                svcResult.text = (ServiceProbe.staticInfo(a) + ServiceProbe.lastResult.orEmpty())
                    .joinToString("\n") { "${it.label}: ${it.value}" }
            }
        }
    }

    private fun exportPackages() {
        if (!PackageExporter.start(a)) Toast.makeText(a, "Dışa aktarma zaten sürüyor", Toast.LENGTH_SHORT).show()
        tick()
    }

    private fun mark(t: String) {
        val item = t.substringBefore('\n')
        Recorder.mark(if (item == "Serbest") "serbest" else item)
        Toast.makeText(a, "${t.replace('\n', ' ')} işaretlendi", Toast.LENGTH_SHORT).show()
        tick()
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val views = LiveProbe.signals().associateBy { it.signal.id }

        views["kapi"]?.let { sv ->
            doors.state(sv, now)
            val vals = sv.keys.take(5).map { it.value }
            doorsView.values = vals
            val open = sv.keys.take(5).filter { it.value == "2" }.map { it.label }
            // 1 = closed, 2 = open comes from reading the vendor code; until the car confirms it,
            // anything else is shown raw rather than guessed.
            doors.big.text = when {
                vals.all { it.isEmpty() } -> "veri yok"
                vals.all { it == "1" } -> "Hepsi kapalı"
                vals.all { it == "1" || it == "2" } -> "AÇIK: " + open.joinToString(", ")
                else -> "ham: " + vals.joinToString(" ") { it.ifEmpty { "—" } }
            }
            doors.big.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (open.isEmpty()) 22f else 18f)
            doors.big.setTextColor(if (open.isEmpty()) Palette.text else Palette.accent)
        }
        views["hiz"]?.let { sv ->
            speed.state(sv, now)
            // The unit is not confirmed yet (9.4 compares it with the instrument cluster).
            speed.big.text = sv.keys.firstOrNull()?.value?.ifEmpty { null } ?: "—"
            speed.meta.text = speed.meta.text.toString() + " · ham değer, birim 9.4'te doğrulanacak"
        }
        views["vites"]?.let { sv ->
            gear.state(sv, now)
            val gk = sv.keys.firstOrNull()
            gear.big.text = gk?.value ?: "—"
            if (gk == null) gear.detail.text = "Vites anahtarı bilinmiyor. P → R → D yap; değişen satır aşağıdaki listeye düşer." +
                " Bir aday değişirse burada anahtar adıyla görünür."
        }
        views["direksiyon"]?.let { sv ->
            wheel.state(sv, now)
            val raw = sv.keys.firstOrNull()?.value.orEmpty()
            wheel.big.text = raw.ifEmpty { "—" }
            wheelView.angle = raw.toFloatOrNull()
        }
        views["far"]?.let { sv ->
            lights.state(sv, now)
            lights.big.text = sv.keys.take(3).joinToString(" · ") { it.value.ifEmpty { "—" } }
            lights.big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        views["radar"]?.let { sv ->
            radar.state(sv, now)
            radarView.values = sv.keys.take(8).map { it.value }
            radar.big.text = if (sv.status == LiveProbe.Status.NONE) "veri yok" else "8 sensör · ham"
            radar.big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            radar.detail.visibility = View.GONE
        }
        views["klima"]?.let { sv ->
            air.state(sv, now)
            val m = sv.keys.associate { it.label to it.value }
            air.big.text = if (sv.keys.all { it.value.isEmpty() }) "yayın gelmedi"
            else "Sol ${m["Sol"].orDash()} · Sağ ${m["Sağ"].orDash()} · Fan ${m["Fan"].orDash()}"
            air.big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }

        val unk = LiveProbe.unknownChanges(6)
        unknown.big.text = if (unk.isEmpty()) "Henüz yok" else "${unk.size} değişken değişti"
        unknown.status.text = "Vites ve far anahtarını burada arıyoruz · sürekli değişenler en altta"
        unknown.detail.text = unk.joinToString("\n") { "${it.value.ifEmpty { "—" }} ·${it.changes}  ${it.key}  ${LiveProbe.ago(it.lastChangeAt, now)}" }
        unknown.meta.text = ""
        unknown.bgShape.setStroke(dp(3), if (unk.any { now - it.lastChangeAt < 1500 }) Palette.accent else Color.TRANSPARENT)

        // Recording
        if (Recorder.recording) {
            recPill.text = "● KAYIT ${Recorder.duration(now)}"
            recPill.setTextColor(Color.parseColor("#FF6B5B"))
            recButton.text = "Kaydı durdur"
            recInfo.text = "${Recorder.rowsText()} satır" + (Recorder.lastMark?.let { " · son işaret $it" } ?: "")
        } else {
            recPill.text = "kayıt kapalı"
            recPill.setTextColor(Palette.faint)
            recButton.text = "Kaydı başlat"
            recInfo.text = "Yalnız okur, araca hiçbir şey göndermez"
        }

        val url = address()
        addressView.text = if (url == null) "Ekran bir ağa bağlı değil. Telefonun hotspot’una bağla." else "$url\nKayıt dosyası: $url/kayit.csv"
        if (url != qrFor) {
            qrFor = url
            qr.setImageBitmap(url?.let { Qr.bitmap(it, dp(132), Color.BLACK, Color.WHITE) })
        }
        val ws = WindowLog.statusText(a)
        if (winStatus.text.toString() != ws) winStatus.text = ws
        svcButton.isEnabled = !ServiceProbe.running

        val ps = PackageExporter.statusText()
        if (pkgStatus.text.toString() != ps) pkgStatus.text = ps
        pkgButton.isEnabled = !PackageExporter.running
        pkgButton.alpha = if (PackageExporter.running) 0.5f else 1f

        diag.text = "Okuma: ${LiveProbe.method} · tarama ${LiveProbe.scanCount} · ${LiveProbe.scanKeys} değişken · ${LiveProbe.scanMs} ms" +
            (LiveProbe.lastError?.let { " · hata: $it" } ?: "")
    }

    private fun String?.orDash() = if (this.isNullOrEmpty()) "—" else this

    // --- view helpers ---

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(a).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.DEFAULT
        }
    }

    private fun button(t: String, primary: Boolean, size: Float = if (primary) 18f else 16f, onClick: () -> Unit) = Button(a).apply {
        text = t
        isAllCaps = false
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setTextColor(if (primary) Color.parseColor("#10161B") else Palette.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setLineSpacing(0f, 0.95f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(if (primary) Palette.accent else Palette.surface)
            cornerRadius = dp(14).toFloat()
            if (!primary) setStroke(dp(1), Palette.line)
        }
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
}
