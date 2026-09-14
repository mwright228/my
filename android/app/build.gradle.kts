plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "id.my.mub"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.my.mub"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "2.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// libmubxcore.so (the Go-based VPN/protocol engine, JNI-bridged via
// NativeCoreBridge.kt) is not built by Gradle/AGP itself — it's cross-compiled
// separately with the Android NDK + Go/CGO. Historically that step was a
// manual, undocumented prerequisite: skipping it produces an app that installs
// and looks fine but where every "Connect" attempt fails with "Native VPN core
// is unavailable", because System.loadLibrary("mubxcore") has nothing to load.
// Wiring it into preBuild means a normal `./gradlew assembleDebug` either
// produces a working native core or fails loudly with an actionable message,
// instead of silently shipping a VPN app that can't VPN.
// build-native.sh itself decides whether to skip (MUBX_SKIP_NATIVE_BUILD=1)
// or fail loudly (missing NDK/Go) — this task just makes sure it always runs.
val buildNativeCore by tasks.registering(Exec::class) {
    description = "Cross-compiles libmubxcore.so via ../build-native.sh"
    workingDir = rootDir
    commandLine("bash", "build-native.sh")
}

tasks.named("preBuild") {
    dependsOn(buildNativeCore)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
