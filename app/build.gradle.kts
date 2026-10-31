plugins {
    alias(libs.plugins.android.application)
}

base {
    archivesName.set("Music-Browser")
}

android {
    namespace = "com.example.musicbrowser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.musicbrowser"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.media:media:1.7.0")
    
    // GeckoView for browser engine
    implementation("org.mozilla.geckoview:geckoview:131.0.20241011205646")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}