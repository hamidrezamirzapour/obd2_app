plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.hamidreza.autodiag"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.hamidreza.autodiag"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
