pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "weartak-voice"
include(":core", ":app", ":weartak-sdk")
project(":weartak-sdk").projectDir = file(System.getenv("WEARTAK_SDK_SOURCE") ?: ".deps/sdk")
