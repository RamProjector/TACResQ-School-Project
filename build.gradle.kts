buildscript {
    // Resolve AGP through the root buildscript classpath so its transitive toolchain
    // dependencies can be constrained to patched, binary-compatible releases.
    configurations.classpath {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
            "com.google.guava:guava:33.7.2-jre",
            "org.jdom:jdom2:2.0.6.1",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.apache.httpcomponents:httpmime:4.5.14",
        )
    }
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.5.2")
        // Keep the Kotlin compiler aligned with Compose Compiler 2.4.21 for AGP 9.5.0.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
