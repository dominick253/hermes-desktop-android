// Versions are pinned to artifacts already in this host's Gradle cache,
// so a clean build needs no plugin downloads.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
