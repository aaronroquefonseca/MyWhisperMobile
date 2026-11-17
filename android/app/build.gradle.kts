plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseNumber = providers.gradleProperty("mywhisper.versionCode").getOrElse("4").toInt()
// Commits since the last release tag; set by CI for nightly builds only.
val nightlyNumber = providers.gradleProperty("mywhisper.nightlyNumber").getOrElse("0").toInt()
require(nightlyNumber in 0..999) { "mywhisper.nightlyNumber must be 0..999, got $nightlyNumber" }

android {
    namespace = "com.mywhisper.mobile"
    compileSdk = 36

    // Version scheme: gradle.properties holds the release number
    // (mywhisper.versionCode, +1 per release) and SemVer versionName.
    // Real versionCode = release number * 1000, leaving room for nightlies
    // (see androidComponents below) between one release and the next.
    defaultConfig {
        applicationId = "com.mywhisper.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseNumber * 1000
        versionName = providers.gradleProperty("mywhisper.versionName").getOrElse("0.1.0")
        manifestPlaceholders["appLabel"] = "MyWhisper"
        // On-device AI runtime pack (scripts/build-localai.sh): downloaded on
        // demand, never bundled. CI passes the release asset; empty = no
        // download offered (a pack can still be sideloaded in debug).
        fun prop(name: String) = providers.gradleProperty(name).getOrElse("")
        buildConfigField("String", "LOCALAI_ID", "\"${prop("mywhisper.localai.id")}\"")
        buildConfigField("String", "LOCALAI_URL", "\"${prop("mywhisper.localai.url")}\"")
        buildConfigField("String", "LOCALAI_SHA256", "\"${prop("mywhisper.localai.sha256")}\"")
        buildConfigField("long", "LOCALAI_SIZE", "${prop("mywhisper.localai.size").ifBlank { "0" }}L")
    }

    // Release signing from env (CI secrets); without MYWHISPER_KEYSTORE_PATH
    // release builds stay unsigned. Never commit the keystore.
    val releaseKeystore = System.getenv("MYWHISPER_KEYSTORE_PATH")
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("MYWHISPER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MYWHISPER_KEY_ALIAS")
            keyPassword = System.getenv("MYWHISPER_KEY_PASSWORD")
        }
    }
    buildTypes {
        debug {
            // Emulator (x86_64) + physical arm64 debug installs.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            // Side-by-side test installs: -Pmywhisper.appIdSuffix=.test keeps
            // the user's installed MyWhisper untouched.
            providers.gradleProperty("mywhisper.appIdSuffix").orNull?.let { applicationIdSuffix = it }
        }
        release {
            // arm64-only release (the on-device AI pack is arm64-only too);
            // x86_64 stays available in debug for the emulator.
            ndk { abiFilters += "arm64-v8a" }
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // R8 + shrinkResources in release (rules in proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Built from every push to main: release config, own label and icon
        // (src/nightly), versionCode between this release and the next.
        create("nightly") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Same applicationId for stable/nightly so users can switch without losing
// settings; the label tells them apart.
androidComponents {
    onVariants { variant ->
        val nightly = variant.buildType == "nightly"
        val label = buildString {
            append("MyWhisper")
            if (nightly) append(" Nightly")
            // Side-by-side test install (see debug.applicationIdSuffix) needs a distinct icon label.
            if (variant.buildType == "debug" && providers.gradleProperty("mywhisper.appIdSuffix").isPresent) append(" Test")
        }
        variant.manifestPlaceholders.put("appLabel", label)
        if (nightly) {
            val baseName = providers.gradleProperty("mywhisper.versionName").getOrElse("0.1.0")
            variant.outputs.forEach { output ->
                output.versionCode.set(releaseNumber * 1000 + nightlyNumber)
                output.versionName.set("$baseName-nightly.$nightlyNumber")
            }
        }
    }
}

dependencies {
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Build the Rust core and drop the .so files into jniLibs before merge.
tasks.register<Exec>("buildRustCore") {
    commandLine("bash", rootProject.file("../scripts/build-core.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn("buildRustCore") }
