package dev.kokpit.kesif

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
class PackageExporterTest {

    private fun fakeApk(dir: File, name: String, size: Int): File =
        File(dir, name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(size) { (it * 31 % 251).toByte() }) }

    private fun install(app: Application, pkg: String, base: File, splits: List<File> = emptyList()) {
        val ai = ApplicationInfo().apply {
            packageName = pkg; sourceDir = base.path; publicSourceDir = base.path
            splitSourceDirs = if (splits.isEmpty()) null else splits.map { it.path }.toTypedArray()
        }
        val pi = PackageInfo().apply { packageName = pkg; versionName = "1.2"; applicationInfo = ai }
        shadowOf(app.packageManager).installPackage(pi)
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun exportsVendorAndSettingsWithSplits() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val sys = File(app.cacheDir, "fake-system").apply { mkdirs() }
        val can = fakeApk(sys, "autocan/base.apk", 300_000)
        val canSplit = fakeApk(sys, "autocan/split_config.mdpi.apk", 12_345)
        val swc = fakeApk(sys, "swc/base.apk", 50_000)
        val settings = fakeApk(sys, "settings/Settings.apk", 80_000)
        val other = fakeApk(sys, "other/base.apk", 1_000)
        install(app, "com.hcn.autocan", can, listOf(canSplit))
        install(app, "com.hcn.swc", swc)
        install(app, "com.android.settings", settings)
        install(app, "com.example.notme", other)

        // Stand-ins for /system/app/AutoSetting/oat, /system/priv-app/MtkSettings/oat and /data/config.
        val oat = File(sys, "system/app/AutoSetting/oat").apply { mkdirs() }
        fakeApk(oat, "arm/AutoSetting.odex", 70_000)
        fakeApk(oat, "arm/AutoSetting.vdex", 20_000)
        val missingOat = File(sys, "system/priv-app/MtkSettings/oat") // does not exist
        val cfg = File(sys, "data/config").apply { mkdirs() }
        fakeApk(cfg, "can/canbus.ini", 512)
        fakeApk(cfg, "carplay/zlink.cfg", 2048)
        fakeApk(cfg, "big.bin", 6 * 1024 * 1024)
        fakeApk(cfg, "update/Theme.apk", 1000)
        java.nio.file.Files.createSymbolicLink(File(cfg, "gone.cfg").toPath(), File(sys, "nowhere").toPath())
        java.nio.file.Files.createSymbolicLink(File(cfg, "loop").toPath(), cfg.toPath()) // must not recurse forever
        // A FIFO would block open() forever; a link out of the root must not be followed.
        val fifo = File(cfg, "pipe"); ProcessBuilder("mkfifo", fifo.path).start().waitFor()
        java.nio.file.Files.createSymbolicLink(File(cfg, "rootlink").toPath(), File("/").toPath())
        fakeApk(cfg, "a b.cfg", 10); fakeApk(cfg, "a_b.cfg", 11)
        PackageExporter.systemDirs = listOf(oat.path, missingOat.path)
        PackageExporter.configDir = cfg.path

        PackageExporter.init(app)
        val sources = PackageExporter.sources(app)
        assertEquals(setOf("com.hcn.autocan", "com.hcn.swc", "com.android.settings"), sources.map { it.pkg }.toSet())
        assertEquals(4, sources.size) // autocan base + split, swc, settings

        assertTrue(PackageExporter.start(app))
        val until = System.currentTimeMillis() + 20_000
        while (PackageExporter.running && System.currentTimeMillis() < until) Thread.sleep(50)
        assertFalse(PackageExporter.running)
        println(PackageExporter.statusText())

        val out = PackageExporter.dir(app)
        val names = out.list()!!.sorted()
        println(names)
        assertTrue(names.none { it.endsWith(".tmp") })
        val split = File(out, "com.hcn.autocan-1.2-split_config.mdpi.apk")
        assertTrue(split.exists())
        assertEquals(sha(canSplit), sha(split))
        assertEquals(sha(can), sha(File(out, "com.hcn.autocan-1.2-base.apk")))
        assertTrue(File(out, "com.android.settings-1.2-base.apk").exists())
        assertFalse(names.any { it.contains("notme") })
        // The export folder sits inside the CSV recording folder.
        assertEquals(File(app.filesDir, "kayit"), out.parentFile)

        val manifest = File(out, "paketler.csv").readText()
        println(manifest)
        assertTrue(manifest.contains("Build.FINGERPRINT"))
        assertTrue(manifest.contains(sha(canSplit)))
        assertTrue(manifest.contains("kopyalandı"))

        // System folders are mirrored with their structure, under sistem/<absolute path>.
        val mirrorOat = File(out, "sistem/" + oat.path.trimStart('/') + "/arm/AutoSetting.odex")
        assertTrue(mirrorOat.path, mirrorOat.exists())
        val mirrorCfg = File(out, "sistem/" + cfg.path.trimStart('/') + "/can/canbus.ini")
        assertTrue(mirrorCfg.exists())
        assertFalse(names.contains("big.bin"))
        assertFalse(out.walkTopDown().any { it.name == "big.bin" || it.name == "Theme.apk" })
        assertTrue(manifest.contains("atlandı (5 MB'tan büyük)"))
        assertTrue(manifest.contains("atlandı (.apk)"))
        assertTrue(manifest.lines().any { it.contains("gone.cfg") && it.contains("okunamadı") })
        assertTrue(manifest.lines().any { it.contains("MtkSettings") && it.contains("okunamadı") })
        assertTrue(manifest.lines().any { it.contains("/pipe") && it.contains("özel dosya") })
        assertTrue(manifest.lines().any { it.contains("rootlink") && it.contains("klasör dışına") })
        // "a b.cfg" and "a_b.cfg" both kept, under different names.
        val cfgMirror = File(out, "sistem/" + cfg.path.trimStart('/'))
        assertTrue(File(cfgMirror, "a_b.cfg").exists() && File(cfgMirror, "a_b~2.cfg").exists())
        // getprop is not available on the test machine: recorded, run still finished.
        assertTrue(manifest.lines().any { it.startsWith("getprop,") })
        assertTrue(PackageExporter.statusText().startsWith("Bitti"))
        val rel = "sistem/" + cfg.path.trimStart('/') + "/can/canbus.ini"
        assertEquals(mirrorCfg.canonicalPath, PackageExporter.byName(rel)!!.canonicalPath)
        val zip = java.io.ByteArrayOutputStream().also { PackageExporter.writeDiagnosticZip(it) }
        val entries = java.util.zip.ZipInputStream(zip.toByteArray().inputStream()).let { z ->
            generateSequence { z.nextEntry?.name }.toList()
        }
        println(entries)
        assertTrue(entries.contains("paketler.csv") && entries.contains(rel))
        assertFalse(entries.any { it.endsWith("-base.apk") })

        // A second run copies nothing again.
        assertTrue(PackageExporter.start(app))
        while (PackageExporter.running) Thread.sleep(50)
        assertTrue(File(out, "paketler.csv").readText().contains("zaten var"))
        assertEquals("paketler.csv", PackageExporter.files().first().first)
        assertEquals(null, PackageExporter.byName("../shared_prefs/kayit.xml"))
    }
}
