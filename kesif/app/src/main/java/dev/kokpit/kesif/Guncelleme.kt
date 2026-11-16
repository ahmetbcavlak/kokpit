package dev.kokpit.kesif

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Serves the one downloaded APK to the system package installer, read-only, as a content:// URI
 * (Android 7+ refuses file:// URIs between apps). The same job as AndroidX FileProvider, without
 * adding the library. Not exported: the installer gets access only through the one-off read grant
 * on the install intent.
 */
class ApkSaglayici : ContentProvider() {
    companion object {
        const val AUTHORITY = "dev.kokpit.kesif.apk"
        const val NAME = "kokpit-kesif.apk"
        val URI: Uri = Uri.parse("content://$AUTHORITY/$NAME")
    }

    private fun file(): File? = context?.let { Updater.file(it) }?.takeIf { it.isFile }

    override fun onCreate() = true
    override fun getType(uri: Uri) = if (uri.lastPathSegment == NAME) "application/vnd.android.package-archive" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.lastPathSegment != NAME || mode != "r") throw SecurityException("yalnız okunur")
        val f = file() ?: throw java.io.FileNotFoundException(NAME)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.lastPathSegment != NAME) return null
        val f = file() ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> NAME; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/**
 * "Güncellemeyi dene" (Keşif 0.9, wave A: remote update). Downloads the latest Keşif APK from the
 * repo's download link, compares versions and the signing certificate with the installed Keşif, and
 * opens the system installer for the user to confirm. Keşif never installs anything itself.
 *
 * The installer replaces Keşif's own process, so the outcome is usually seen at the next start:
 * [checkAfterStart] compares the installed version with the one recorded before the installer opened.
 */
object Updater {
    const val URL_APK = "https://github.com/ahmetbcavlak/kokpit/raw/main/dist/kokpit-kesif.apk"
    const val REQUEST = 31
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val PREFS = "guncelleme"

    @Volatile var status = "İndirilmedi."; private set
    @Volatile var running = false; private set
    /** True when the downloaded file is Keşif and signed with the installed Keşif's certificate. */
    @Volatile var installable = false; private set
    @Volatile private var downloadedVersion: Pair<Long, String>? = null

    fun file(ctx: Context) = File(ctx.cacheDir, "guncelleme/${ApkSaglayici.NAME}")

