plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sa.akhbaraltareeq"
    compileSdk = 35
    defaultConfig {
        applicationId = "sa.akhbaraltareeq"
        minSdk = 23
        targetSdk = 28
        versionCode = 2
        versionName = "0.3.0"
        // The car unit and current Android phones use 64-bit ARM; omit unused native ABIs.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.0")\n    implementation("org.jsoup:jsoup:1.18.3")
}
