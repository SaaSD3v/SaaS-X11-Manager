// Top-level build file
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// The pinned Lorie source currently declares much newer AndroidX/Kotlin
// versions than this app uses. Those dependencies are implementation details of
// the upstream module, not APIs required by our host app, and allowing Gradle to
// select them upgrades the complete Lifecycle/Compose graph to versions that
// require AGP 9 / compileSdk 37.
//
// Keep the embedded engine on the dependency family already validated by the
// structural branch. Apply the constraint to every subproject so :lorie itself
// compiles against the same versions instead of only excluding transitives from
// :app.
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlin:kotlin-stdlib:1.9.22",
                "org.jetbrains.kotlin:kotlin-stdlib-common:1.9.22",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.9.22",
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22",
                "androidx.core:core:1.12.0",
                "androidx.core:core-ktx:1.12.0",
                "androidx.lifecycle:lifecycle-common:2.7.0",
                "androidx.lifecycle:lifecycle-common-java8:2.7.0",
                "androidx.lifecycle:lifecycle-process:2.7.0",
                "androidx.lifecycle:lifecycle-runtime:2.7.0",
                "androidx.lifecycle:lifecycle-runtime-ktx:2.7.0",
                "androidx.lifecycle:lifecycle-runtime-compose:2.7.0",
                "androidx.lifecycle:lifecycle-viewmodel:2.7.0",
                "androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0",
                "androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0"
            )
        }
    }
}
