plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mywhisper.mobile"
    compileSdk = 36

    // Version scheme: versionCode monotonic (+1/release), versionName SemVer.
    // Source of truth: gradle.properties (mywhisper.versionCode/Name).
    defaultConfig {
        applicationId = "com.mywhisper.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("mywhisper.versionCode").getOrElse("4").toInt()
        versionName = providers.gradleProperty("mywhisper.versionName").getOrElse("0.1.0")
    }

    // Release signing (docs only, unsigned build by default):
    // Provide via env/gradle.properties, never commit keystore:
    //   signingConfigs { create("release") {
    //     storeFile = file(System.getenv("MYWHISPER_KEYSTORE_PATH") ?: "release.keystore")
    //     storePassword = System.getenv("MYWHISPER_KEYSTORE_PASSWORD")
    //     keyAlias = System.getenv("MYWHISPER_KEY_ALIAS")
    //     keyPassword = System.getenv("MYWHISPER_KEY_PASSWORD") } }
    //   buildTypes.release.signingConfig = signingConfigs.getByName("release")
    buildTypes {
        debug {
            // Emulator (x86_64) + physical arm64 debug installs.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // APK SLIM: arm64-only release. The sherpa/LLM AARs ship 4 ABIs
            // (~149MB of native .so outside arm64); Play + physical devices
            // are arm64. x86_64 stays available in debug for the emulator.
            ndk { abiFilters += "arm64-v8a" }
            // APK SLIM: R8 + shrinkResources in release (rules in
            // proguard-rules.pro: Tink -dontwarn/-keep, CoreBridge
            // native keeps, sherpa/LiteRT-LM reflection keeps).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // APK SLIM: lite = server chain only (no on-device AARs on the
    // classpath at all); full = lite + sherpa STT + LiteRT-LM packs.
    // fullImplementation keeps the ~50MB sherpa AAR and litertlm
    // (incl. its native libs) out of lite APKs. Lite
    // compiles and runs without them: LocalStt/SherpaRunner loads
    // sherpa classes by reflection (null → server fallback) and the
    // LLM side pack-gates on AAR presence (server fallback) when absent.
    buildFeatures {
        compose = true
    }
    flavorDimensions += "mode"
    productFlavors {
        create("lite") { dimension = "mode" }
        create("full") { dimension = "mode" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // FAIL-FAST (2026-10-15 incident): a full-flavor APK assembled while
    // libs/ holds no sherpa AAR builds fine — fileTree() silently matches
    // nothing — and the resulting runner-less "full" APK wrongly reports
    // NEED_FULL_FLAVOR on a full build. Refuse to even configure full APK
    // production when no AAR is present; lite (server chain only) is
    // unaffected and keeps building without it. Test/lint tasks are
    // deliberately ungated (JVM tests bridge sherpa by reflection).
    val fullApkRequested = gradle.startParameter.taskNames.any { t ->
        val l = t.lowercase()
        "full" in l && ("assemble" in l || "install" in l || "bundle" in l)
    }
    if (fullApkRequested && file("libs").listFiles { f -> f.extension == "aar" }.isNullOrEmpty()) {
        throw GradleException(
            "Full flavor requires the sherpa-onnx AAR in android/app/libs/ " +
                "(none found — fileTree() would silently drop it and produce " +
                "a runner-less 'full' APK). Run scripts/fetch-sherpa-aar.sh " +
                "from the repo root, then rebuild. Lite flavor builds without it.",
        )
    }

    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // APK SLIM: icons-core only. Verified 2026-10-15: the only
    // material icons used anywhere are filled Add/Delete/Edit/Menu/
    // Refresh (MainActivity.kt) — all in icons-core. extended pulls
    // ~1000 unused vector glyphs into dex (~25-35MB win with R8).
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Background pack downloads: WorkManager owns Doze-aware scheduling,
    // the unmetered/battery-not-low constraints, and exponential-backoff
    // retry + app-kill resume (see ModelDownloadSchedule for rationale).
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // On-device LLM packs (LiteRT-LM .litertlm, Gemma-4-first
    // lineup). fullImplementation-only: the AAR's presence gates
    // pack downloads (LocalLlmManager.isPackSupported); no inference
    // runs yet (no JVM bridge), so lite builds and JVM unit tests
    // simply pack-gate to NEED_FULL_FLAVOR / server fallback.
    add("fullImplementation", "com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    // Pin note: 0.16.1 is the newest litertlm built with Kotlin 2.2
    // (0.17+ ships Kotlin 2.4 metadata the project's 2.2.0 compiler
    // cannot read — full flavor fails to compile). Revisit together
    // with the Kotlin 2.4 plugin bump.
    // On-device STT runtime (sherpa-onnx 1.13.8, arm64-v8a + x86_64 JNI).
    // AAR fetched by scripts/fetch-sherpa-aar.sh (run once after
    // checkout, CI does it too), never committed. fullImplementation only:
    // lite has no sherpa classes on the classpath (LocalStt/SherpaRunner
    // bridges by reflection, CNFE → null → server fallback).
    add("fullImplementation", fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

// Build the Rust core and drop the .so files into jniLibs before merge.
tasks.register<Exec>("buildRustCore") {
    commandLine("bash", rootProject.file("../scripts/build-core.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn("buildRustCore") }
