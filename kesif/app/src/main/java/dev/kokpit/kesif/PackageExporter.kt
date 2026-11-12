package dev.kokpit.kesif

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Paketleri dışa aktar": copies the APK files of the vendor packages (every package whose name
 * starts with com.hcn.) and of com.android.settings into the app's own export folder, next to
 * the CSV recordings, so the iPad can download them for offline study.
 *
 * Read-only toward the system: the APKs are opened with FileInputStream from their install path
 * (applicationInfo.sourceDir and splitSourceDirs), which a normal app may read. No root, nothing
 * is written outside the app's folder, no package is changed.
 */
object PackageExporter {

    private const val SETTINGS = "com.android.settings"
    private const val MANIFEST = "paketler.csv"
    /** Kept free on top of what the copy needs, so the unit never runs out of space. */
    private const val SPARE_BYTES = 500L * 1024 * 1024

    fun isTarget(pkg: String) = pkg.startsWith("com.hcn.") || pkg == SETTINGS

    /** One APK file of a package: the base, or one of its splits. */
    data class Source(val pkg: String, val label: String, val version: String, val versionCode: Long,
                      val kind: String, val path: String, val bytes: Long, val readable: Boolean) {
        /** File name in the export folder: package, version and which part it is. */
        val exportName: String get() = safe("$pkg-$version-$kind") + ".apk"
    }

    data class Result(val source: Source, val status: String, val sha256: String)

    @Volatile var running = false; private set
    @Volatile var doneBytes = 0L; private set
    @Volatile var totalBytes = 0L; private set
    @Volatile var current: String? = null; private set
    @Volatile var lastSummary: String? = null; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var finishedAt = 0L; private set

