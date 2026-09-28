import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

// Gradle itself runs on Rider's bundled JBR (JDK 25, see gradlew.env). The platform targets 21,
// so compile with --release 21 instead of asking for a separate JDK 21 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Locally: build against the installed Rider (no multi-GB SDK download).
// CI (no Rider.app, or -PriderPath= empty): download the matching Rider from JetBrains.
val riderPath: String? = providers.gradleProperty("riderPath").orNull?.takeIf { it.isNotBlank() && file(it).exists() }

dependencies {
    intellijPlatform {
        if (riderPath != null) local(riderPath) else rider(providers.gradleProperty("platformVersion"))
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

// ---- local dev loop: ./gradlew installToRider, then restart Rider ----
// Copies the built plugin straight into Rider's user plugins dir, skipping the "Install from Disk" dialog.
val riderDataDirName: String = riderPath
    ?.let { file("$it/Contents/Resources/product-info.json") }
    ?.takeIf { it.exists() }
    ?.let { Regex("\"dataDirectoryName\"\\s*:\\s*\"([^\"]+)\"").find(it.readText())?.groupValues?.get(1) }
    ?: "Rider2026.2"

tasks.register<Sync>("installToRider") {
    group = "intellij platform"
    description = "Install the built plugin into ~/Library/Application Support/JetBrains/$riderDataDirName/plugins (restart Rider afterwards)."
    dependsOn(tasks.buildPlugin)
    from(tasks.buildPlugin.map { zipTree(it.archiveFile) })
    // The zip is rooted at code-walk/…; drop that so the sync target is exactly the plugin dir.
    eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
    includeEmptyDirs = false
    into(File(System.getProperty("user.home"), "Library/Application Support/JetBrains/$riderDataDirName/plugins/code-walk"))
    doLast { println("Installed to $destinationDir — restart Rider to load it.") }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
            untilBuild = providers.gradleProperty("platformUntilBuild")
        }
    }
    buildSearchableOptions = false
}
