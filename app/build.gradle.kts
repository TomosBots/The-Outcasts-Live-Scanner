plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.theoutcasts.livescanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.theoutcasts.livescanner"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Keep ML Kit temporarily while the scanner is migrated to Tesseract.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Offline OCR engine (Tesseract 5.5.1).
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
