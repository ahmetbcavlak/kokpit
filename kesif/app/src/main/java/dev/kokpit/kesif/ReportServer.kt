package dev.kokpit.kesif

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A tiny read-only web server so the report can be opened from an iPad
 * on the same hotspot. It serves only the report, nothing else.
 */
class ReportServer(private val port: Int, private val report: () -> Report) {
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
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(c: Socket): Unit = c.use {
        val reader = BufferedReader(InputStreamReader(c.getInputStream()))
        val requestLine = reader.readLine() ?: return@use
        val path = requestLine.split(" ").getOrNull(1) ?: "/"
        val r = report()
        val (type, body) = when {
            path.startsWith("/rapor.txt") -> "text/plain; charset=utf-8" to r.toText()
            else -> "text/html; charset=utf-8" to html(r)
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun html(r: Report): String = """
<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kokpit Keşif Raporu</title>
<style>
body{margin:0;background:#0E1114;color:#ECEEEA;font:17px/1.5 -apple-system,system-ui,sans-serif;padding:24px}
h1{font-size:28px;margin:0 0 4px}small{color:#9AA5AD}
.btn{display:inline-block;background:#F0A030;color:#10161B;border:0;border-radius:12px;padding:14px 20px;font-size:17px;font-weight:600;margin:16px 8px 16px 0;text-decoration:none}
pre{white-space:pre-wrap;word-break:break-word;background:#171C21;border-radius:12px;padding:16px;font:14px/1.45 ui-monospace,Menlo,monospace}
</style></head><body>
<h1>Kokpit Keşif Raporu</h1><small>${esc(r.createdAt)}</small><br>
<button class="btn" onclick="navigator.clipboard.writeText(document.getElementById('r').innerText).then(()=>this.textContent='Kopyalandı')">Raporu kopyala</button>
<a class="btn" href="/rapor.txt" download="kokpit-kesif.txt">Dosya olarak indir</a>
<pre id="r">${esc(r.toText())}</pre>
</body></html>
""".trimIndent()
}
