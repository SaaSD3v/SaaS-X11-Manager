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

// Experimental embedded Termux:X11 engine. The upstream source is pinned as a
// git submodule so this spike can be discarded without touching main.
include(":lorie")
project(":lorie").projectDir = file("third_party/termux-x11/lorie")

// :lorie has a compileOnly dependency on the upstream shell-loader stub. Keep
// only that stub in the build graph; the external shell-loader application is
// intentionally not built or required by SaaS X11 Manager.
include(":shell-loader:stub")
project(":shell-loader").projectDir = file("shell-loader")
project(":shell-loader:stub").projectDir = file("third_party/termux-x11/shell-loader/stub")
