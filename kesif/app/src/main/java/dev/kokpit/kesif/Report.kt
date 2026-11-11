package dev.kokpit.kesif

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the report: a label and its value. */
data class Line(val label: String, val value: String)

/** A titled group of lines. */
data class Section(val title: String, val lines: List<Line>)

data class AppEntry(
    val label: String,
    val pkg: String,
    val version: String,
    val system: Boolean,
    val enabled: Boolean,
    val flagged: String?,
)

data class Report(
    val createdAt: String,
    val summary: List<Line>,
    val sections: List<Section>,
    val apps: List<AppEntry>,
) {
    fun toText(): String = buildString {
        appendLine("KOKPIT KEŞİF RAPORU · $createdAt")
        appendLine()
        appendLine("== ÖZET ==")
        summary.forEach { appendLine("${it.label}: ${it.value}") }
        sections.forEach { s ->
            appendLine()
            appendLine("== ${s.title.uppercase(Locale.forLanguageTag("tr"))} ==")
            s.lines.forEach { appendLine("${it.label}: ${it.value}") }
        }
        appendLine()
        appendLine("== UYGULAMALAR (${apps.size}) ==")
        apps.forEach { a ->
            val tags = buildList {
                if (a.system) add("sistem")
                if (!a.enabled) add("kapalı")
                a.flagged?.let { add("ÖNEMLİ: $it") }
            }.joinToString(", ")
            appendLine("${a.pkg} | ${a.label} | ${a.version}${if (tags.isNotEmpty()) " | $tags" else ""}")
        }
    }
}

object ReportBuilder {

    /** Package-name fragments worth flagging, with a short Turkish reason. */
    private val interesting = listOf(
        "syu" to "FYT araç servisi / CAN",
        "canbus" to "CAN verisi",
        "zlink" to "CarPlay köprüsü (Zlink)",
        "tlink" to "CarPlay köprüsü (TLink)",
        "autokit" to "CarPlay köprüsü (AutoKit)",
        "carlink" to "CarPlay köprüsü",
        "carplay" to "CarPlay",
        "androidauto" to "Android Auto",
        "launcher" to "Ana ekran",
        "fyt" to "FYT platformu",
        "microntek" to "MTC platformu",
        "carsetting" to "Araç ayarları",
        "radio" to "Radyo",
        "bluetooth" to "Bluetooth / telefon",
        "btphone" to "Bluetooth telefon",
        "update" to "Güncelleme uygulaması",
        "ota" to "Güncelleme uygulaması",
        "twcore" to "Güncelleme servisi (dikkat)",
        "dvr" to "Kamera / DVR",
        "avm" to "360 kamera",
        "navi" to "Navigasyon",
    )

    /** System property prefixes we are allowed to read; nothing that identifies the unit. */
    private val propPrefixes = listOf(
        "ro.product.", "ro.build.", "ro.board.", "ro.hardware", "ro.soc.", "ro.chipname",
        "ro.sf.lcd_density", "ro.fyt", "persist.fyt", "sys.fyt", "ro.lsec", "ro.vendor.build.",
        "ro.boot.hardware", "ro.config.", "ro.product.cpu.abilist", "persist.sys.timezone",
    )
    private val propBlock = listOf("serial", "imei", "mac", "meid", "sn", "uuid", "bootloader")

    fun build(ctx: Context): Report {
        val pm = ctx.packageManager
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date())

        // Display
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        @Suppress("DEPRECATION")
        display.getRealMetrics(dm)
        val refresh = display.refreshRate

        // Memory
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        // Storage
        val st = StatFs(Environment.getDataDirectory().path)
        val totalStorage = st.blockCountLong * st.blockSizeLong
        val freeStorage = st.availableBlocksLong * st.blockSizeLong

