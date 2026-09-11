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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "X11Manager"
include(":app")

// The upstream Termux:X11 repository stays pinned and untouched. This local wrapper uses
// its Java/resources/CMake sources with the Gradle/AGP versions of this project.
include(":embedded-lorie")

// AVNC 3.3.1 is consumed as a pinned engine-only source module. Its Activity,
// navigation, database and app UI are not part of the Manager surface.
include(":embedded-avnc")

// Lorie references the shell-loader stub at compile time. Only the tiny stub is
// in the graph; the external shell-loader application is not built or required.
include(":shell-loader:stub")
project(":shell-loader").projectDir = file("shell-loader")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
