// Plays party members for the screenshots (tools/shots): the app's own party code (OnlineParty)
// on the computer, one per member, over the public MQTT brokers. Run from the repo root:
//   ./gradlew :shared:compileDebugKotlinAndroid       (the shared code it uses, once after changes)
//   ./gradlew -p tools/party-sim run --args="tools/shots/party.json"
plugins {
    kotlin("jvm") version "2.1.0"
    application
}
// The shared module's compiled classes (its Android build; the party code is plain Kotlin, its
// crypto javax.crypto).
val sharedClasses = file("../../shared/build/tmp/kotlin-classes/debug")
dependencies {
    implementation(files(sharedClasses))
    // What those shared classes use (versions as in shared/build.gradle.kts).
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
    mainClass.set("PartySimKt")
}
// Relative paths in --args are resolved from the repository root.
tasks.named<JavaExec>("run") {
    workingDir = file("../..")
    standardInput = System.`in`
}
