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
    }
}

rootProject.name = "GhostStream"

include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:media",
    ":core:storage",
    ":core:session",
    ":core:settings",
    ":feature:home",
    ":feature:library",
    ":feature:session",
    ":feature:settings",
    ":feature:onboarding",
    ":feature:networksetup",
    ":webassets",
)

