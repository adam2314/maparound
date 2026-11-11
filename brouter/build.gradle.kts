// BRouter routing engine (https://github.com/abrensch/brouter), v1.7.10, MIT licence (see LICENSE).
// Unmodified sources of brouter-util, -codec, -expressions, -mapaccess and -core.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:none")
    options.isWarnings = false
}
