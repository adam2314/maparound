// The website's map legend and the maps' data schema (docs/map/, docs/he/map/), made from the
// Topo style and my-pipeline's tag mapping with the app's own legend code. Run from the repo root:
//   ./gradlew :shared:compileDebugKotlinAndroid       (the shared code it uses, once after changes)
//   ./gradlew -p tools/map-docs run --args="--pipeline ../my-pipeline"
plugins {
    kotlin("jvm") version "2.1.0"
    application
}
// The shared module's compiled classes (its Android build; the legend, the texts and the place
// kinds are plain Kotlin).
val sharedClasses = file("../../shared/build/tmp/kotlin-classes/debug")
dependencies {
    implementation(files(sharedClasses))
    // The style's symbols, as the map reads them.
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    // What those shared classes use (versions as in shared/build.gradle.kts): the texts' language
    // is Compose state.
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.7.3")
    implementation("com.squareup.okio:okio:3.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
    implementation("org.jetbrains.kotlinx:atomicfu:0.25.0")
}
tasks.named("compileKotlin") {
    doFirst {
        check(sharedClasses.isDirectory) {
            "Build the shared code first: ./gradlew :shared:compileDebugKotlinAndroid"
        }
    }
}
application {
    mainClass.set("MapDocsKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}
// Relative paths in --args are resolved from the repository root.
tasks.named<JavaExec>("run") { workingDir = file("../..") }
