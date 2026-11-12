package dev.kokpit.anaekran

import org.robolectric.internal.dependency.DependencyResolver
import org.robolectric.pluginapi.Sdk
import org.robolectric.plugins.DefaultSdkProvider
import java.util.TreeMap
import javax.annotation.Priority
import javax.inject.Inject

/**
 * Robolectric knows SDKs up to 35 and loads the newest one's jar for framework resources,
 * even when every test runs on 27. Only the Android 8.1 jar is on disk (offline build), and
 * 8.1 is the head unit's version, so this provider offers SDK 27 alone.
 * Registered in META-INF/services/org.robolectric.pluginapi.SdkProvider (test only).
 */
@Priority(1)
class YalnizSdk27 @Inject constructor(cozucu: DependencyResolver) : DefaultSdkProvider(cozucu) {
    override fun populateSdks(knownSdks: TreeMap<Int, Sdk>) {
        super.populateSdks(knownSdks)
        knownSdks.keys.retainAll(setOf(27))
    }
}