    @Suppress("DEPRECATION")
    private fun code(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signatures(p: PackageInfo?): Set<String> =
        p?.signatures?.map { sha(it.toByteArray()) }?.toSet().orEmpty()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun installed(ctx: Context): PackageInfo? =
        @Suppress("DEPRECATION") runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES) }.getOrNull()

    fun canInstall(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 26) (if (ctx.packageManager.canRequestPackageInstalls()) "izin var" else "izin yok")
        else @Suppress("DEPRECATION") runCatching {
            if (Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS) == 1) "izin var" else "izin yok"
        }.getOrDefault("bilinmiyor")

    fun download(ctx: Context, done: () -> Unit): Boolean {
        if (running) return false
        running = true
        installable = false
        val app = ctx.applicationContext
        status = "İndiriliyor…"
        thread(name = "kesif-guncelleme", isDaemon = true) {
            val rows = ArrayList<Pair<String, String>>()
            try {
                val f = file(app).apply { parentFile?.mkdirs() }
                val tmp = File(f.parentFile, "${f.name}.part")
                val t0 = SystemClock.elapsedRealtime()
                var c: HttpURLConnection? = null
                val md = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    c = (URL(URL_APK).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = true; useCaches = false
                        setRequestProperty("User-Agent", "KokpitKesif/${BuildConfigCompat.version}")
                    }
                    val code = c.responseCode
                    rows += "HTTP" to "$code · ${c.url.host}"
                    if (code != 200) throw IllegalStateException("HTTP $code")
                    c.inputStream.use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) throw IllegalStateException("20 MB’tan büyük")
                                md.update(buf, 0, n); out.write(buf, 0, n)
                                status = "İndiriliyor… ${total / 1024} KB"
                            }
                            out.fd.sync()
                        }
                    }
                } finally { runCatching { c?.disconnect() } }
                if (!tmp.renameTo(f)) throw IllegalStateException("dosya taşınamadı")
                val ms = SystemClock.elapsedRealtime() - t0
                rows += "indirme" to "${total / 1024} KB · $ms ms"
                rows += "SHA-256" to md.digest().joinToString("") { "%02x".format(it) }

                @Suppress("DEPRECATION")
                val dl = app.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES)
                val me = installed(app)
                if (dl == null) throw IllegalStateException("APK okunamadı")
                downloadedVersion = code(dl) to dl.versionName.orEmpty()
                val same = signatures(dl).isNotEmpty() && signatures(dl) == signatures(me)
                rows += "indirilen" to "${dl.packageName} ${dl.versionName} (${code(dl)})"
                rows += "yüklü" to "${me?.versionName} (${me?.let { code(it) }})"
                rows += "karşılaştırma" to when {
                    me == null -> "yüklü sürüm okunamadı"
                    code(dl) > code(me) -> "indirilen daha yeni"
                    code(dl) == code(me) -> "aynı sürüm (yine de yükleyici denenebilir)"
                    else -> "indirilen daha eski"
                }
                rows += "imza" to if (same) "yüklü Keşif ile aynı" else "FARKLI: yükleyici açılmaz"
                installable = dl.packageName == app.packageName && same
                status = rows.joinToString("\n") { "${it.first}: ${it.second}" }
            } catch (t: Throwable) {
                rows += "hata" to "${t.javaClass.simpleName} ${t.message.orEmpty().take(160)}"
                status = rows.joinToString("\n") { "${it.first}: ${it.second}" }
            } finally {
                Yol.rows(app, "guncelleme", "indirme", rows + ("kurulum izni" to canInstall(app)))
                running = false
                Handler(Looper.getMainLooper()).post(done)
            }
        }
        return true
    }

    fun openPermissionSettings(a: Activity) {
        Yol.row(a, "guncelleme", "akis", "izin ayarı açıldı", canInstall(a))
        val i = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)
        runCatching { a.startActivity(i) }.onFailure {
            Yol.row(a, "guncelleme", "akis", "izin ayarı açılamadı", it.javaClass.simpleName)
            runCatching { a.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        }
    }

    /** Opens the system installer; the user decides. false when there is nothing safe to open. */
    fun openInstaller(a: Activity): Boolean {
        if (!installable || !file(a).isFile) return false
        val dv = downloadedVersion
        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("from", installed(a)?.let { code(it) } ?: -1L)
            .putLong("to", dv?.first ?: -1L)
            .putLong("at", System.currentTimeMillis())
            .apply()
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(ApkSaglayici.URI, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, a.packageName)
        }
        return runCatching { a.startActivityForResult(i, REQUEST) }.fold(
            { Yol.row(a, "guncelleme", "akis", "yükleyici açıldı", "${dv?.second} (${dv?.first}) · kurulum izni: ${canInstall(a)}"); true },
            { Yol.row(a, "guncelleme", "akis", "yükleyici açılamadı", "${it.javaClass.simpleName} ${it.message.orEmpty()}"); false },
        )
    }

    /** The installer came back without replacing Keşif (cancelled or failed). */
    fun onResult(ctx: Context, resultCode: Int, data: Intent?) {
        val text = when (resultCode) {
            Activity.RESULT_OK -> "tamam"
            Activity.RESULT_CANCELED -> "vazgeçildi"
            Activity.RESULT_FIRST_USER -> "hata"
            else -> "kod $resultCode"
        }
        val detail = data?.extras?.let { b -> b.keySet().joinToString(" · ") { "${it.substringAfterLast('.')}=${runCatching { @Suppress("DEPRECATION") b.get(it) }.getOrNull()}" } }.orEmpty()
        status += "\nyükleyici: $text"
        Yol.row(ctx, "guncelleme", "akis", "yükleyici sonucu", text, detail)
    }

    /** At every start: was an update started, and did Keşif's version change since? */
    fun checkAfterStart(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = installed(ctx)?.let { code(it) } ?: return
        val to = p.getLong("to", -1L)
        if (to < 0) return
        val from = p.getLong("from", -1L)
        val at = p.getLong("at", 0L)
        val result = if (now == to && now != from) "güncellendi" else if (now == to) "aynı sürüm yeniden kuruldu ya da kurulmadı" else "kurulmadı"
        Yol.row(ctx, "guncelleme", "akis", "güncelleme sonrası açılış", "$from → $now ($result)",
            "yükleyici açılışından ${(System.currentTimeMillis() - at) / 1000} sn sonra")
        status = "Son deneme: $from → $now ($result)"
        p.edit().remove("to").remove("from").remove("at").apply()
    }
}

class UpdatePage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val perm = ui.label("", 15f, Palette.text, bold = true)
    private val status = ui.label("", 14f, Palette.text, mono = true)
    private val install = ui.button("Yükleyiciyi aç", primary = true) { openInstaller() }

    init {
        val (r, content) = ui.frame("2 · Güncellemeyi dene", "GitHub’daki son Keşif · yüklemeyi sen onaylarsın", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("1) İndir: sürüm ve imza yüklü Keşif’le karşılaştırılır. 2) İzin yoksa “Bilinmeyen uygulamaları yükle” ayarında Keşif’e izin ver. 3) Yükleyiciyi aç ve onayla. Keşif kendi üzerine kurulurken kapanır; yeniden açınca sonucu yazar.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(perm.apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(ui.button("1 · İndir ve karşılaştır", primary = true) {
                if (Updater.download(a) { tick() }) tick()
            }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(60)).apply { bottomMargin = ui.dp(10) })
            addView(ui.button("2 · Kurulum izni ayarını aç", primary = false) { Updater.openPermissionSettings(a) },
                LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(56)).apply { bottomMargin = ui.dp(10) })
            addView(install, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(60)))
        }
        ui.card(content) {
            addView(ui.label("Durum", 17f, Palette.accent, bold = true))
            addView(status.apply { setPadding(0, ui.dp(6), 0, 0) })
        }
    }

    private var lastPerm = ""
    override fun onShow() { lastPerm = "" }

    private fun openInstaller() {
        if (!Updater.openInstaller(a)) android.widget.Toast.makeText(a, "Önce indir; imza aynı olmalı", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun tick() {
        val p = Updater.canInstall(a)
        if (p != lastPerm) {
            // Logged on change, so coming back from the settings screen shows the user's choice.
            if (lastPerm.isNotEmpty()) Yol.row(a, "guncelleme", "akis", "kurulum izni değişti", "$lastPerm → $p")
            lastPerm = p
        }
        perm.text = "Kurulum izni: $p"
        perm.setTextColor(if (p == "izin var") Palette.live else Palette.accent)
        status.text = Updater.status
        val ok = Updater.installable && !Updater.running
        install.isEnabled = ok; install.alpha = if (ok) 1f else 0.45f
    }
}