        // CPU
        val cores = Runtime.getRuntime().availableProcessors()
        val maxFreqs = (0 until cores).mapNotNull { i ->
            readFile("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.trim()?.toLongOrNull()
        }
        val cpuHardware = readFile("/proc/cpuinfo")
            ?.lines()
            ?.firstOrNull { it.startsWith("Hardware") }
            ?.substringAfter(":")?.trim()
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else null
        val bench = cpuBenchmarkMs()

        val summary = listOf(
            Line("Cihaz", "${Build.MANUFACTURER} ${Build.MODEL}"),
            Line("Android", "${getprop("ro.build.version.release") ?: Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ekranda “${Build.VERSION.RELEASE}” yazıyor"),
            Line("İşlemci", listOfNotNull(soc, cpuHardware, Build.HARDWARE).distinct().joinToString(" · ")),
            Line("Çekirdek", "$cores × ${maxFreqs.maxOrNull()?.let { "%.1f GHz".format(it / 1_000_000.0) } ?: "?"}"),
            Line("Bellek", "${gb(mi.totalMem)} toplam · ${gb(mi.availMem)} boş"),
            Line("Ekran", "${dm.widthPixels}×${dm.heightPixels} px · ${dm.densityDpi} dpi · ${refresh.toInt()} Hz"),
            Line("Depolama", "${gb(totalStorage)} · ${gb(freeStorage)} boş"),
            Line("İşlemci testi", "$bench ms (düşük daha iyi)"),
        )

        val device = Section(
            "Cihaz", listOf(
                Line("Üretici", Build.MANUFACTURER),
                Line("Marka", Build.BRAND),
                Line("Model", Build.MODEL),
                Line("Cihaz kodu", Build.DEVICE),
                Line("Ürün", Build.PRODUCT),
                Line("Kart", Build.BOARD),
                Line("Donanım", Build.HARDWARE),
                Line("Derleme", Build.DISPLAY),
                Line("Derleme türü", Build.TYPE),
                Line("Güvenlik yaması", if (Build.VERSION.SDK_INT >= 23) Build.VERSION.SECURITY_PATCH else "?"),
                Line("ABI", Build.SUPPORTED_ABIS.joinToString()),
                Line("Düşük bellekli cihaz", if (am.isLowRamDevice) "evet" else "hayır"),
                Line("Uygulama bellek sınırı", "${am.memoryClass} MB"),
            )
        )

        val screen = Section(
            "Ekran", listOf(
                Line("Çözünürlük", "${dm.widthPixels} × ${dm.heightPixels}"),
                Line("Yön", if (dm.heightPixels > dm.widthPixels) "dikey" else "yatay"),
                Line("Yoğunluk", "${dm.densityDpi} dpi (ölçek ${dm.density})"),
                Line("Genişlik (dp)", "${(dm.widthPixels / dm.density).toInt()} dp"),
                Line("Yükseklik (dp)", "${(dm.heightPixels / dm.density).toInt()} dp"),
                Line("76 dp hedef", "${(76 * dm.density).toInt()} px"),
                Line("Yenileme", "%.1f Hz".format(refresh)),
            )
        )

        val cpu = Section(
            "İşlemci", buildList {
                add(Line("Çekirdek sayısı", cores.toString()))
                maxFreqs.forEachIndexed { i, f -> add(Line("cpu$i en yüksek", "${f / 1000} MHz")) }
                cpuHardware?.let { add(Line("cpuinfo Hardware", it)) }
                soc?.let { add(Line("SoC", it)) }
                add(Line("Test süresi", "$bench ms"))
            }
        )

        val cr = ctx.contentResolver
        fun g(key: String) = runCatching { Settings.Global.getString(cr, key) }.getOrNull() ?: "-"
        fun s(key: String) = runCatching { Settings.Secure.getString(cr, key) }.getOrNull() ?: "-"
        val settings = Section(
            "Ayarlar", listOf(
                Line("Geliştirici seçenekleri", g("development_settings_enabled")),
                Line("USB hata ayıklama", g(Settings.Global.ADB_ENABLED)),
                Line("Kablosuz hata ayıklama", g("adb_wifi_enabled")),
                Line("Pencere animasyonu", g(Settings.Global.WINDOW_ANIMATION_SCALE)),
                Line("Geçiş animasyonu", g(Settings.Global.TRANSITION_ANIMATION_SCALE)),
                Line("Animatör süresi", g(Settings.Global.ANIMATOR_DURATION_SCALE)),
                Line("Varsayılan klavye", s(Settings.Secure.DEFAULT_INPUT_METHOD)),
            )
        )

        // Home screens
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homes = pm.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName }.distinct()
        val defaultHome = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName ?: "-"
        val home = Section(
            "Ana ekran", listOf(
                Line("Şu anki ana ekran", defaultHome),
                Line("Ana ekran olabilecekler", homes.joinToString().ifEmpty { "-" }),
            )
        )

        val net = Section(
            "Ağ", localIps().map { Line(it.first, it.second) }.ifEmpty { listOf(Line("Ağ", "bağlı değil")) }
        )

        val props = Section("Sistem özellikleri", readProps())

        // Reality check: cheap units often report a fake Android version and fake RAM.
        val realRelease = getprop("ro.build.version.release")
        val memTotalKb = readFile("/proc/meminfo")?.lines()
            ?.firstOrNull { it.startsWith("MemTotal") }
            ?.filter { it.isDigit() }?.toLongOrNull()
        val reality = Section(
            "Gerçeklik kontrolü", listOf(
                Line("Ekranda görünen Android", Build.VERSION.RELEASE),
                Line("Sistemin kendi sürümü", realRelease ?: "-"),
                Line("API düzeyi", "${Build.VERSION.SDK_INT} (27 = Android 8.1)"),
                Line("Sürüm tutarlı mı", if (realRelease == null || realRelease == Build.VERSION.RELEASE) "evet" else "HAYIR · görünen sürüm sahte"),
                Line("Bellek (sistem API)", gb(mi.totalMem)),
                Line("Bellek (/proc/meminfo)", memTotalKb?.let { gb(it * 1024) } ?: "-"),
            )
        )

