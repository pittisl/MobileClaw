import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Runtime profiles are the default. A developer may explicitly seed a debug-only
// installation with LLM_DEV_DEFAULTS=true in gitignored local.properties.
// Release and ordinary debug APKs contain neither keys nor private endpoint values.
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use(props::load)
}
val publicBuild = providers.gradleProperty("mobileclaw.publicBuild").orNull.equals("true", ignoreCase = true)
val devLlmDefaults = !publicBuild && localProps.getProperty("LLM_DEV_DEFAULTS", "false").equals("true", ignoreCase = true)

// BuildConfig values are Java literals, not source fragments supplied by local files.
fun buildConfigLiteral(value: String): String = buildString {
    append('"')
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (c.code < 32 || c.code == 127) {
                append('\\').append(c.code.toString(8).padStart(3, '0'))
            } else {
                append(c)
            }
        }
    }
    append('"')
}

android {
    namespace = "edu.pitt.isl.mobileclaw.notiftest"
    // AppFunctions requires compileSdk 36. targetSdk remains 34 so this compiler
    // requirement does not opt the app into newer target-SDK runtime behavior.
    compileSdk = 36

    defaultConfig {
        applicationId = "edu.pitt.isl.mobileclaw.notiftest"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // Required for anything under src/androidTest to run at all — without it
        // connectedDebugAndroidTest has no runner and silently does nothing.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships four ABIs at ~30 MB each; the phones this runs on (OnePlus,
        // Pixel) and the emulators (`mobileclaw-34`/`-36`) are these two. 32-bit ARM and
        // x86 would each add a copy of libonnxruntime for a device nobody has.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Safe defaults are inherited by every build type, including release.
        buildConfigField("boolean", "DEV_LLM_DEFAULTS", "false")
        buildConfigField("String", "LLM_API_KEY", buildConfigLiteral(""))
        buildConfigField("String", "LLM_ENDPOINT", buildConfigLiteral(""))
        buildConfigField("String", "LLM_MODEL", buildConfigLiteral(""))
        buildConfigField("String", "NOTION_API_KEY", buildConfigLiteral(""))

    }

    buildFeatures {
        buildConfig = true
        // AGP 8 defaults AIDL off. The privileged user service is reached
        // over Binder, so its interface has to be generated from .aidl.
        aidl = true
    }

    // Room writes the generated schema JSON here so migrations can be diffed against
    // a committed baseline rather than reconstructed from memory.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        // Without this the AppFunctions compiler emits only the per-class inventory; the
        // aggregation pass is what writes assets/app_functions.xml, which is what the
        // platform indexes. First install on the Pixel 7 without it: "indexed: []".
        arg("appfunctions:aggregateAppFunctions", "true")
    }

    // MigrationTestHelper reads the exported schema JSON at runtime, so it has to be
    // packaged into the test APK's assets.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    // Exercise the exact booth PDFs/images in app-owned test storage, without all-files access.
    sourceSets.getByName("androidTest").assets.srcDir("$rootDir/tools/demo_preload/generated")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // `kotlinOptions` is deprecated on Kotlin 2.x; the replacement block is below `android`.

    buildTypes {
        debug {
            if (devLlmDefaults) {
                buildConfigField("boolean", "DEV_LLM_DEFAULTS", "true")
                buildConfigField("String", "LLM_API_KEY", buildConfigLiteral(localProps.getProperty("LLM_API_KEY", "")))
                buildConfigField("String", "LLM_ENDPOINT", buildConfigLiteral(localProps.getProperty("LLM_ENDPOINT", "https://api.openai.com/v1/chat/completions")))
                buildConfigField("String", "LLM_MODEL", buildConfigLiteral(localProps.getProperty("LLM_MODEL", "gpt-4o")))
                buildConfigField("String", "NOTION_API_KEY", buildConfigLiteral(localProps.getProperty("NOTION_API_KEY", "")))
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        // Lets JVM unit tests drive OpenAiAiClient, whose android.util.Log calls would
        // otherwise throw on JVM stubs. The agent-loop tests use MockWebServer and real
        // JVM implementations of OkHttp and org.json.
        //
        // Framework stubs return 0/false/null under this option, so tests can silently
        // rely on stub values. SQLite, Context and other live framework behavior must
        // be tested in src/androidTest.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    // Read capture-time offsets consistently on API 26+, including tags absent from
    // older platform ExifInterface implementations.
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    // The scheduled daily memory pass. WorkManager is used instead of
    // running it opportunistically on first app open, which needs no dependency but does
    // not fire on a phone that was off and drifts a little further every day.
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // On-device full-text index for the file-search skill. `LocalStorage` is
    // the in-process backend (Icing, bundled): it works from API 21, needs no Play
    // Services and no platform indexer, and the index lives in app-private storage.
    // Documents are built as GenericDocument by hand, so the annotation processor — a
    // second KSP/kapt processor alongside Room's — is deliberately not pulled in.
    // beta01 is the newest release whose AAR metadata asks for compileSdk 35 and any AGP;
    // rc01 and 1.1.0 require compileSdk 36 + AGP 8.9.1.
    // Same API surface for everything used here (setSchemaAsync/putAsync/search/removeAsync).
    implementation("androidx.appsearch:appsearch:1.1.0-beta01")
    implementation("androidx.appsearch:appsearch-local-storage:1.1.0-beta01")
    // ML Kit image labelling, the *bundled* flavour, for the "what is in this photo"
    // field on the file index. Bundled rather than the Play-Services-delivered model
    // because the demo phones must not depend on a download at the booth, and because
    // the emulator image has no model store to download into. The cost is ~10 MB of APK.
    // Pixels never leave the device; note that the SDK itself carries Firebase transport
    // for usage telemetry (see ImageLabels), which is the one outbound thing it adds.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // ML Kit text recognition (Latin script), bundled for the same two reasons, for the
    // index's OCR field: text read off photos, screenshots and PDF pages, so a boarding
    // pass is found — and quoted — by what is printed on it. Same telemetry caveat.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // ONNX Runtime for MobileCLIP image/text embeddings (the search skill's
    // similarity route). The weights are not in the repository — tools/fetch_clip_model.sh
    // puts them in app/src/main/assets/clip/, gitignored — and ClipModel degrades to
    // "not available" when they are absent, so a build without them still runs.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Android AppFunctions: one @AppFunction `ping`, self-called, to exercise the
    // platform's own function bus on API 36 with no privilege. The compiler is a second KSP
    // processor alongside Room's.
    implementation(libs.androidx.appfunctions)
    // `appfunctions-service` holds the @AppFunction annotation and the platform-facing
    // service; the main artifact lists it as a runtime dependency only, so it has to be on
    // the compile classpath by name.
    implementation("androidx.appfunctions:appfunctions-service:1.0.0-alpha08")
    ksp(libs.androidx.appfunctions.compiler)
    // AppSearch and AppFunctions return Guava ListenableFutures; with AppSearch 1.1.0 the
    // class comes only through `listenablefuture` shim resolution and the compiler could not
    // see it, so the real Guava is named here (the -android flavour the libraries build with).
    implementation("com.google.guava:guava:32.0.1-android")
    // Privileged shell backend. `api` is the client; `provider` contributes
    // the ShizukuProvider and the moe.shizuku.manager.permission.API_V23 uses-permission
    // via manifest merge, so neither is declared by hand. Both are inert when Shizuku
    // isn't installed — the app degrades to "no privileged backend", never to broken.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation(libs.junit)
    // Android's org.json is a throwing stub in JVM unit tests; pull a real impl so
    // the SSE-parsing tests can exercise JSONObject.
    testImplementation("org.json:json:20231013")
    // Serves canned SSE transcripts to the real OpenAiAiClient, so the agent loop is
    // exercised over a real socket without a network or an API key.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(libs.androidx.room.testing)
    // Room's suspend DAO functions need a coroutine builder in tests.
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
// Kotlin's JVM target, kept equal to `compileOptions` above. `android.kotlinOptions` is
// deprecated on Kotlin 2.x; without this block Kotlin follows the daemon's JDK (21+) and
// the build fails on the Java/Kotlin target mismatch check.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
