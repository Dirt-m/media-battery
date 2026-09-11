import org.gradle.api.tasks.PathSensitivity

// AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android plugin here;
// only the Compose compiler plugin is applied on top.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.mediabattery"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mediabattery"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // `full` is the sideload/F-Droid build (accessibility service, direct battery
    // exemption request); `play` drops both. The differences live in source sets and
    // manifests only, never in `if`s, and CI builds both.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            isDefault = true
        }
        create("play") {
            dimension = "distribution"
        }
    }
}

// The copy test reads the string resources straight off disk, so they have to be a
// declared input: otherwise editing copy leaves the task up to date and the check never
// runs on the edit that needed it.
tasks.withType<Test>().configureEach {
    inputs.dir(layout.projectDirectory.dir("src/main/res/values"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("stringResources")
}

dependencies {
    implementation(project(":core"))
    // The sync adapter's settings travel as JsonElement, so the app sees the same JSON
    // types core does. No serialization plugin: nothing here is a @Serializable class.
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    // The screens read the engine's flows through `collectAsStateWithLifecycle`, which
    // lives in runtime compose, so a screen that is not visible stops collecting.
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
}
