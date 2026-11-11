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
        versionCode = 2
        versionName = "0.2.0"
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
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
