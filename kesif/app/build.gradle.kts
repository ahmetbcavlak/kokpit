plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.kokpit.kesif"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kokpit.kesif"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "0.6.0"
    }

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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("kokpit")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// Previews: Robolectric's downloader is rate-limited in the build sandbox. Put
// android-all-instrumented-8.1.0-robolectric-4611349-i7.jar (Maven Central) into
// app/build/robolectric-jars and the test runs offline.
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath)
}
