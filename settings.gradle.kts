pluginManagement {
    repositories.google()
    repositories.mavenCentral()
    repositories.gradlePluginPortal()
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories.mavenCentral()
    repositories.google()
}

rootProject.name = "ScreenStream"

include(":common")
include(":mjpeg")
include(":webrtc")
include(":app")