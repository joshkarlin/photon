// Plugin versions track the Light SDK's (third_party/light-sdk/gradle/libs.versions.toml):
// the SDK is an included build, and AGP/Kotlin must match across a composite.
plugins {
    id("com.android.application") version "8.12.3" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
