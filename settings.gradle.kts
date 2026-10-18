pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "samapp"
if (!providers.gradleProperty("desktopOnly").isPresent) include(":app") // -PdesktopOnly: CI without the Android SDK
include(":desktop")
