package dev.kokpit.anaekran.veri

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * K-29 / K-75: the app only reads. No broadcast is sent, no service bound, no accessibility
 * action taken, no setting written, no process started. Checks the whole of src/main's source
 * code itself (comments removed), not only the data layer.
 *
 * Two exceptions, 0.2.2 (K-116), both in src/main/java/dev/kokpit/anaekran/veri/KapiOzellikleri.kt
 * alone (matched by that full path, so a file of the same name elsewhere gets nothing) and each as
 * one exact text:
 *  - reading a system property by reflection: `Class.forName("android.os.SystemProperties")
 *    .getMethod("get", String::class.java)` — the getter only, never "set";
 *  - the getprop fallback: `ProcessBuilder("getprop", ad)` — that binary with one argument, no shell;
 *    the argument is checked against the five fixed property names before the process starts
 *    (KapiOzellikleriTest covers that).
 * Why: the vendor publishes the door state only as system properties; reading them is the only way to
 * get it without the popup, and neither call can change anything. The exact texts are cut out of that
 * file and the rest of it is scanned with every pattern like any other file, so a second use, another
 * command or a setter would still be caught. Settings.*.put* stays forbidden everywhere.
 *
 * 0.2.2 review (R1 KÜÇÜK-9): also caught — a process through android.system.Os.exec*, writing a
 * setting through the ContentResolver (insert/update/bulkInsert/delete on a Settings URI, a
 * provider call "PUT_…"), and reflection (Class.forName, getMethod / getDeclaredMethod) outside the
 * one excepted text. Still a text scan: a URI kept in a variable, or names built from pieces, are
 * not caught; the code review is the other half of this rule.
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
        Regex("""\bOs\s*\.\s*exec\w*\s*\("""),
        Regex("""\b(insert|update|bulkInsert|delete)\s*\(\s*Settings\b"""),
        Regex("""\bSettings\s*\.\s*(Secure|System|Global)\s*\.\s*CONTENT_URI\b"""),
        Regex(""""PUT_\w*""""),
        Regex("""\bClass\s*\.\s*forName\b"""),
        Regex("""\bget(Declared)?Method\s*\("""),
    )

    /** File (its full path under the module) → the only texts of a forbidden pattern allowed in it (each exactly once). */
    private val istisnalar = mapOf(
        "src/main/java/dev/kokpit/anaekran/veri/KapiOzellikleri.kt" to listOf(
            "Class.forName(\"android.os.SystemProperties\").getMethod(\"get\", String::class.java)",
            "ProcessBuilder(\"getprop\", ad)",
        ),
    )

    /** The file's text with its allowed exceptions cut out; fails if one is not there exactly once. */
    private fun istisnasiz(f: File, metin: String): String {
        val yol = f.invariantSeparatorsPath
        val izinli = istisnalar.entries.firstOrNull { yol == it.key || yol.endsWith("/" + it.key) }?.value ?: return metin
        var kalan = metin
        for (i in izinli) {
            assertEquals("allowed text in $yol must appear exactly once: $i", 1, kalan.split(i).size - 1)
            kalan = kalan.replace(i, " ")
        }
        return kalan
    }

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
            val metin = istisnasiz(f, yorumsuz(f.readText()))
            yasak.filter { it.containsMatchIn(metin) }.map { "${f.path}: ${it.pattern}" }
        }
        assertTrue("forbidden calls: $bulgular", bulgular.isEmpty())
    }

    @Test
    fun istisnalarDar() {
        val f = File("src/main/java/dev/kokpit/anaekran/veri/KapiOzellikleri.kt")
        val metin = yorumsuz(f.readText())
        // Exactly the allowed texts, nothing else forbidden, no setter looked up, no shell.
        assertTrue(yasak.none { it.containsMatchIn(istisnasiz(f, metin)) })
        for (y in listOf("\"set\"", "native_set", "\"sh\"", "\"su\"", "/system/bin", ".command(")) {
            assertTrue("$y in KapiOzellikleri.kt", !metin.contains(y))
        }
        // The exception is tied to that file: the same text anywhere else is caught, also in a file
        // of the same name in another package (the full path is the key, not the file name).
        for (ornek in listOf("ProcessBuilder(\"getprop\", ad)", "Class.forName(\"android.os.SystemProperties\")")) {
            for (baska in listOf("src/main/java/X.kt", "src/main/java/dev/kokpit/anaekran/baska/veri/KapiOzellikleri.kt", "src/main/KapiOzellikleri.kt")) {
                assertTrue("not caught in $baska: $ornek", yasak.any { it.containsMatchIn(istisnasiz(File(baska), ornek)) })
            }
        }
        // A second process in the excepted file is still caught.
        val ikinci = metin + "\nProcessBuilder(\"getprop\", ad)"
        try {
            istisnasiz(f, ikinci)
            throw AssertionError("second ProcessBuilder not caught")
        } catch (e: AssertionError) {
            assertTrue(e.message.orEmpty().contains("exactly once"))
        }
        val baskaKomut = metin + "\nProcessBuilder(\"sh\", \"-c\", x)"
        assertTrue(yasak.any { it.containsMatchIn(istisnasiz(f, baskaKomut)) })
    }

    @Test
    fun ayarYazmaYasagiAynen() {
        for (ornek in listOf(
            "Settings.System.putInt(cr, \"can_ignore_doorinfo\", 1)", "Settings.System.putString(cr, k, v)",
            "Settings.Global.putInt(cr, k, 0)", "Settings.Secure.putString(cr, k, v)",
            // Through the ContentResolver instead of the helpers.
            "cr.insert(Settings.System.CONTENT_URI, v)", "contentResolver.update(Settings.Global.CONTENT_URI, v, null, null)",
            "val u = Settings.Secure.CONTENT_URI", "cr.call(u, \"PUT_system\", \"can_ignore_doorinfo\", b)",
            "cr.bulkInsert(Settings.System.CONTENT_URI, a)",
        )) {
            assertTrue("not caught: $ornek", yasak.any { it.containsMatchIn(ornek) })
        }
        assertTrue(yasak.none { it.containsMatchIn("Settings.System.getInt(cr, AYAR_CAN_IGNORE_DOORINFO)") })
    }

    @Test
    fun desenlerYasakCagrilariYakalar() {
        for (ornek in listOf(
            "sendOrderedBroadcast(i, null)", "context.sendStickyBroadcast(i)", "sendBroadcast(i)",
            "Runtime.getRuntime().exec(\"su\")", "val r = Runtime.getRuntime(); r.exec(arrayOf(\"sh\"))",
            "ProcessBuilder(\"sh\").start()", "bindService(i, c, 0)", "n.performAction(16)",
            "Os.execv(\"/system/bin/sh\", a)", "android.system.Os.execve(p, a, e)", "Os . execvp(x, y)",
            "Class.forName(\"android.os.ServiceManager\")", "c.getDeclaredMethod(\"set\", String::class.java)",
            "k.getMethod(\"set\", String::class.java, String::class.java)",
        )) {
            assertTrue("not caught: $ornek", yasak.any { it.containsMatchIn(ornek) })
        }
        // Ordinary code is not flagged.
        for (ornek in listOf(
            "executor.execute(r)", "startActivity(i)", "registerReceiver(a, f)", "dondur?.invoke(d)",
            "Settings.System.getInt(cr, k)", "liste.update(x)", "serviceInfo = bilgi",
        )) {
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
        // 0.2.2 (R1 ÖNEMLİ-4): no clicks in the static subscription; KapiServisi adds them to its own
        // subscription only while a door popup is up and the properties are not the source.
        assertTrue(!ayar.contains("typeViewClicked"))
        assertTrue(ayar.contains("""android:notificationTimeout="0""""))
    }
}
