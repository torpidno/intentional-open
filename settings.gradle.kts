pluginManagement {
    repositories {
        // Keep this unfiltered: AGP itself depends on Google-hosted artifacts
        // such as com.google.testing.platform, which live only on Google Maven.
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

rootProject.name = "Pause"
include(":app")
