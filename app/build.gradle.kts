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
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation("com.github.csukuangfj.piper-phonemize:piper-phonemize:v1.5.9")
}