    @Volatile private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!running) cleanTmp(dir(ctx))
    }

    /** Half-copied files left by a power cut; never served, only taking space. */
    private fun cleanTmp(d: File) {
        d.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun dir(ctx: Context) = File(ctx.filesDir, "kayit/paketler")

    /** Every APK file to export, found through PackageManager. Splits are included when present. */
    fun sources(ctx: Context): List<Source> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        // Disabled vendor packages count too: their APK is still on the system partition.
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        val pkgs: List<PackageInfo> = runCatching { pm.getInstalledPackages(flags) }.getOrDefault(emptyList())
        return pkgs.filter { isTarget(it.packageName) }.sortedBy { it.packageName }.flatMap { p ->
            val ai = p.applicationInfo ?: return@flatMap emptyList()
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName)
            val version = p.versionName ?: "?"
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
            fun src(kind: String, path: String): Source {
                val f = File(path)
                return Source(p.packageName, label, version, code, kind, path, f.length(), f.canRead())
            }
            val base = ai.sourceDir?.let { listOf(src("base", it)) }.orEmpty()
            // A split is named after its file, e.g. split_config.xhdpi.apk → split_config.xhdpi.
            val splits = ai.splitSourceDirs.orEmpty().filterNotNull()
                .map { src(File(it).name.removeSuffix(".apk"), it) }
            base + splits
        }
    }

    /** Starts the export on a background thread. Returns false if one is already running. */
    @Synchronized
    fun start(ctx: Context): Boolean {
        if (running) return false
        running = true
        lastError = null
        lastSummary = null
        doneBytes = 0L
        totalBytes = 0L
        val app = ctx.applicationContext
        try {
            thread(name = "kesif-paket") {
                try {
                    export(app)
                } catch (t: Throwable) {
                    lastError = "Dışa aktarma durdu: ${t.javaClass.simpleName} ${t.message.orEmpty()}"
                } finally {
                    current = null
                    finishedAt = System.currentTimeMillis()
                    running = false
                }
            }
        } catch (t: Throwable) {
            running = false
            lastError = "Başlatılamadı: ${t.javaClass.simpleName}"
            return false
        }
        return true
    }

    private fun export(ctx: Context) {
        val out = dir(ctx).apply { mkdirs() }
        cleanTmp(out)
        val all = sources(ctx)
        // Exports of versions no longer installed (after a firmware update) are removed from our
        // own folder, so space use does not grow with every update.
        val keep = all.map { it.exportName }.toSet()
        out.listFiles { f -> f.name.endsWith(".apk") && f.name !in keep }?.forEach { it.delete() }
        val todo = all.filter { it.readable }
        totalBytes = todo.sumOf { it.bytes }
        doneBytes = 0L

        // Space check first: only what is not already exported counts.
        val needed = todo.filterNot { upToDate(File(out, it.exportName), it) }.sumOf { it.bytes }
        if (out.usableSpace < needed + SPARE_BYTES) {
            lastError = "Yer yetmiyor: ${mb(needed)} gerekiyor, ${mb(out.usableSpace)} boş (${mb(SPARE_BYTES)} pay bırakılıyor)."
            return
        }

        val results = ArrayList<Result>()
        var stopped: String? = null
        for (s in all) {
            if (stopped != null) { results += Result(s, "atlandı ($stopped)", ""); continue }
            if (!s.readable) { results += Result(s, "okunamadı (izin yok)", ""); continue }
            current = s.exportName
            val dest = File(out, s.exportName)
            results += try {
                if (upToDate(dest, s)) Result(s, "zaten var", sha256(dest))
                else Result(s, "kopyalandı", copy(File(s.path), dest))
            } catch (e: java.io.IOException) {
                // A write error (often a full disk) stops the run instead of failing file after file.
                stopped = "önceki dosyada yazma hatası"
                lastError = "Yazma hatası: ${e.message.orEmpty()}"
                Result(s, "hata: ${e.message.orEmpty()}", "")
            } catch (t: Throwable) {
                Result(s, "hata: ${t.javaClass.simpleName} ${t.message.orEmpty()}", "")
            }
        }
        writeManifest(ctx, out, results)
        val ok = results.count { it.status == "kopyalandı" || it.status == "zaten var" }
        val pkgCount = results.map { it.source.pkg }.distinct().size
        lastSummary = "$pkgCount paket · $ok/${results.size} dosya · ${mb(results.filter { it.sha256.isNotEmpty() }.sumOf { it.source.bytes })}" +
            (results.count { it.status.startsWith("hata") || it.status.startsWith("okunamadı") }.takeIf { it > 0 }?.let { " · $it dosya alınamadı" } ?: "")
    }

    /** Same size and not older than the installed file: no need to copy again. */
    private fun upToDate(dest: File, s: Source): Boolean {
        val src = File(s.path)
        return dest.exists() && dest.length() == s.bytes && dest.lastModified() >= src.lastModified()
    }

    /**
     * Plain stream copy into a temporary file, then a rename, so a half-copied APK never carries
     * the final name. The SHA-256 is worked out on the way, for checking the file on the iPad.
     */
    private fun copy(src: File, dest: File): String {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        doneBytes += n
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw java.io.IOException("ad değiştirilemedi")
        } catch (t: Throwable) {
            tmp.delete() // never leave a partial copy behind
            throw t
        }
        return hex(md.digest())
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); doneBytes += n }
        }
        return hex(md.digest())
    }

    /** paketler.csv: what was exported, from where, with checksums, plus the unit's build identity. */
    private fun writeManifest(ctx: Context, out: File, results: List<Result>) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder("﻿")
        sb.append("# Kokpit Keşif ${BuildConfigCompat.version} · paket dışa aktarma · $stamp\n")
        DeviceInfo.lines().forEach { sb.append("# ").append(it.label).append(": ").append(it.value).append('\n') }
        sb.append("paket,ad,surum,surum_kodu,parca,kaynak_yol,dosya,bayt,sha256,durum\n")
        for (r in results) {
            val s = r.source
            sb.append(listOf(s.pkg, s.label, s.version, s.versionCode.toString(), s.kind, s.path, s.exportName,
                s.bytes.toString(), r.sha256, r.status).joinToString(",") { csv(it) }).append('\n')
        }
        val tmp = File(out, "$MANIFEST.tmp")
        FileOutputStream(tmp).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        val dest = File(out, MANIFEST)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.delete(); lastError = "paketler.csv yazılamadı" }
    }

    // ---------- files for the web server ----------

    /** Finished files in the export folder (never a .tmp being written). */
    fun files(ctx: Context? = app): List<File> =
        if (ctx == null) emptyList() else dir(ctx).listFiles { f -> f.isFile && (f.name.endsWith(".apk") || f.name == MANIFEST) }
            ?.sortedWith(compareBy<File> { it.name != MANIFEST }.thenBy { it.name }) ?: emptyList()

    /** Only names from [files] are accepted, never a path from the request. */
    fun byName(name: String): File? = files().firstOrNull { it.name == name }

    fun statusText(): String = when {
        running -> "Kopyalanıyor: ${current ?: "…"} · ${mb(doneBytes)} / ${mb(totalBytes)}" +
            (if (totalBytes > 0) " (%${(doneBytes * 100 / totalBytes).coerceIn(0, 100)})" else "")
        lastError != null -> lastError!!
        lastSummary != null -> "Bitti: $lastSummary"
        files().any { it.name.endsWith(".apk") } -> "Klasörde ${files().count { it.name.endsWith(".apk") }} APK var (önceki dışa aktarmadan)"
        else -> "Henüz dışa aktarılmadı"
    }

    // ---------- helpers ----------

    fun mb(b: Long) = "%.1f MB".format(Locale.US, b / 1_048_576.0)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun csv(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
