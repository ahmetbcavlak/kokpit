import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.kokpit.anaekran"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kokpit.anaekran"
        minSdk = 27
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.0"
    }

    // Release key comes from the environment (same as kesif/). Never commit a keystore or password.
    signingConfigs {
        // Ana Ekran has its own key (K-91), separate from Keşif's "kokpit" key. Every release must be
        // signed with this same key: an update keeps the accessibility service enabled, a
        // remove-and-install switches it off. The key is never committed (keys/ is git-ignored).
        create("anaekran") {
            storeFile = file(System.getenv("ANAEKRAN_KEYSTORE") ?: "../../keys/anaekran.jks")
            storePassword = System.getenv("ANAEKRAN_KEYSTORE_PASSWORD") ?: ""
            keyAlias = "anaekran"
            keyPassword = System.getenv("ANAEKRAN_KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("anaekran")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The clips are played from their file descriptor (openRawResourceFd): they must stay stored,
    // not deflated. AAPT2 already stores .mp4 by default; this makes it explicit.
    androidResources { noCompress += "mp4" }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Deliberately no runtime libraries: platform Activity + android.widget only (APK size, start-up time).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}

// Robolectric runs offline against the Android 8.1 jar that is already on disk
// (src/test/.../YalnizSdk27.kt limits Robolectric to that SDK).
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", System.getenv("ROBOLECTRIC_JARS") ?: "/opt/robolectric-jars")
}
