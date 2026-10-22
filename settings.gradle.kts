pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileClaw"
include(":app")
// The AppFunctions demo callee: a second, separate app the agent controls
// over Android 16's function bus. Its own applicationId, its own APK; it shares this
// build only so the toolchain (compileSdk 36, KSP2, appfunctions alpha08) is one thing.
include(":demotarget")
