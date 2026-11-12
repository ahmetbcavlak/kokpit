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
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release key comes from the environment (same as kesif/). Never commit a keystore or password.
    signingConfigs {
        create("kokpit") {
            storeFile = file(System.getenv("KOKPIT_KEYSTORE") ?: "../../keys/kokpit.jks")
            storePassword = System.getenv("KOKPIT_KEYSTORE_PASSWORD") ?: ""
            keyAlias = "kokpit"
            keyPassword = System.getenv("KOKPIT_KEYSTORE_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("kokpit")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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
