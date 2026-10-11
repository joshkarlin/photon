import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    compileSdk = 36

    defaultConfig {
        applicationId = "app.photon"
        // The Light SDK requires API 34; the LP3 runs it. targetSdk stays at 35
        // to avoid opting into newer runtime behaviour changes.
        minSdk = 34
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
            // Opt-in debuggable release (-PdebuggableRelease=true) for on-device
            // DB inspection without a key change / relink. Off by default.
            isDebuggable = (project.findProperty("debuggableRelease") as String?)?.toBoolean() ?: false
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Light SDK UI (LightTheme, LightText, LightTopBar, ...), substituted with
    // the third_party/light-sdk submodule's :sdk:ui project in settings.gradle.kts.
    implementation("com.thelightphone:sdk-ui:0.2.0")

    // Versions track the Light SDK's catalog where both use a library.
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.20")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
}
