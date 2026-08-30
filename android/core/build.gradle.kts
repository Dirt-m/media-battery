// Pure JVM module: battery math, rules, projection, sync protocol and friction pool, with
// no Android dependencies, so the ported node suites run under `gradle :core:test` with no
// SDK and no emulator.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // The state file and the sync wire format are both JSON.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // api, not implementation: SyncEngine's public surface hands out a StateFlow and takes
    // a CoroutineScope.
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform {
        // The live server suite needs a go toolchain and a listening port, so it is opt in:
        // `gradle :core:test -Dintegration=true`, or run the tagged class directly.
        if (System.getProperty("integration") == null) excludeTags("integration")
    }
}