        // Apps
        @Suppress("DEPRECATION")
        val pkgs = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val apps = pkgs.map { p ->
            val ai = p.applicationInfo
            val label = ai?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: p.packageName
            val name = p.packageName.lowercase(Locale.ROOT)
            AppEntry(
                label = label,
                pkg = p.packageName,
                version = p.versionName ?: "-",
                system = ai != null && (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                enabled = ai?.enabled ?: true,
                flagged = interesting.firstOrNull { name.contains(it.first) }?.second,
            )
        }.sortedWith(compareBy<AppEntry> { it.flagged == null }.thenBy { it.system }.thenBy { it.pkg })

        val appSummary = Section(
            "Uygulama özeti", listOf(
                Line("Toplam", apps.size.toString()),
                Line("Sistem", apps.count { it.system }.toString()),
                Line("Sonradan kurulan", apps.count { !it.system }.toString()),
                Line("Önemli işaretli", apps.count { it.flagged != null }.toString()),
            )
        )

        return Report(
            now, summary,
            listOf(reality, device, screen, cpu, home, settings, net, appSummary) + components(ctx) + props,
            apps,
        )
    }

    /** Packages whose inner screens and services we want to map. */
    private val componentPrefixes = listOf(
        "com.hcn.", "com.zjinnova.", "com.autochips.", "com.auto.", "com.android.launcher3",
        "com.mediatek.engineermode", "com.adups.",
    )

    fun isTarget(pkg: String) = componentPrefixes.any { pkg.startsWith(it) } && !pkg.startsWith("com.hcn.auto.theme.")

    /** Lists activities, services, receivers and providers of the vendor packages. */
    private fun components(ctx: Context): List<Section> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val targets = pm.getInstalledPackages(0).map { it.packageName }.filter(::isTarget).sorted()
        return targets.map { pkg ->
            val lines = mutableListOf<Line>()
            fun add(kind: String, flag: Int, pick: (android.content.pm.PackageInfo) -> List<Line>) {
                runCatching {
                    @Suppress("DEPRECATION")
                    pick(pm.getPackageInfo(pkg, flag))
                }.onSuccess { lines += it }.onFailure { lines += Line(kind, "okunamadı") }
            }
            add("Ekranlar", PackageManager.GET_ACTIVITIES) { pi ->
                pi.activities.orEmpty().map { Line("ekran", "${it.name}${if (it.exported) " · dışa açık" else ""}") }
            }
            add("Servisler", PackageManager.GET_SERVICES) { pi ->
                pi.services.orEmpty().map { Line("servis", "${it.name}${if (it.exported) " · dışa açık" else ""}") }
            }
            add("Alıcılar", PackageManager.GET_RECEIVERS) { pi ->
                pi.receivers.orEmpty().map { Line("alıcı", "${it.name}${if (it.exported) " · dışa açık" else ""}") }
            }
            add("Sağlayıcılar", PackageManager.GET_PROVIDERS) { pi ->
                pi.providers.orEmpty().map { Line("sağlayıcı", "${it.name} · ${it.authority}") }
            }
            Section("Bileşenler · $pkg", lines.ifEmpty { listOf(Line("-", "bileşen yok")) })
        }
    }

    data class ApkFile(val pkg: String, val label: String, val path: String, val bytes: Long)

    /** APK files of the vendor packages, for offline study on the iPad. */
    fun apkFiles(ctx: Context): List<ApkFile> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        return pm.getInstalledPackages(0).mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            if (!isTarget(p.packageName)) return@mapNotNull null
            val f = File(ai.sourceDir ?: return@mapNotNull null)
            if (!f.canRead()) return@mapNotNull null
            ApkFile(p.packageName, runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName), f.path, f.length())
        }.sortedBy { it.pkg }
    }

    private fun getprop(key: String): String? = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifEmpty { null }
    }.getOrNull()

    fun localIps(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress.orEmpty() }
            }
    }.getOrDefault(emptyList())

    private fun readProps(): List<Line> = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("getprop"))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.lines().mapNotNull { line ->
            // Format: [key]: [value]
            val m = Regex("""^\[(.+?)]: \[(.*)]$""").find(line.trim()) ?: return@mapNotNull null
            val (k, v) = m.destructured
            val kl = k.lowercase(Locale.ROOT)
            if (propPrefixes.none { kl.startsWith(it) }) return@mapNotNull null
            if (propBlock.any { kl.contains(it) }) return@mapNotNull null
            Line(k, v)
        }.sortedBy { it.label }
    }.getOrDefault(emptyList())

    private fun cpuBenchmarkMs(): Long {
        val start = SystemClock.elapsedRealtime()
        // Sieve of Eratosthenes up to 2,000,000, repeated three times.
        repeat(3) {
            val n = 2_000_000
            val composite = BooleanArray(n + 1)
            var i = 2
            while (i.toLong() * i <= n) {
                if (!composite[i]) {
                    var j = i * i
                    while (j <= n) { composite[j] = true; j += i }
                }
                i++
            }
        }
        return SystemClock.elapsedRealtime() - start
    }

    private fun readFile(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1_073_741_824.0)
}
