buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Keep the Kotlin compiler aligned with Compose Compiler 2.4.21 for AGP 9.5.0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
