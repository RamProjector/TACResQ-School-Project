buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Keep the Android build plugin and Kotlin compiler aligned with this toolchain.
        classpath("com.android.tools.build:gradle:9.5.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")

        // Pin vulnerable AGP transitive build-time libraries to patched, compatible releases.
        // These dependencies are for the Gradle plugin classpath; they are not app runtime dependencies.
        classpath("org.bouncycastle:bcprov-jdk18on:1.87")
        classpath("org.bouncycastle:bcpkix-jdk18on:1.87")
        classpath("org.bouncycastle:bcutil-jdk18on:1.87")
        classpath("org.jdom:jdom2:2.0.6.1")
        classpath("com.google.guava:guava:33.7.2-jre")
        classpath("org.bitbucket.b_c:jose4j:0.9.7")
        classpath("org.apache.commons:commons-lang3:3.22.0")
        classpath("org.apache.httpcomponents:httpclient:4.5.14")
    }
}

plugins {
    // AGP is supplied by the root buildscript classpath so its transitive
    // dependencies can be resolved to the patched versions declared above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
