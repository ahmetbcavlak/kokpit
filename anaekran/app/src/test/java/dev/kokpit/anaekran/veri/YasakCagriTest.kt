package dev.kokpit.anaekran.veri

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K-29 / K-75: the app only reads. No broadcast is sent, no service bound, no accessibility
 * action taken, no setting written, no process started. Checks the whole of src/main's source
 * code itself (comments removed), not only the data layer.
 */
class YasakCagriTest {

    private val yasak = listOf(
        Regex("""\bsend(Ordered|Sticky|StickyOrdered)?Broadcast(AsUser)?\s*\("""),
        Regex("""\bsendOrderedBroadcast\b"""),
        Regex("""\bsendStickyBroadcast\b"""),
        Regex("""\bbindService\s*\("""),
        Regex("""\bstart(Foreground)?Service\s*\("""),
        Regex("""\bperformAction\s*\("""),
        Regex("""\bperformGlobalAction\s*\("""),
        Regex("""\bdispatchGesture\s*\("""),
        Regex("""\bSettings\s*\.\s*(Secure|System|Global)\s*\.\s*put\w*\s*\("""),
        Regex("""\bSystemProperties\b"""),
        Regex("""\bsetSystemProperty\b"""),
        Regex("""IAirCtrl"""),
        Regex("""\bRuntime\s*\.\s*getRuntime\s*\(\s*\)\s*\.\s*exec\b"""),
        Regex("""\.\s*exec\s*\("""),
        Regex("""\bProcessBuilder\b"""),
    )

    private fun kaynak(): File {
        // Gradle runs unit tests in the module directory (app/).
        val f = File("src/main")
        assertTrue("source not found from ${File(".").absolutePath}", f.isDirectory)
        return f
    }

    private fun yorumsuz(s: String) = s
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun uygulamaYalnizOkur() {
        val dosyalar = kaynak().walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("too few files: ${dosyalar.map { it.name }}", dosyalar.size >= 40)
        // The scan really covers every layer, not only veri/.
        for (k in listOf("MainActivity.kt", "KapiServisi.kt", "PaketAcici.kt", "KlipOynatici.kt", "UyariKatmani.kt")) {
            assertTrue("$k not scanned", dosyalar.any { it.name == k })
        }
        val bulgular = dosyalar.flatMap { f ->
            val metin = yorumsuz(f.readText())
            yasak.filter { it.containsMatchIn(metin) }.map { "${f.path}: ${it.pattern}" }
        }
        assertTrue("forbidden calls: $bulgular", bulgular.isEmpty())
    }

    @Test
    fun desenlerYasakCagrilariYakalar() {
        for (ornek in listOf(
            "sendOrderedBroadcast(i, null)", "context.sendStickyBroadcast(i)", "sendBroadcast(i)",
            "Runtime.getRuntime().exec(\"su\")", "val r = Runtime.getRuntime(); r.exec(arrayOf(\"sh\"))",
            "ProcessBuilder(\"sh\").start()", "bindService(i, c, 0)", "n.performAction(16)",
        )) {
            assertTrue("not caught: $ornek", yasak.any { it.containsMatchIn(ornek) })
        }
        // Ordinary code is not flagged.
        for (ornek in listOf("executor.execute(r)", "startActivity(i)", "registerReceiver(a, f)")) {
            assertTrue("false alarm: $ornek", yasak.none { it.containsMatchIn(ornek) })
        }
    }

    @Test
    fun erisilebilirlikAyariHareketIzniVermez() {
        val xml = File("src/main/res/xml/kapi_servisi.xml").readText()
        val ayar = xml.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
        for (y in listOf(
            "canPerformGestures", "canRequestEnhancedWebAccessibility", "flagIncludeNotImportantViews",
            "flagRequestTouchExplorationMode", "flagRequestFilterKeyEvents", "canRequestFilterKeyEvents",
            "canRequestTouchExplorationMode", "packageNames",
        )) {
            assertTrue("$y in kapi_servisi.xml", !ayar.contains(y))
        }
        assertTrue(ayar.contains("flagRetrieveInteractiveWindows"))
        assertTrue(ayar.contains("""android:notificationTimeout="0""""))
    }
}
