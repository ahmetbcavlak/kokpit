package dev.kokpit.kesif

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * "Paketleri dışa aktar": a diagnostic backup into the app's own export folder
 * (filesDir/kayit/paketler, inside the CSV recording folder), for offline study on the iPad.
 *
 *  1. APKs of every com.hcn.* package and of com.android.settings (sourceDir + splitSourceDirs)
 *  2. Everything readable under the vendor apps' compiled-code folders
 *     /system/app/AutoSetting/oat and /system/priv-app/MtkSettings/oat, folder structure kept
 *  3. Everything readable under /data/config, except .apk files and files over 5 MB
 *  4. The full `getprop` output as getprop.txt
 *
 * Read-only toward the system: sources are only opened with FileInputStream; no root, nothing is
 * written outside the app's folder. A file the sandbox does not let us read (permissions, SELinux)
 * is listed in paketler.csv as "okunamadı" with its path, and the run goes on. Only a write error
 * on our side (usually a full disk) stops the run.
 */
object PackageExporter {

    private const val SETTINGS = "com.android.settings"
    private const val MANIFEST = "paketler.csv"
    private const val GETPROP = "getprop.txt"
    /** Mirrors of system folders go under this subfolder, e.g. sistem/data/config/… */
    private const val MIRROR = "sistem"
    /** Kept free on top of what the copy needs, so the unit never runs out of space. */
    private const val SPARE_BYTES = 500L * 1024 * 1024
    private const val CONFIG_MAX_BYTES = 5L * 1024 * 1024
    private const val MAX_DEPTH = 12
    /** At most this many files per scanned root, so a link to a huge tree cannot run away. */
    private const val MAX_FILES = 5000
    /** Suffix of a file being written; cannot clash with a real source name ending in .tmp. */
    private const val PART = ".kesif-part"

    /** Folders copied whole (whatever is readable). Variables only so tests can point them elsewhere. */
    internal var systemDirs = listOf("/system/app/AutoSetting/oat", "/system/priv-app/MtkSettings/oat")
    /** Copied without .apk files and without files over [CONFIG_MAX_BYTES]. */
    internal var configDir = "/data/config"

    fun isTarget(pkg: String) = pkg.startsWith("com.hcn.") || pkg == SETTINGS

    /** One APK file of a package: the base, or one of its splits. */
    data class Source(val pkg: String, val label: String, val version: String, val versionCode: Long,
                      val kind: String, val path: String, val bytes: Long, val readable: Boolean) {
        /** File name in the export folder: package, version and which part it is. */
        val exportName: String get() = safe("$pkg-$version-$kind") + ".apk"
    }

    /** One line of paketler.csv. */
    data class Row(val type: String, val sourcePath: String, val exportName: String, val bytes: Long,
                   val sha256: String, val status: String, val pkg: String = "", val label: String = "",
                   val version: String = "", val versionCode: String = "", val kind: String = "") {
        val ok get() = status == "kopyalandı" || status == "zaten var"
        val failed get() = status.startsWith("okunamadı") || status.startsWith("hata")
    }

    /** A planned copy: where it comes from and where it goes in the export folder. */
    private data class Job(val type: String, val src: File, val rel: String, val bytes: Long, val apk: Source? = null)

    /** The source could not be read: recorded as "okunamadı", the run goes on. */
    private class SourceUnreadable(msg: String) : IOException(msg)

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

    fun dir(ctx: Context) = File(ctx.filesDir, "kayit/paketler")

    fun appContext(): Context? = app

    /** Half-written files left by a power cut, anywhere in the export folder; never served. */
    private fun cleanTmp(d: File) {
        d.walkTopDown().filter { it.isFile && it.name.endsWith(PART) }.forEach { it.delete() }
    }

