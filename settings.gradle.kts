pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
rootProject.name = "kinetix3d-studio"

// Thin application shell
include(":app")

// Pure-Kotlin JVM modules (unit-tested without an Android SDK; see CI)
include(":core-model")
include(":core-evaluator")

// Android library modules
include(":core-platform-api")
include(":engine-media")
include(":engine-render")
include(":engine-ai")
include(":ui-studio")
