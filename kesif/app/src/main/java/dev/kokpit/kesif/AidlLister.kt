package dev.kokpit.kesif

import android.content.Context
import dalvik.system.PathClassLoader
import java.lang.reflect.Modifier

/**
 * Reads the AIDL interfaces in the vendor's APK for documentation. Classes are loaded with
 * initialize = false. Reading the Stub's TRANSACTION_ constants initialises that one class; AIDL
 * stubs hold only constants, so no vendor logic runs. Nothing is invoked, nothing is sent to the
 * service or the vehicle.
 */
object AidlLister {

    private val focus = Regex("IAirCtrl|AirCtrl|CanService", RegexOption.IGNORE_CASE)

    fun list(ctx: Context, pkg: String, descriptor: String?, note: String): List<Line> {
        val out = ArrayList<Line>()
        out += Line("Servisin bildirdiği arayüz", descriptor ?: "(okunamadı)")
        out += Line("Kaynak", note)
        val apk = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()
            ?: return out + Line("APK", "$pkg bulunamadı")
        out += Line("APK", apk)

        // Every class name in the APK (works for preopted system apps too; the runtime finds the odex).
        @Suppress("DEPRECATION")
        val names: List<String> = runCatching {
            val dex = dalvik.system.DexFile(apk)
            try { dex.entries().toList() } finally { runCatching { dex.close() } }
        }.getOrElse { out += Line("Sınıf listesi", "okunamadı: ${it.javaClass.simpleName}"); emptyList() }

        val stubs = names.filter { it.endsWith("\$Stub") }
        out += Line("APK'daki AIDL arayüzleri", if (stubs.isEmpty()) "bulunamadı" else stubs.joinToString(", ") { it.removeSuffix("\$Stub") }.take(1500))

        // The interfaces to detail: the one the service reported, plus anything named like AirCtrl.
        val targets = (listOfNotNull(descriptor) + stubs.map { it.removeSuffix("\$Stub") }.filter { focus.containsMatchIn(it) })
            .distinct()
        AirCommand.reset(descriptor)
        if (targets.isEmpty()) return out + Line("IAirCtrlServices", "APK'da bulunamadı")
        val found = ArrayList<AirCommand.Candidate>()

        val loader = PathClassLoader(apk, ClassLoader.getSystemClassLoader())
        for (name in targets) try {
            val iface = runCatching { Class.forName(name, false, loader) }.getOrElse {
                out += Line(name, "yüklenemedi: ${it.javaClass.simpleName}"); null
            } ?: continue
            var stubDescriptor: String? = null
            val codes = runCatching {
                val stub = Class.forName("$name\$Stub", false, loader)
                stubDescriptor = runCatching {
                    stub.getDeclaredField("DESCRIPTOR").apply { isAccessible = true }.get(null) as? String
                }.getOrNull()
                stub.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TRANSACTION_") && it.type == Int::class.javaPrimitiveType }
                    .associate { f -> f.isAccessible = true; f.name.removePrefix("TRANSACTION_") to f.getInt(null) }
            }.getOrElse { emptyMap() }
            out += Line("Arayüz", name)
            iface.declaredMethods.sortedBy { codes[it.name] ?: Int.MAX_VALUE }.forEach { m ->
                val sig = "${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})"
                out += Line("   " + (codes[m.name]?.let { "#$it" } ?: "#?"), sig)
                // A climate "temperature up" with no parameters: the only kind the 0.7.1 button may call.
                val code = codes[m.name]
                if (code != null && m.parameterTypes.isEmpty() && AirCommand.looksLikeTempUp(m.name))
                    found += AirCommand.Candidate(stubDescriptor ?: name, name, m.name, sig, code)
            }
        } catch (t: Throwable) {
            // One interface that cannot be read (e.g. a missing parameter type) does not hide the rest.
            out += Line(name, "okunamadı: ${t.javaClass.simpleName} ${t.message.orEmpty()}")
        }
        AirCommand.choose(found)
        out += Line("Klima +0,5° adayı", AirCommand.candidateText())
        return out
    }
}
