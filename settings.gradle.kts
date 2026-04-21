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
    }
}

rootProject.name = "Photon"
include(":app")
