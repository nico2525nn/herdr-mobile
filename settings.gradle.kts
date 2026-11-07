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
include(":terminal-ghostty")
include(":baselineprofile")
include(":testing")
include(":core-model")
include(":core-network")
include(":core-designsystem")
include(":feature-home")
include(":feature-terminal")
include(":feature-settings")
include(":connection-ssh")
include(":connection-direct")
include(":notifications")