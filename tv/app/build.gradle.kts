plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/* Werte für den Google-Gerätelogin kommen beim Bauen aus GitHub-Secrets (Umgebungsvariablen). */
fun env(name: String) = (System.getenv(name) ?: "").replace("\"", "")

android {
    namespace = "de.lychtlos.fundustv"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.lychtlos.fundustv"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${env("FUNDUS_TV_CLIENT_ID")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"${env("FUNDUS_TV_CLIENT_SECRET")}\"")
    }

    signingConfigs {
        /* Fester Schlüssel im Repo, damit neue Versionen sich über alte installieren lassen. */
        create("fundus") {
            storeFile = file("fundus-tv.keystore")
            storePassword = "fundustv"
            keyAlias = "fundustv"
            keyPassword = "fundustv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fundus")
        }
        debug {
            signingConfig = signingConfigs.getByName("fundus")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
