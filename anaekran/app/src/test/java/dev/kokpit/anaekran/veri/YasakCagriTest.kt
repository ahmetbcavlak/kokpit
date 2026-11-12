package dev.kokpit.anaekran.veri

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K-29 / K-75: the data layer only reads. No broadcast is sent, no service bound, no accessibility
 * action taken, no setting written. Checks the source code itself (comments removed).
 */
class YasakCagriTest {

    private val yasak = listOf(
        Regex("""\bsend(Ordered|Sticky|StickyOrdered)?Broadcast(AsUser)?\s*\("""),
        Regex("""\bbindService\s*\("""),
        Regex("""\bstart(Foreground)?Service\s*\("""),
        Regex("""\bperformAction\s*\("""),
        Regex("""\bperformGlobalAction\s*\("""),
        Regex("""\bdispatchGesture\s*\("""),
        Regex("""\bSettings\s*\.\s*(Secure|System|Global)\s*\.\s*put\w*\s*\("""),
        Regex("""\bSystemProperties\b"""),
        Regex("""\bsetSystemProperty\b"""),
        Regex("""IAirCtrl"""),
    )

    private fun kaynak(): File {
        // Gradle runs unit tests in the module directory (app/).
        val f = File("src/main/java/dev/kokpit/anaekran/veri")
        assertTrue("source not found from ${File(".").absolutePath}", f.isDirectory)
        return f
    }

    private fun yorumsuz(s: String) = s
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun veriKatmaniYalnizOkur() {
        val dosyalar = kaynak().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("too few files: ${dosyalar.map { it.name }}", dosyalar.size >= 10)
        val bulgular = dosyalar.flatMap { f ->
            val metin = yorumsuz(f.readText())
            yasak.filter { it.containsMatchIn(metin) }.map { "${f.name}: ${it.pattern}" }
        }
        assertTrue("forbidden calls: $bulgular", bulgular.isEmpty())
    }

    @Test
    fun erisilebilirlikAyariHareketIzniVermez() {
        val xml = File("src/main/res/xml/kapi_servisi.xml").readText()
        val ayar = xml.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
        for (y in listOf("canPerformGestures", "flagRequestTouchExplorationMode", "flagRequestFilterKeyEvents", "canRequestFilterKeyEvents", "packageNames")) {
            assertTrue("$y in kapi_servisi.xml", !ayar.contains(y))
        }
        assertTrue(ayar.contains("flagRetrieveInteractiveWindows"))
        assertTrue(ayar.contains("""android:notificationTimeout="0""""))
    }
}
