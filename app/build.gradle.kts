plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.maparound"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.maparound"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val mapsforgeVersion = "0.25.0"

dependencies {
    // Jetpack / Compose
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Mapsforge: offline OSM vector maps (.map) + online tile layers
    implementation("org.mapsforge:mapsforge-core:$mapsforgeVersion")
    implementation("org.mapsforge:mapsforge-map:$mapsforgeVersion")
    implementation("org.mapsforge:mapsforge-map-reader:$mapsforgeVersion")
    implementation("org.mapsforge:mapsforge-themes:$mapsforgeVersion")
    implementation("org.mapsforge:mapsforge-map-android:$mapsforgeVersion")
    implementation("com.caverock:androidsvg:1.4")

    // Offline routing engine (vendored BRouter, MIT)
    implementation(project(":brouter"))

    // Party location sharing: MQTT (EPL-2.0 / EDL-1.0) and QR codes (Apache-2.0)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Android Auto: Android for Cars App Library (navigation category, map drawn on the car surface)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")

    testImplementation("junit:junit:4.13.2")
}
