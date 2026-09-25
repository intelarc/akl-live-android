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
        // Google's mirror of Maven Central, for when Central turns CI runners away
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
rootProject.name = "AKL Live"
include(":app")
