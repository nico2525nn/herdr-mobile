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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Termux terminal-view/terminal-emulator (JitPack is the only repo;
        // not on Maven Central). Pinned, content-filtered to termux group.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.termux.termux-app")
            }
        }
    }
}

rootProject.name = "herdr-mobile"

include(":app")
include(":core-model")
include(":core-network")
include(":core-designsystem")
include(":feature-home")
include(":feature-terminal")
include(":feature-settings")
include(":terminal-emulator")
include(":terminal-view")
include(":connection-ssh")
include(":connection-direct")
include(":notifications")