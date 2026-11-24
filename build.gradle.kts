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
    // Resolve AGP from the patched root buildscript classpath, not a second plugin classpath.
    id("com.android.application") apply false
    // AGP is supplied by the root buildscript classpath so its transitive
    // dependencies can be resolved to the patched versions declared above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

tasks.register("verifyBuildToolDependencySecurity") {
    group = "verification"
    description = "Verifies patched versions of vulnerable Android Gradle plugin transitive dependencies."

    doLast {
        val resolvedModules = buildscript.configurations
            .getByName("classpath")
            .incoming
            .resolutionResult
            .allComponents
            .mapNotNull { component ->
                component.moduleVersion?.let { "${it.group}:${it.name}" to it.version }
            }
            .toMap()

        val requiredVersions = mapOf(
            "org.bouncycastle:bcprov-jdk18on" to "1.87",
            "org.bouncycastle:bcpkix-jdk18on" to "1.87",
            "org.bouncycastle:bcutil-jdk18on" to "1.87",
            "org.jdom:jdom2" to "2.0.6.1",
            "com.google.guava:guava" to "33.7.2-jre",
            "org.bitbucket.b_c:jose4j" to "0.9.7",
            "org.apache.commons:commons-lang3" to "3.22.0",
            "org.apache.httpcomponents:httpclient" to "4.5.14",
        )

        requiredVersions.forEach { (module, expectedVersion) ->
            val actualVersion = resolvedModules[module]
            check(actualVersion == expectedVersion) {
                "$module must resolve to $expectedVersion, but resolved ${actualVersion ?: "not found"}"
            }
        }

        println("Verified ${requiredVersions.size} patched Android build-tool dependencies.")
    }
}
