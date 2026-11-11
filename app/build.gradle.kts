plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autolyrics"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autolyrics"
        minSdk = 26
        targetSdk = 34
        versionCode = 39
        versionName = "2.0.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("signing.p12")
            storePassword = "autolyrics"
            keyAlias = "autolyrics"
            keyPassword = "autolyrics"
        }
        // Your own private key. GitHub Actions writes it from repository secrets
        // (see .github/workflows/build.yml); never commit the key itself.
        create("release") {
            val storePath = System.getenv("SIGNING_STORE_FILE")
            if (storePath != null && file(storePath).exists()) {
                storeFile = file(storePath)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val hasOwnKey = System.getenv("SIGNING_STORE_FILE")?.let { file(it).exists() } == true
            signingConfig = signingConfigs.getByName(if (hasOwnKey) "release" else "debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Android Auto - MediaBrowserService
    implementation("androidx.media:media:1.7.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Color extraction from album art
    implementation("androidx.palette:palette-ktx:1.0.0")

    // ML Kit — on-device language detection & translation
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests (run by GitHub Actions on every push)
    testImplementation("junit:junit:4.13.2")
}
