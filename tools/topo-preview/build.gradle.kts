// Desktop preview of MapAround's offline topo rendering (no phone needed).
// Run from the repo root (see README.md):
//   ./gradlew :shared:compileDebugKotlinAndroid       (the shared code it uses, once after changes)
//   ./gradlew -p tools/topo-preview run --args="..."
plugins {
    kotlin("jvm") version "2.1.0"
    application
}
// The shared module's compiled classes (its Android build; the parts used here are plain JVM:
// DemIndex, HgtProcessor, Contours, Bits, the trail cell format…).
val sharedClasses = file("../../shared/build/tmp/kotlin-classes/debug")
dependencies {
    implementation("org.mapsforge:mapsforge-map-awt:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    implementation(files(sharedClasses))
    // What those shared classes use (versions as in shared/build.gradle.kts).
    implementation("com.squareup.okio:okio:3.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
}
// Compile the app's Android-free terrain code directly, so the preview uses the real thing.
sourceSets { main { kotlin.srcDir("../../app/src/main/java/app/maparound/terrain")
    kotlin.srcDir("../../app/src/main/java/app/maparound/trails") } }
tasks.named("compileKotlin") {
    doFirst {
        check(sharedClasses.isDirectory) {
            "Build the shared code first: ./gradlew :shared:compileDebugKotlinAndroid"
        }
    }
}
application {
    mainClass.set("PreviewKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Xmx2g")
}
// Relative paths in --args are resolved from the repository root.
tasks.named<JavaExec>("run") { workingDir = file("../..") }
