plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Play Games / AdMob / Billing configuration. Put real values in ~/.gradle/gradle.properties or
 * the project's gradle.properties (see README). The defaults are safe for development:
 * Google's official AdMob *test* IDs, and Play Games switched off until a project ID is set.
 */
fun prop(name: String, default: String) = (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default
val playGamesProjectId = prop("playGamesProjectId", "")
val admobAppId = prop("admobAppId", "ca-app-pub-3940256099942544~3347511713")            // default: test app
val admobInterstitialId = prop("admobInterstitialId", "ca-app-pub-3940256099942544/1033173712") // default: test unit
val removeAdsProductId = prop("removeAdsProductId", "remove_ads")
// Google's official AdMob test IDs: debug builds always use these, so you never click real ads.
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testAdmobInterstitialId = "ca-app-pub-3940256099942544/1033173712"
// Optional release signing (Play needs a signed upload): set these four properties.
val releaseStoreFile = prop("releaseStoreFile", "")

android {
    namespace = "app.satisfyingsand"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.satisfyingsand"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0"

        manifestPlaceholders["admobAppId"] = admobAppId
        resValue("string", "game_services_project_id", playGamesProjectId.ifBlank { "0" })
        buildConfigField("boolean", "PLAY_GAMES_ENABLED", playGamesProjectId.isNotBlank().toString())
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        buildConfigField("String", "REMOVE_ADS_PRODUCT_ID", "\"$removeAdsProductId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseStoreFile.isNotBlank()) create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = prop("releaseStorePassword", "")
            keyAlias = prop("releaseKeyAlias", "")
            keyPassword = prop("releaseKeyPassword", "")
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testAdmobInterstitialId\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Optional: point Robolectric at a pre-downloaded android-all jar (offline runs).
            System.getenv("ROBOLECTRIC_DEPS_DIR")?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Back handling that works the same on every Android version (predictive back on 13+).
    implementation("androidx.activity:activity:1.12.4")
    implementation("com.google.android.gms:play-services-games-v2:22.1.0")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
}
