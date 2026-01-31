plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.naksh.vibeaudio"
    compileSdk = 34 // ⚠️ Change: 36 is experimental, 34 is stable. Use 34 to be safe.

    defaultConfig {
        applicationId = "com.naksh.vibeaudio"
        minSdk = 24 // ⚠️ Change: Media Controls need minSdk 21+, bumping to 24 is safer.
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

    // 🔥 NEW DEPENDENCY ADDED (Fixes the Error)
    implementation("androidx.media:media:1.7.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}