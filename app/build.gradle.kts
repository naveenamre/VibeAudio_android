plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.naksh.vibeaudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.naksh.vibeaudio"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // 🔥 REQUIRED FOR NOTIFICATION & MEDIA CONTROLS
    implementation("androidx.media:media:1.7.0")

    // 🔥 REQUIRED FOR OFFLINE FILE PLAYBACK (The Fix)
    implementation("androidx.webkit:webkit:1.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}