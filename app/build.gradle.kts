import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().also { props ->
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) props.load(propsFile.inputStream())
}

android {
    namespace = "app.photon"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.photon"
        minSdk = 29
        targetSdk = 35
        // Overridable from CI: the release workflow passes -PversionCodeOverride
        // and -PversionNameOverride derived from the git tag. Falls back to the
        // baked-in values for local debug builds.
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionNameOverride") as String?) ?: "0.2.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            keyAlias     = keystoreProps.getProperty("keyAlias")     ?: System.getenv("KEY_ALIAS")
            keyPassword  = keystoreProps.getProperty("keyPassword")  ?: System.getenv("KEY_PASSWORD")
            storePassword= keystoreProps.getProperty("storePassword")?: System.getenv("KEYSTORE_PASSWORD")
            storeFile    = (keystoreProps.getProperty("storeFile")   ?: System.getenv("KEYSTORE_PATH"))
                               ?.let { rootProject.file(it) }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // Minify disabled: Signal's libsignal/Jackson code relies on
            // reflection and field-name serialization that R8 would strip or
            // rename, breaking the protocol at runtime (see AndroidRecordFix and
            // the PushServiceSocket reflection workaround). APK size is dominated
            // by native libs anyway.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Signal protocol + service layer
    implementation("com.github.turasa:signal-service-java:2.15.3_unofficial_143") {
        exclude(group = "org.signal", module = "libsignal-client")
    }
    implementation("org.signal:libsignal-android:0.92.1")
}
