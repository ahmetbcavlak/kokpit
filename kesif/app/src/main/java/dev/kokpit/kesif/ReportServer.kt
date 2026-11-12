package dev.kokpit.kesif

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A tiny read-only web server so the report can be opened from an iPad
 * on the same hotspot. It serves the report and, for study, the APK files
 * of the vendor apps. It never accepts uploads or changes anything.
 */
class ReportServer(
    private val port: Int,
    private val report: () -> Report,
    private val apks: () -> List<ReportBuilder.ApkFile>,
) {
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        thread(name = "kesif-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(client) } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(c: Socket): Unit = c.use {
        c.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val requestLine = reader.readLine() ?: return@use
        val out = c.getOutputStream()
        val path = runCatching { URLDecoder.decode(requestLine.split(" ").getOrNull(1) ?: "/", "UTF-8") }.getOrElse {
            send(out, "text/plain; charset=utf-8", "Geçersiz adres", 400); return@use
        }
        val route = path.substringBefore('?')
        when {
            route == "/" || route == "/canli" -> send(out, "text/html; charset=utf-8", LivePage.html())
            route == "/canli.json" -> send(out, "application/json; charset=utf-8", LiveProbe.json())
            route == "/kayit.csv" -> {
                val f = Recorder.latest()
                if (f == null) send(out, "text/plain; charset=utf-8", "Henüz kayıt yok", 404)
                else sendGrowing(out, f, "text/csv; charset=utf-8")
            }
            route == "/kayit.json" -> {
                val f = Recorder.latest()
                if (f == null) send(out, "application/json; charset=utf-8", "[]", 404)
                else send(out, "application/json; charset=utf-8", Recorder.asJson(f))
            }
            route.startsWith("/kayit/") -> {
                // Only a name from the recorder's own list is served, never a path from the request.
                val f = Recorder.byName(route.removePrefix("/kayit/"))
                if (f == null) send(out, "text/plain; charset=utf-8", "Bulunamadı", 404)
                else sendGrowing(out, f, "text/csv; charset=utf-8")
            }
            route == "/rapor" -> send(out, "text/html; charset=utf-8", html(report()))
            route == "/arayuz.csv" || route == "/servis-arayuzu.txt" -> {
                val ctx = PackageExporter.appContext()
                val f = ctx?.let { if (route == "/arayuz.csv") UiDump.file(it) else ServiceProbe.interfaceFile(it) }
                if (f == null || !f.exists()) send(out, "text/plain; charset=utf-8", "Henüz yok", 404)
                else sendGrowing(out, f, if (route.endsWith(".csv")) "text/csv; charset=utf-8" else "text/plain; charset=utf-8")
            }
            route == "/pencereler.csv" -> {
                val f = PackageExporter.appContext()?.let { WindowLog.file(it) }
                if (f == null || !f.exists()) send(out, "text/plain; charset=utf-8", "Henüz pencere olayı yok", 404)
                else sendGrowing(out, f, "text/csv; charset=utf-8")
            }
            route == "/paketler" || route == "/paketler/" -> send(out, "text/html; charset=utf-8", packagesPage())
            route == "/paketler/teshis.zip" && PackageExporter.running ->
                send(out, "text/plain; charset=utf-8", "Dışa aktarma sürüyor; bitince yeniden dene.", 409)
            route == "/paketler/teshis.zip" -> {
                // Small diagnostic files in one zip, streamed: the length is not known up front,
                // so the body simply ends when the connection closes.
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\n" +
                    "Content-Disposition: attachment; filename=\"kesif-teshis.zip\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII))
                PackageExporter.writeDiagnosticZip(out)
                out.flush()
            }
            route.startsWith("/paketler/") -> {
                // Only a finished file from the export folder's own list, never a path from the request.
                val f = PackageExporter.byName(route.removePrefix("/paketler/"))
                if (f == null) send(out, "text/plain; charset=utf-8", "Bulunamadı", 404)
                else sendFile(out, f, f.name, when {
                    f.name.endsWith(".csv") -> "text/csv; charset=utf-8"
                    f.name.endsWith(".txt") -> "text/plain; charset=utf-8"
                    f.name.endsWith(".apk") -> "application/vnd.android.package-archive"
                    else -> "application/octet-stream"
                })
            }
            path.startsWith("/rapor.txt") -> send(out, "text/plain; charset=utf-8", report().toText())
            path == "/apk" || path == "/apk/" -> send(out, "text/html; charset=utf-8", apkPage())
            path.startsWith("/apk/") && path.endsWith(".apk") -> {
                val pkg = path.removePrefix("/apk/").removeSuffix(".apk")
                // Only files from the vetted list can be served; the path never comes from the request.
                val apk = apks().firstOrNull { it.pkg == pkg }
                if (apk == null) send(out, "text/plain; charset=utf-8", "Bulunamadı", 404)
                else sendFile(out, File(apk.path), "${apk.pkg}.apk")
            }
            else -> send(out, "text/plain; charset=utf-8", "Bulunamadı", 404)
        }
    }

    private fun send(out: OutputStream, type: String, body: String, code: Int = 200) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "200 OK"; 400 -> "400 Bad Request"; 409 -> "409 Conflict"; else -> "$code Not Found"
        }
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(bytes)
        out.flush()
    }

    /**
     * A file that may still be growing (the recording): its length is taken once and exactly that
     * many bytes are streamed, so Content-Length is right and memory stays small.
     */
    private fun sendGrowing(out: OutputStream, f: File, type: String) {
        val len = f.length()
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $len\r\n" +
                "Content-Disposition: attachment; filename=\"${f.name}\"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        )
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    /**
     * Streams a file. It is opened before the headers go out and its size is taken from the open
     * file, so a file replaced meanwhile (a re-export) can never make Content-Length wrong.
     */
    private fun sendFile(out: OutputStream, f: File, name: String, type: String = "application/octet-stream") {
        val input = runCatching { java.io.FileInputStream(f) }.getOrElse {
            send(out, "text/plain; charset=utf-8", "Bulunamadı", 404); return
        }
        input.use { ins ->
            val len = ins.channel.size()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $len\r\n" +
                    "Content-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\n\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val style = """
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;background:#0E1114;color:#ECEEEA;font:17px/1.5 -apple-system,system-ui,sans-serif;padding:24px}
h1{font-size:28px;margin:0 0 4px}small{color:#9AA5AD}a{color:#F0A030}
.btn{display:inline-block;background:#F0A030;color:#10161B;border:0;border-radius:12px;padding:14px 20px;font-size:17px;font-weight:600;margin:16px 8px 16px 0;text-decoration:none}
pre{white-space:pre-wrap;word-break:break-word;background:#171C21;border-radius:12px;padding:16px;font:14px/1.45 ui-monospace,Menlo,monospace}
li{margin:10px 0}
</style>"""

    private fun html(r: Report): String = """
<!DOCTYPE html><html lang="tr"><head>$style<title>Kokpit Keşif Raporu</title></head><body>
<h1>Kokpit Keşif Raporu</h1><small>${esc(r.createdAt)}</small><br>
<button class="btn" onclick="navigator.clipboard.writeText(document.getElementById('r').innerText).then(()=>this.textContent='Kopyalandı')">Raporu kopyala</button>
<a class="btn" href="/rapor.txt" download="kokpit-kesif.txt">Dosya olarak indir</a>
<a class="btn" href="/apk">Uygulama dosyaları</a>
<a class="btn" href="/canli">Canlı araç verisi</a>
<pre id="r">${esc(r.toText())}</pre>
</body></html>
""".trimIndent()

    private fun packagesPage(): String {
        val files = PackageExporter.files()
        val items = files.joinToString("\n") { (rel, f) ->
            val href = rel.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            "<li><a href=\"/paketler/$href\" download>${esc(rel)}</a><br><small>${PackageExporter.mb(f.length())}</small></li>"
        }
        return """
<!DOCTYPE html><html lang="tr"><head>$style<title>Dışa aktarılan paketler</title></head><body>
<h1>Dışa aktarılan paketler</h1>
<small>${esc(PackageExporter.statusText())}. paketler.csv hangi dosyanın nereden geldiğini, sürümünü, SHA-256 değerini ve okunamayan dosyaları gösterir.</small>
<a class="btn" href="/paketler/teshis.zip">APK’lar hariç hepsi tek dosyada (zip)</a>
<ul>${items.ifEmpty { "<li>Henüz dosya yok. Ekranda “Paketleri dışa aktar”a dokun.</li>" }}</ul>
<a class="btn" href="/canli">Canlı veri</a>
</body></html>
""".trimIndent()
    }

    private fun apkPage(): String {
        val items = apks().joinToString("\n") {
            "<li><a href=\"/apk/${esc(it.pkg)}.apk\" download>${esc(it.label)}</a><br><small>${esc(it.pkg)} · ${"%.1f".format(it.bytes / 1_048_576.0)} MB</small></li>"
        }
        return """
<!DOCTYPE html><html lang="tr"><head>$style<title>Uygulama dosyaları</title></head><body>
<h1>Üretici uygulamalarının dosyaları</h1>
<small>Claude bunları inceleyip araç ayarlarına ve CAN verisine nasıl ulaşacağımızı çıkaracak. Önce <b>Oto Can</b>, <b>Araç ayarları</b>, <b>ZLINK</b> ve <b>Ev</b> yeterli.</small>
<ul>$items</ul>
<a class="btn" href="/rapor">Rapora dön</a>
</body></html>
""".trimIndent()
    }
}