    /**
     * Only regular files are copied. A FIFO would block open() forever and a device file
     * (e.g. a link to /dev/zero) would never end, so those are recorded and skipped.
     */
    private fun isRegular(f: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 26) java.nio.file.Files.isRegularFile(f.toPath())
        else android.system.OsConstants.S_ISREG(android.system.Os.stat(f.path).st_mode)
    }.getOrDefault(false)

    /** Every APK file to export, found through PackageManager. Splits are included when present. */
    fun sources(ctx: Context): List<Source> {
        val pm = ctx.packageManager
        // Disabled vendor packages count too: their APK is still on the system partition.
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
        @Suppress("DEPRECATION")
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
        val rows = ArrayList<Row>()
        val jobs = ArrayList<Job>()

        // 1. APKs. Exports of versions no longer installed are removed from our own folder.
        val apks = sources(ctx)
        val keep = apks.map { it.exportName }.toSet()
        out.listFiles { f -> f.name.endsWith(".apk") && f.name !in keep }?.forEach { it.delete() }
        for (s in apks) {
            if (s.readable) jobs += Job("apk", File(s.path), s.exportName, s.bytes, s)
            else rows += apkRow(s, "okunamadı (izin yok)", "")
        }

        // 2 and 3. System folders, mirrored under sistem/.
        for (d in systemDirs) walk(File(d), "sistem", rows, jobs) { null }
        walk(File(configDir), "config", rows, jobs) { f ->
            when {
                f.name.endsWith(".apk", ignoreCase = true) -> "atlandı (.apk)"
                f.length() > CONFIG_MAX_BYTES -> "atlandı (5 MB'tan büyük)"
                else -> null
            }
        }

        // Mirrored files no longer present in the source folders are removed from our copy.
        val planned = jobs.map { it.rel }.toSet()
        File(out, MIRROR).walkBottomUp().forEach { f ->
            val rel = f.relativeTo(out).path
            if (f.isFile && rel !in planned) f.delete()
            else if (f.isDirectory && f.list().isNullOrEmpty()) f.delete()
        }

        // Space check before anything is written; only what is not already exported counts.
        totalBytes = jobs.sumOf { it.bytes }
        val needed = jobs.filterNot { upToDate(File(out, it.rel), it.src) }.sumOf { it.bytes }
        var stopped: String? = null
        if (out.usableSpace < needed + SPARE_BYTES) {
            lastError = "Yer yetmiyor: ${mb(needed)} gerekiyor, ${mb(out.usableSpace)} boş (${mb(SPARE_BYTES)} pay bırakılıyor)."
            stopped = "yer yok"
        }

        // Copy. A source we cannot read is recorded and skipped; a write error stops the run.
        for (j in jobs) {
            fun row(status: String, sha: String) =
                j.apk?.let { apkRow(it, status, sha) } ?: Row(j.type, j.src.path, j.rel, j.bytes, sha, status)
            val dest = File(out, j.rel)
            if (stopped != null) {
                // Files already exported and unchanged are still there, whatever stopped the run.
                rows += if (stopped == "yer yok" && upToDate(dest, j.src)) row("zaten var", "") else row("atlandı ($stopped)", "")
                continue
            }
            current = j.rel
            rows += try {
                if (upToDate(dest, j.src)) row("zaten var", sha256(dest))
                else row("kopyalandı", copy(j.src, dest))
            } catch (e: SourceUnreadable) {
                row("okunamadı: ${e.message.orEmpty()}", "")
            } catch (e: IOException) {
                stopped = "önceki dosyada yazma hatası"
                lastError = "Yazma hatası: ${e.message.orEmpty()}"
                row("hata (yazma): ${e.message.orEmpty()}", "")
            } catch (t: Throwable) {
                row("okunamadı: ${t.javaClass.simpleName} ${t.message.orEmpty()}", "")
            }
        }

        // 4. getprop output, as the unit gives it (small, so written even when space was short).
        current = GETPROP
        rows += dumpGetprop(out)

        writeManifest(out, rows)
        val apkOk = rows.count { it.type == "apk" && it.ok }
        val sysOk = rows.count { it.type == "sistem" && it.ok }
        val cfgOk = rows.count { it.type == "config" && it.ok }
        val failed = rows.count { it.failed }
        lastSummary = "${rows.filter { it.type == "apk" }.map { it.pkg }.distinct().size} paket, $apkOk APK · " +
            "$sysOk sistem dosyası · $cfgOk yapılandırma dosyası · ${mb(rows.filter { it.ok }.sumOf { it.bytes })}" +
            (if (failed > 0) " · $failed kayıt okunamadı ya da hatalı (paketler.csv’de)" else "")
    }

    /**
     * Walks [root] and plans a copy of every file under it, mirrored under sistem/<absolute path>.
     * [skip] returns a reason to leave a file out, or null to copy it. Folders the sandbox will not
     * list are recorded as "okunamadı"; symlink loops are cut by canonical path and depth.
     */
    private fun walk(root: File, type: String, rows: MutableList<Row>, jobs: MutableList<Job>, skip: (File) -> String?) {
        val seen = HashSet<String>()
        val used = jobs.map { it.rel }.toHashSet()
        val rootCanon = runCatching { root.canonicalPath }.getOrDefault(root.path)
        var count = 0
        fun rel(f: File): String {
            val base = MIRROR + "/" + f.path.trimStart('/').split('/').joinToString("/") { safe(it) }
            // Two names that differ only in replaced characters must not share one copy.
            // The suffix goes before the extension (a_b~2.cfg), so the file type is still recognised.
            val dot = base.lastIndexOf('.').takeIf { it > base.lastIndexOf('/') + 1 } ?: base.length
            var r = base; var i = 2
            while (!used.add(r)) r = base.substring(0, dot) + "~${i++}" + base.substring(dot)
            return r
        }
        fun visit(f: File, depth: Int) {
            val canon = runCatching { f.canonicalPath }.getOrDefault(f.path)
            if (!seen.add(canon)) return
            val isDir = runCatching { f.isDirectory }.getOrDefault(false)
            if (isDir) {
                // A link to a folder outside the scanned root (e.g. /, /proc, /sys) is not followed.
                if (canon != rootCanon && !canon.startsWith("$rootCanon/")) {
                    rows += Row(type, f.path + "/", "", 0, "", "atlandı (klasör dışına bağlantı → $canon)"); return
                }
                if (depth > MAX_DEPTH) { rows += Row(type, f.path, "", 0, "", "atlandı (çok derin)"); return }
                val children = runCatching { f.listFiles() }.getOrNull()
                if (children == null) { rows += Row(type, f.path + "/", "", 0, "", "okunamadı (klasör listelenemedi)"); return }
                children.sortedBy { it.name }.forEach { visit(it, depth + 1) }
                return
            }
            val exists = runCatching { f.exists() }.getOrDefault(false)
            if (!exists) { rows += Row(type, f.path, "", 0, "", "okunamadı (erişilemiyor)"); return }
            if (!isRegular(f)) { rows += Row(type, f.path, "", 0, "", "atlandı (özel dosya: FIFO, aygıt ya da soket)"); return }
            // A file link pointing outside the scanned root (e.g. into /proc) is not followed either.
            if (canon != rootCanon && !canon.startsWith("$rootCanon/")) {
                rows += Row(type, f.path, "", 0, "", "atlandı (klasör dışına bağlantı → $canon)"); return
            }
            if (++count > MAX_FILES) { if (count == MAX_FILES + 1) rows += Row(type, root.path + "/", "", 0, "", "atlandı ($MAX_FILES dosya sınırı aşıldı)"); return }
            val size = runCatching { f.length() }.getOrDefault(0L)
            val reason = skip(f)
            when {
                reason != null -> rows += Row(type, f.path, "", size, "", reason)
                !f.canRead() -> rows += Row(type, f.path, "", size, "", "okunamadı (izin yok)")
                else -> jobs += Job(type, f, rel(f), size)
            }
        }
        val rootExists = runCatching { root.exists() }.getOrDefault(false)
        if (!rootExists) {
            // exists() is false both when the folder is missing and when the sandbox hides it.
            rows += Row(type, root.path + "/", "", 0, "", "okunamadı (yok ya da erişilemiyor)")
            return
        }
        visit(root, 0)
    }

    private fun apkRow(s: Source, status: String, sha: String) =
        Row("apk", s.path, s.exportName, s.bytes, sha, status, s.pkg, s.label, s.version, s.versionCode.toString(), s.kind)

    /** Same size and not older than the source: no need to copy again. */
    private fun upToDate(dest: File, src: File): Boolean = runCatching {
        dest.exists() && dest.length() == src.length() && dest.lastModified() >= src.lastModified()
    }.getOrDefault(false)

    /**
     * Plain stream copy into a temporary file, then a rename, so a half-copied file never carries
     * the final name. Failures on the reading side are [SourceUnreadable]; on the writing side,
     * plain IOException. The SHA-256 is worked out on the way.
     */
    private fun copy(src: File, dest: File): String {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + PART)
        // A file that keeps growing well past its size is not a normal file; stop reading it.
        val limit = src.length() + 16L * 1024 * 1024
        var read = 0L
        val md = MessageDigest.getInstance("SHA-256")
        val input: InputStream = try { FileInputStream(src) } catch (t: Throwable) {
            throw SourceUnreadable(t.message ?: t.javaClass.simpleName)
        }
        try {
            input.use { ins ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = try { ins.read(buf) } catch (t: Throwable) {
                            throw SourceUnreadable(t.message ?: t.javaClass.simpleName)
                        }
                        if (n < 0) break
                        read += n
                        if (read > limit) throw SourceUnreadable("boyutundan çok daha uzun okunuyor")
                        output.write(buf, 0, n)
                        md.update(buf, 0, n)
                        doneBytes += n
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("ad değiştirilemedi")
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

    /** Runs getprop (no arguments: it only reads) and saves its stdout unchanged as getprop.txt. */
    private fun dumpGetprop(out: File): Row {
        val dest = File(out, GETPROP)
        return try {
            val p = ProcessBuilder("getprop").start()
            val dog = thread(isDaemon = true) { runCatching { Thread.sleep(5000); p.destroy() } }
            val bytes = try { p.inputStream.readBytes() } finally { dog.interrupt(); p.destroy() }
            if (bytes.isEmpty()) return Row("getprop", "getprop", "", 0, "", "okunamadı (boş çıktı)")
            val tmp = File(out, GETPROP + PART)
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException("ad değiştirilemedi") }
            Row("getprop", "getprop", GETPROP, bytes.size.toLong(), hex(MessageDigest.getInstance("SHA-256").digest(bytes)), "kopyalandı")
        } catch (t: Throwable) {
            Row("getprop", "getprop", "", 0, "", "okunamadı: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
    }

    /** paketler.csv: every file, where it came from and what happened to it, plus the build identity. */
    private fun writeManifest(out: File, rows: List<Row>) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val sb = StringBuilder("﻿")
        sb.append("# Kokpit Keşif ${BuildConfigCompat.version} · paket dışa aktarma · $stamp\n")
        DeviceInfo.lines().forEach { sb.append("# ").append(it.label).append(": ").append(it.value).append('\n') }
        sb.append("tur,paket,ad,surum,surum_kodu,parca,kaynak_yol,dosya,bayt,sha256,durum\n")
        for (r in rows) {
            sb.append(listOf(r.type, r.pkg, r.label, r.version, r.versionCode, r.kind, r.sourcePath, r.exportName,
                r.bytes.toString(), r.sha256, r.status).joinToString(",") { csv(it) }).append('\n')
        }
        val tmp = File(out, MANIFEST + PART)
        FileOutputStream(tmp).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        val dest = File(out, MANIFEST)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.delete(); lastError = "paketler.csv yazılamadı" }
    }

    // ---------- files for the web server ----------

    /**
     * Finished files in the export folder as paths relative to it (e.g. "sistem/data/config/a.ini"),
     * paketler.csv and getprop.txt first. Never a .tmp being written.
     */
    fun files(ctx: Context? = app): List<Pair<String, File>> {
        if (ctx == null) return emptyList()
        val root = dir(ctx)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && !it.name.endsWith(PART) }
            .map { it.relativeTo(root).path to it }
            .sortedWith(compareBy<Pair<String, File>> { order(it.first) }.thenBy { it.first })
            .toList()
    }

    private fun order(rel: String) = when {
        rel == MANIFEST -> 0
        rel == GETPROP -> 1
        rel.startsWith("$MIRROR/") -> 2
        else -> 3
    }

    /** Only paths from [files] are accepted, never a path built from the request. */
    fun byName(rel: String): File? = files().firstOrNull { it.first == rel }?.second

    /**
     * Streams a zip of the small diagnostic files (paketler.csv, getprop.txt and the sistem/ mirror),
     * without the APKs, so one tap on the iPad gets everything but the big files.
     */
    fun writeDiagnosticZip(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            // The window event log lives one folder up; it belongs in the diagnostic bundle too.
            listOfNotNull(app?.let { WindowLog.file(it) }, app?.let { UiDump.file(it) }, app?.let { ServiceProbe.interfaceFile(it) })
                .filter { it.exists() }.forEach { f ->
                runCatching { FileInputStream(f).use { input ->
                    zip.putNextEntry(ZipEntry(f.name).apply { time = f.lastModified() }); input.copyTo(zip, 64 * 1024); zip.closeEntry()
                } }
            }
            for ((rel, f) in files()) {
                if (rel.endsWith(".apk") && !rel.startsWith("$MIRROR/")) continue
                runCatching {
                    FileInputStream(f).use { input ->
                        zip.putNextEntry(ZipEntry(rel).apply { time = f.lastModified() })
                        input.copyTo(zip, 64 * 1024)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    fun statusText(): String = when {
        running -> "Kopyalanıyor: ${current ?: "…"} · ${mb(doneBytes)} / ${mb(totalBytes)}" +
            (if (totalBytes > 0) " (%${(doneBytes * 100 / totalBytes).coerceIn(0, 100)})" else "")
        lastError != null -> lastError!!
        lastSummary != null -> "Bitti: $lastSummary"
        files().any { it.first.endsWith(".apk") } -> "Klasörde önceki dışa aktarmadan dosyalar var"
        else -> "Henüz dışa aktarılmadı"
    }

    // ---------- helpers ----------

    fun mb(b: Long) = "%.1f MB".format(Locale.US, b / 1_048_576.0)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it == "." || it == "..") "_" else it }
    private fun csv(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
