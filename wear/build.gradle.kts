import java.util.Properties

// MapAround's Wear OS app (docs/WATCH_PLAN.md, phase 2): shows the phone's navigation and recording
// and controls them, over Google's watch link (the Wearable Data Layer).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The same Google Play upload key as the phone app's (see app/build.gradle.kts).
val uploadKey: Properties? = (
    System.getenv("MAPAROUND_KEYSTORE")?.let(::File)
        ?: File(System.getProperty("user.home"), ".mapAround-keys/keystore.properties")
    ).takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "app.maparound.wear"
    compileSdk = 36

    defaultConfig {
        // The phone app's id: Google's watch link connects the two apps by it.
        applicationId = "app.maparound"
        // Wear OS 3 and later.
        minSdk = 30
        targetSdk = 34
        // Its own numbers, beside the phone app's in the same Play listing (phone 10 → watch 100010).
        versionCode = 100010
        versionName = "0.5.4"
    }

    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                storeFile = file(uploadKey.getProperty("storeFile"))
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("upload")
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // The phone app's shared code: the watch link's protocol, texts, the turn arrows.
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.9.3")
    // Compose for Wear OS (built on the same Compose 1.7 as the phone app's).
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material:material-icons-extended")
    // The tile, and the Ongoing Activity on the watch face.
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:listenablefuture:1.0")
    // Where you are, for the route view (the watch's GPS, or the phone's through it).
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Google's watch link, with coroutines.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
