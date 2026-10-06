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