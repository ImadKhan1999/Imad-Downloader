import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

val appVersion = libs.versions.app.get()

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
    testImplementation(platform("org.junit:junit-bom:${libs.versions.junit.get()}"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    dependsOn("buildExtension") // tests check the packaged extension builds
    systemProperty("snapshotDir", rootProject.file("docs/screenshots").path)
    systemProperty("realNetwork", (project.findProperty("realNetwork") ?: "false").toString())
    testLogging.showStandardStreams = true
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Version visible to the app at runtime (About box, extension handshake).
val generateVersion by tasks.registering {
    val out = layout.buildDirectory.dir("generated/version")
    outputs.dir(out)
    inputs.property("version", appVersion)
    doLast {
        val f = out.get().file("com/imad/downloader/desktop/AppVersion.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package com.imad.downloader.desktop\n\nconst val APP_VERSION = \"$appVersion\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateVersion) }

// The browser extension (Chromium and Firefox builds) ships inside the app; the app copies it
// into the user profile when it adds it to a browser.
val buildExtension by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    commandLine("node", "extension/build.mjs")
    inputs.files(fileTree(rootProject.file("extension")) { exclude("dist/**", "test/**") })
    outputs.dir(rootProject.file("extension/dist"))
}
val syncExtension by tasks.registering(Sync::class) {
    dependsOn(buildExtension)
    from(rootProject.file("extension/dist")) { exclude("*.zip") }
    into(layout.buildDirectory.dir("appResources/common/extension"))
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(syncExtension) }

compose.desktop {
    application {
        mainClass = "com.imad.downloader.desktop.MainKt"
        jvmArgs += listOf("-Xmx512m", "-Dfile.encoding=UTF-8", "-Dsun.java2d.d3d=true")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            packageName = "iDownloader"
            packageVersion = appVersion
            description = "iDownloader - fast multi-connection download manager"
            vendor = "ImadLabs"
            copyright = "© 2026 ImadLabs"
            modules("java.naming", "java.net.http", "jdk.httpserver", "jdk.crypto.ec", "java.management", "jdk.unsupported")
            windows {
                iconFile.set(project.file("src/main/resources/icons/app.ico"))
                menuGroup = "iDownloader"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "7b6a3c1e-4d2f-4e8a-9c51-6f0d2b8e9a14"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
