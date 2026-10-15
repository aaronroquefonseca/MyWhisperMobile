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
        release {
            // R8/minify + shrinkResources CHECKED 2026-10-14: enabling R8 with
            // default rules fails on Tink (security-crypto) missing classes
            // (errorprone annotations, javax.annotation). Keep disabled until
            // proguard-rules.pro adds -dontwarn/-keep for Tink. Then set:
            // isMinifyEnabled=true, isShrinkResources=true,
            // proguardFiles(getDefaultProguardFile(...), "proguard-rules.pro").
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    buildFeatures {
        compose = true
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
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // On-device LLM cleanup (MediaPipe LLM Inference .task, GPU delegate;
    // successor API: LiteRT-LM). Loaded lazily via reflection in
    // LocalLlmManager so JVM unit tests run without the native runner.
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

// Build the Rust core and drop the .so files into jniLibs before merge.
tasks.register<Exec>("buildRustCore") {
    commandLine("bash", rootProject.file("../scripts/build-core.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn("buildRustCore") }
