plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// TaskBoard: the AppFunctions callee for the demo. A deliberately small,
// separate app — its own package, its own process, its own APK — that exposes a handful
// of functions with visible effects (a task list, a theme colour, a notification) so
// MobileClaw can be shown driving *another* app through Android 16's function bus,
// which a self-call cannot show. Same toolchain as the main module on purpose: the
// AppFunctions compiler is version-locked to KSP2, and the legacy metadata file the
// Android 16 build indexes from is only emitted with the aggregation flag below.
android {
    namespace = "edu.pitt.isl.mobileclaw.taskboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.pitt.isl.mobileclaw.taskboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    ksp {
        // Same trap as the main module: without this the compiler emits only the per-class
        // inventory, and the platform indexes nothing ("App function not found").
        arg("appfunctions:aggregateAppFunctions", "true")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // The provider side of AppFunctions: the annotation, the compiler, and the service
    // the library registers in the manifest for us. Pinned with the main module.
    implementation(libs.androidx.appfunctions)
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    ksp(libs.androidx.appfunctions.compiler)
    implementation("com.google.guava:guava:32.0.1-android")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core:1.5.0")
}
