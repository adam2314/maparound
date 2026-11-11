// Desktop preview of MapAround's offline topo rendering (no phone needed).
// Run from the repo root:  ./gradlew -p tools/topo-preview run --args="..."  (see README.md)
plugins {
    kotlin("jvm") version "2.1.0"
    application
}
dependencies {
    implementation("org.mapsforge:mapsforge-map-awt:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
}
// Compile the app's Android-free terrain code directly, so the preview uses the real thing.
sourceSets { main { kotlin.srcDir("../../app/src/main/java/app/maparound/terrain")
    kotlin.srcDir("../../app/src/main/java/app/maparound/trails") } }
application {
    mainClass.set("PreviewKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Xmx2g")
}
// Relative paths in --args are resolved from the repository root.
tasks.named<JavaExec>("run") { workingDir = file("../..") }
