package dev.kokpit.kesif

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/**
 * "İnternet" (Keşif 0.9): can the unit reach GitHub over HTTPS, and how fast. Needed for remote
 * update and diagnostics (K-133, wave A). Two hosts: github.com and raw.githubusercontent.com, where
 * the download link really ends up. Android 8.1's 2018 certificate store may not trust them: the
 * error text says so.
 *
 * Read-only: one HEAD request per host, no data sent beyond the request itself. The Wi-Fi network
 * name is read (needs location permission on Android 8.1); no MAC or BSSID.
 */
object InternetProbe {

    val targets = listOf("https://github.com/", "https://raw.githubusercontent.com/ahmetbcavlak/kokpit/main/README.md")
    private const val TIMEOUT_MS = 10_000

    @Volatile var running = false; private set
    @Volatile var last: List<String> = emptyList(); private set

    @Suppress("DEPRECATION")
    fun network(ctx: Context): List<Pair<String, String>> {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val info = runCatching { cm?.activeNetworkInfo }.getOrNull()
        val caps = runCatching { cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = runCatching { wifi?.connectionInfo?.ssid }.getOrNull()
        val ssidText = when {
            info?.type != ConnectivityManager.TYPE_WIFI -> "Wi-Fi değil"
            ssid == null || ssid == "<unknown ssid>" || ssid.isBlank() -> "okunamadı (konum izni ya da konum kapalı)"
            else -> ssid.trim('"')
        }
        return listOf(
            "ağ türü" to (info?.typeName ?: "bağlı değil"),
            "bağlı" to (if (info?.isConnected == true) "evet" else "hayır"),
            "internet" to when {
                caps == null -> "yok"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "doğrulandı"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "var, doğrulanmadı"
                else -> "yok"
            },
            "Wi-Fi adı" to ssidText,
        )
    }

    /** One host: DNS time, then a HEAD request's time to the response code (connect + TLS + reply). */
    fun probe(url: String): List<Pair<String, String>> {
        val host = URL(url).host
        val out = ArrayList<Pair<String, String>>()
        val t0 = SystemClock.elapsedRealtime()
        val dns = runCatching { InetAddress.getAllByName(host) }
        out += "$host DNS" to dns.fold({ "${SystemClock.elapsedRealtime() - t0} ms · ${it.size} adres" }, { "hata: ${it.javaClass.simpleName} ${it.message.orEmpty()}" })
        if (dns.isFailure) return out
        val t1 = SystemClock.elapsedRealtime()
        var c: HttpURLConnection? = null
        out += "$host HTTPS" to try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"; connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false; useCaches = false
                setRequestProperty("User-Agent", "KokpitKesif/${BuildConfigCompat.version}")
            }
            val code = c.responseCode
            val tls = (c as? HttpsURLConnection)?.cipherSuite.orEmpty()
            "${SystemClock.elapsedRealtime() - t1} ms · HTTP $code" + if (tls.isNotEmpty()) " · $tls" else ""
        } catch (t: Throwable) {
            "hata (${SystemClock.elapsedRealtime() - t1} ms): ${t.javaClass.simpleName} ${t.message.orEmpty().take(160)}"
        } finally { runCatching { c?.disconnect() } }
        return out
    }

    /** Runs both hosts on a worker; [carplay] is the user's tag ("CarPlay açık" / "CarPlay kapalı"). */
    fun run(ctx: Context, carplay: String, done: () -> Unit): Boolean {
        if (running) return false
        running = true
        val app = ctx.applicationContext
        thread(name = "kesif-internet", isDaemon = true) {
            try {
                val at = System.currentTimeMillis()
                val lines = network(app) + targets.flatMap { probe(it) }
                last = listOf("[$carplay] ${Yol.log.time(at).substring(11, 19)}") + lines.map { "${it.first}: ${it.second}" }
                Yol.log.post(app, lines.map { at to listOf("internet", carplay, it.first, it.second, "") })
            } catch (t: Throwable) {
                last = listOf("hata: ${t.javaClass.simpleName}")
            } finally {
                running = false
                android.os.Handler(android.os.Looper.getMainLooper()).post(done)
            }
        }
        return true
    }
}

class InternetPage(private val a: Activity, onBack: () -> Unit) : Page {
    private val ui = Ui(a)
    override val root: LinearLayout
    private val net = ui.label("", 15f, Palette.text, mono = true)
    private val result = ui.label("Henüz ölçülmedi.", 14f, Palette.text, mono = true)
    private var lastNet = 0L

    init {
        val (r, content) = ui.frame("1 · İnternet", "github.com ve raw.githubusercontent.com · HTTPS", onBack)
        root = r
        ui.card(content) {
            addView(ui.label("Önce CarPlay kapalıyken, ekran iPhone’un erişim noktasına bağlıyken ölç; sonra CarPlay açıkken aynı testi yap. Düğme, ölçümü seçtiğin durumla etiketler.", 14f, Palette.faint).apply { setPadding(0, 0, 0, ui.dp(8)) })
            addView(net.apply { setPadding(0, 0, 0, ui.dp(10)) })
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("Ölç\nCarPlay kapalı", primary = true, size = 17f) { run("CarPlay kapalı") }, LinearLayout.LayoutParams(0, ui.dp(72), 1f).apply { rightMargin = ui.dp(10) })
                addView(ui.button("Ölç\nCarPlay açık", primary = true, size = 17f) { run("CarPlay açık") }, LinearLayout.LayoutParams(0, ui.dp(72), 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(72)))
        }
        ui.card(content) {
            addView(ui.label("Son ölçüm", 17f, Palette.accent, bold = true))
            addView(result.apply { setPadding(0, ui.dp(6), 0, 0) })
        }
    }

    override fun onShow() {
        // The Wi-Fi name needs location permission on Android 8.1.
        if (!GpsGuard.hasPermission(a) && !asked) { asked = true; runCatching { a.requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), 19) } }
    }
    private var asked = false

    private fun run(tag: String) {
        if (InternetProbe.run(a, tag) { tick() }) result.text = "Ölçülüyor ($tag)… en çok yarım dakika"
    }

    override fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNet > 2000) {
            lastNet = now
            net.text = InternetProbe.network(a).joinToString("\n") { "${it.first}: ${it.second}" }
        }
        if (!InternetProbe.running && InternetProbe.last.isNotEmpty()) result.text = InternetProbe.last.joinToString("\n")
    }
}
