@file:Suppress("UnstableApiUsage")

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Keep this module's output under the repository-root build/ directory.
layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("profile-core"))

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.typesafe:config:1.4.3")
    testImplementation("junit:junit:4.13.2")
}

/**
 * Renders every bundled HOCON kernel profile into the one schema-v3 JSON
 * document the native side reads. The exporter runs the same [ProfileMerger] /
 * [ProfileResolver] / [com.sesame.app.data.profile.ProfileDocument] code as the
 * app.
 */
tasks.register<JavaExec>("exportKernelProfiles") {
    description = "Render bundled HOCON kernel profiles into schema-v3 JSON documents"
    group = "build"
    val profilesDir = rootProject.layout.projectDirectory.dir("app/src/main/assets/kernel_profiles")
    val outputDir = rootProject.layout.buildDirectory.dir("kernel-profiles")
    inputs.dir(profilesDir)
    /* The exporter's output depends on this module's compiled code as much as on
     * the assets. Without the classpath in `inputs`, a Kotlin change leaves the
     * previous .bin files marked up-to-date -- and the exporter-agreement test
     * then compares fresh app documents against stale exporter output, which is
     * the exact drift that test exists to catch. Measured 2026-10-14 on the
     * merge that first ran it with a changed profile-core. */
    inputs.files(sourceSets["main"].runtimeClasspath)
        .withPropertyName("exporterClasspath")
        .withNormalizer(org.gradle.api.tasks.ClasspathNormalizer::class)
    outputs.dir(outputDir)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.sesame.app.data.profile.ProfileExporter")
    args(
        profilesDir.asFile.absolutePath,
        outputDir.get().asFile.absolutePath,
        outputDir.get().asFile.absolutePath,
    )
}
