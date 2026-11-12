package dev.kokpit.anaekran.veri

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** Whether the user switched on "Kokpit kapı uyarısı" in Settings > Accessibility. Reads only. */
object ErisilebilirlikDurumu {

    fun etkinMi(context: Context): Boolean {
        val ben = ComponentName(context, KapiServisi::class.java)
        val liste = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (t: Throwable) {
            null
        }.orEmpty()
        // Settings may hold the short form (pkg/.Class): compare as components, not as strings.
        return liste.split(':').any { ComponentName.unflattenFromString(it.trim()) == ben }
    }

    /** Enabled and actually bound by the system right now (the service has connected). */
    fun bagliMi(): Boolean = Teshis.kapiServisiBagli
}
