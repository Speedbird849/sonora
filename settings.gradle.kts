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
        // InnerTubeX, the YouTube stream extractor, is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "sonora"
include(":app")
