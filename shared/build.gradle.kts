import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    // Shared Compose screens (Compose Multiplatform; Jetpack Compose on Android)
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // iOS: devices and the simulator on Apple-silicon Macs (built and tested in CI on macOS).
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "MapAroundShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // In the shared API (file handles, sources), so the Android app gets it too.
            api("com.squareup.okio:okio:3.9.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidUnitTest.dependencies {
            implementation("junit:junit:4.13.2")
            // The original mapsforge reader, to check ours against it.
            implementation("org.mapsforge:mapsforge-map-reader:0.25.0")
            implementation("org.mapsforge:mapsforge-map:0.25.0")
            implementation("org.mapsforge:mapsforge-core:0.25.0")
        }
    }
}

android {
    namespace = "app.maparound.shared"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
