package dev.kokpit.anaekran

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import dev.kokpit.anaekran.arayuz.Arkaplan
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.concurrent.Executor

/** Shared set-up for the Robolectric tests. */
object TestYardim {

    /** Worker-thread jobs run inline; their main-thread results wait for [bosalt]. */
    fun esZamanli() {
        Arkaplan.yurutucu = Executor { it.run() }
    }

    fun bosalt() = shadowOf(Looper.getMainLooper()).idle()

    fun ac(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { bosalt() }

    /** Installs a fake app with one launcher activity. */
    fun uygulamaKur(paket: String, ad: String) {
        val pm = RuntimeEnvironment.getApplication().packageManager
        val ai = ApplicationInfo().apply { packageName = paket; nonLocalizedLabel = ad }
        val act = ActivityInfo().apply {
            packageName = paket
            name = "$paket.Ana"
            applicationInfo = ai
            nonLocalizedLabel = ad
            exported = true
        }
        shadowOf(pm).installPackage(PackageInfo().apply { packageName = paket; applicationInfo = ai })
        shadowOf(pm).addOrUpdateActivity(act)
        shadowOf(pm).addIntentFilterForActivity(
            ComponentName(paket, act.name),
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }
}
