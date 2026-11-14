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
        // Mozilla's own Maven: the only source of GeckoView (the Firefox engine).
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "tveye"
include(":app")
