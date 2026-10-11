pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://build-artifacts.signal.org/libraries/maven/")
            content {
                includeGroupByRegex("org\\.signal.*")
            }
        }
        // The Light SDK's on-screen keyboard (an `api` dependency of sdk-ui) is
        // only published on JitPack.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.lightphone")
            }
        }
    }
}

rootProject.name = "Photon"
include(":app")

// Light SDK UI components, built from the pinned submodule (third_party/light-sdk,
// tag v0.2.0) rather than a registry: sdk-ui isn't published publicly, and
// GitHub Packages needs a token. The SDK source is consumed unmodified;
// `systemProp.lightSdk.toolOnly` in gradle.properties keeps the included build
// from configuring its examples/server/emulator projects (its own builder uses
// the same switch).
includeBuild("third_party/light-sdk") {
    dependencySubstitution {
        substitute(module("com.thelightphone:sdk-ui")).using(project(":sdk:ui"))
    }
}
