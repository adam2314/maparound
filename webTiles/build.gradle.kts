import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// The web app's tile worker: the shared tile code (the offline map's vector tiles from the
// downloaded maps and the built-in world) in a Web Worker, reading the maps from the browser's file
// storage (OPFS). webApp copies its build to tiles/. See tiles-worker.js.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "maptiles"
        browser {
            commonWebpackConfig {
                outputFileName = "maptiles.js"
            }
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation("com.squareup.okio:okio:3.9.1")
        }
    }
}
