import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.net.URI

// MapAround in the browser: the shared Compose screens and map code on Kotlin/Wasm, the map drawn
// by MapLibre GL JS. Installable (a PWA) and usable offline. Build: ./gradlew :webApp:wasmJsBrowserDistribution
// (webApp/build/dist/wasmJs/productionExecutable); try it: :webApp:wasmJsBrowserDevelopmentRun.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** The MapLibre GL JS release the web app ships (BSD-3-Clause), served with the app so it works offline. */
val maplibreVersion = "5.24.0"

/** The style's SVG symbols come from mapsforge's themes (LGPL-3.0), as on Android. */
val mapsforgeThemes: Configuration by configurations.creating

dependencies {
    mapsforgeThemes("org.mapsforge:mapsforge-themes:0.25.0") { isTransitive = false }
}

val assetsDir = layout.buildDirectory.dir("generated/webAssets")

/** MapLibre GL JS from the npm registry's CDN, downloaded once. */
val maplibreFiles = layout.buildDirectory.dir("maplibre/$maplibreVersion").also { dir ->
    tasks.register("downloadMapLibre") {
        val out = dir.get().asFile
        outputs.dir(out)
        doLast {
            out.mkdirs()
            for (name in listOf("maplibre-gl.js", "maplibre-gl.css")) {
                val f = out.resolve(name)
                if (!f.exists()) URI("https://unpkg.com/maplibre-gl@$maplibreVersion/dist/$name").toURL().openStream().use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}

/**
 * Fonts for the app's own text (Compose draws it, not the browser): symbols and arrows, Hebrew and
 * Arabic, as fallbacks to its built-in Latin font. Noto (SIL Open Font License), from Google Fonts.
 */
val uiFonts = mapOf(
    "NotoSansSymbols2.ttf" to "ofl/notosanssymbols2/NotoSansSymbols2-Regular.ttf",
    "NotoSansMath.ttf" to "ofl/notosansmath/NotoSansMath-Regular.ttf",
    "NotoSansHebrew.ttf" to "ofl/notosanshebrew/NotoSansHebrew%5Bwdth%2Cwght%5D.ttf",
    "NotoSansArabic.ttf" to "ofl/notosansarabic/NotoSansArabic%5Bwdth%2Cwght%5D.ttf",
)
val uiFontFiles = layout.buildDirectory.dir("uifonts").also { dir ->
    tasks.register("downloadUiFonts") {
        val out = dir.get().asFile
        outputs.dir(out)
        doLast {
            out.mkdirs()
            for ((name, path) in uiFonts) {
                val f = out.resolve(name)
                if (!f.exists()) URI("https://raw.githubusercontent.com/google/fonts/main/$path").toURL().openStream().use { input ->
                    f.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}

/** What the app loads besides its code: the Topo style, label fonts, the world map, symbols, MapLibre. */
val webAssets by tasks.registering(Sync::class) {
    into(assetsDir)
    from("../app/src/main/assets") {
        include("themes/topo.xml", "fonts/**", "world.bin", "borders.bin")
    }
    // The symbols topo.xml uses (jar:symbols/…), at mapsforge/symbols/….
    from({ zipTree(mapsforgeThemes.singleFile) }) {
        include("assets/symbols/**", "assets/patterns/**")
        eachFile { path = "mapsforge/" + path.removePrefix("assets/") }
        includeEmptyDirs = false
    }
    from("../docs/assets") { include("icon-192.png") }
    from("../docs/store") { include("icon-512.png") }
    from(maplibreFiles)
    from(uiFontFiles) { into("uifonts") }
    dependsOn("downloadMapLibre", "downloadUiFonts")
}


kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "maparound"
        browser {
            commonWebpackConfig {
                outputFileName = "maparound.js"
            }
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain {
            resources.srcDir(assetsDir)
            dependencies {
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
        }
    }
}

tasks.matching { it.name == "wasmJsProcessResources" }.configureEach { dependsOn(webAssets) }

// The system's Node.js (the build doesn't download one).
rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
}
