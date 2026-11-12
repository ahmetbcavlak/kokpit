package dev.kokpit.kesif

import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
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
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0E1114")
    private val surface = Color.parseColor("#171C21")
    private val text = Color.parseColor("#ECEEEA")
    private val muted = Color.parseColor("#9AA5AD")
    private val accent = Color.parseColor("#F0A030")

    private lateinit var root: LinearLayout
    private lateinit var reportView: ScrollView
    private var live: LiveScreen? = null
    private var showingLive = true
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (showingLive) live?.tick()
            main.postDelayed(this, LiveProbe.FAST_MS)
        }
    }
    @Volatile private var report: Report? = null
    private var server: ReportServer? = null
    private var port = 8080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(bg)
        BuildConfigCompat.version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(48))
        }
        reportView = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        }
        LiveProbe.start(this)
        Recorder.init(this)
        startServer()
        showLive()
    }

    override fun onStart() {
        super.onStart()
        LiveProbe.visible = true
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onStop() {
        // CarPlay or the camera in front: no screen updates; the full scan pauses unless recording.
        LiveProbe.visible = false
        main.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        server?.stop()
        server = null
        if (!isChangingConfigurations) {
            // Closes the file but keeps the recording on, so the next start continues it.
            Recorder.suspend()
            LiveProbe.stop(this)
        }
        super.onDestroy()
    }

    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        if (!showingLive) showLive() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private var addressAt = 0L
    private var addressCache: String? = null

    /** Listing network interfaces is not free on this CPU; the answer is reused for 5 seconds. */
    private fun address(): String? {
        if (server == null) return null
        val now = SystemClock.elapsedRealtime()
        if (now - addressAt < 5000 && addressAt != 0L) return addressCache
        addressAt = now
        val ip = ReportBuilder.localIps().map { it.second }.firstOrNull { !it.startsWith("127.") }
        addressCache = ip?.let { "http://$it:$port" }
        return addressCache
    }

    private fun showLive() {
        showingLive = true
        val screen = live ?: LiveScreen(this, onReport = { showReport() }, address = { address() }).also { live = it }
        setContentView(screen.root)
        screen.tick()
    }

    private fun showReport() {
        showingLive = false
        setContentView(reportView)
        if (report == null) load() else render(report!!)
    }

    private fun load() {
        root.removeAllViews()
        header()
        root.addView(label("Hazırlanıyor…", 20f, muted))
        thread {
            val r = runCatching { ReportBuilder.build(applicationContext) }.getOrNull()
            report = r
            main.post { if (showingLive) return@post; if (r != null) render(r) else showError() }
        }
    }

    private fun startServer() {
        if (server != null) return
        for (p in listOf(8080, 8090, 8765)) {
            val s = ReportServer(
                p,
                report = { LiveProbe.attach(report ?: ReportBuilder.build(applicationContext).also { report = it }) },
                apks = { ReportBuilder.apkFiles(applicationContext) },
            )
            if (runCatching { s.start() }.isSuccess) { server = s; port = p; return }
        }
    }

    private fun header() {
        root.addView(label("Kokpit Keşif", 34f, text, bold = true))
        root.addView(label("Ekranın kimlik kartı · yalnızca okur, hiçbir ayarı değiştirmez", 18f, muted).apply {
            setPadding(0, dp(4), 0, dp(12))
        })
        root.addView(button("← Canlı araç verisi") { showLive() },
            LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { bottomMargin = dp(16) })
    }

    private fun showError() {
        root.removeAllViews()
        header()
        root.addView(label("Bilgiler okunamadı. Uygulamayı kapatıp yeniden aç.", 22f, accent))
    }

    private fun render(base: Report) {
        startServer()
        val r = LiveProbe.attach(base)
        root.removeAllViews()
        header()

        // Summary
        card {
            addView(label("Özet", 24f, accent, bold = true))
            r.summary.forEach { addView(row(it.label, it.value, 20f)) }
        }

        // Open on iPad
        val ips = ReportBuilder.localIps().map { it.second }.filter { !it.startsWith("127.") }
        card {
            addView(label("Raporu iPad’de aç", 24f, accent, bold = true))
            if (ips.isEmpty() || server == null) {
                addView(label("Ekran bir ağa bağlı değil. Ekranı telefonunun hotspot’una bağla, sonra “Yenile”ye dokun.", 19f, text))
            } else {
                val url = "http://${ips.first()}:$port"
                addView(label("iPad’i de aynı hotspot’a bağla. iPad’in kamerasıyla bu kodu okut ya da Safari’de şu adresi aç:", 19f, text))
                addView(label(url, 28f, text, bold = true).apply { setPadding(0, dp(10), 0, dp(10)) })
                val size = dp(240)
                addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(Qr.bitmap(url, size, Color.BLACK, Color.WHITE))
                }, LinearLayout.LayoutParams(size, size).apply { gravity = Gravity.START })
                if (ips.size > 1) addView(label("Diğer adresler: " + ips.drop(1).joinToString { "http://$it:$port" }, 16f, muted))
                addView(label("Üretici uygulamalarının dosyaları: $url/apk", 18f, muted).apply { setPadding(0, dp(12), 0, 0) })
            }
        }

        // Actions
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("Raporu kaydet") { save(r) }, LinearLayout.LayoutParams(0, dp(76), 1f).apply { rightMargin = dp(12) })
            addView(button("Yenile") { load() }, LinearLayout.LayoutParams(0, dp(76), 1f))
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
        }

        // Flagged apps
        val flagged = r.apps.filter { it.flagged != null }
        card {
            addView(label("Önemli uygulamalar (${flagged.size})", 24f, accent, bold = true))
            flagged.forEach { addView(row(it.flagged ?: "", "${it.label}\n${it.pkg}", 17f)) }
        }

        // Sections
        r.sections.forEach { s ->
            card {
                addView(label(s.title, 22f, accent, bold = true))
                s.lines.forEach { addView(row(it.label, it.value, 16f)) }
            }
        }

        card {
            addView(label("Tüm uygulamalar (${r.apps.size})", 22f, accent, bold = true))
            r.apps.forEach { a ->
                val tag = listOfNotNull(if (a.system) "sistem" else null, if (!a.enabled) "kapalı" else null).joinToString(" · ")
                addView(label("${a.label} · ${a.pkg}${if (tag.isNotEmpty()) " · $tag" else ""}", 15f, text).apply {
                    setPadding(0, dp(4), 0, dp(4))
                })
            }
        }
    }

    private fun save(r: Report) {
        val name = "kokpit-kesif-${System.currentTimeMillis() / 1000}.txt"
        val where = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(r.toText().toByteArray()) }
                "İndirilenler/$name"
            } else {
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, name).writeText(r.toText())
                File(dir, name).path
            }
        }.getOrElse { "kaydedilemedi: ${it.message}" }
        Toast.makeText(this, "Kaydedildi: $where", Toast.LENGTH_LONG).show()
    }

    // --- small view helpers ---

    private fun card(block: LinearLayout.() -> Unit) {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(16).toFloat() }
            block()
        }
        root.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
    }

    private fun row(k: String, v: String, size: Float): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
        addView(label(k, size * 0.8f, muted))
        addView(label(v, size, text))
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextIsSelectable(false)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.parseColor("#10161B"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(14).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
