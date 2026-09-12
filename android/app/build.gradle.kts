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
        versionCode = 2
        versionName = "2.5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "x86_64"))
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = providers.environmentVariable("MUBX_KEYSTORE_PATH")
            val storePassword = providers.environmentVariable("MUBX_KEYSTORE_PASSWORD")
            val keyAlias = providers.environmentVariable("MUBX_KEY_ALIAS")
            val keyPassword = providers.environmentVariable("MUBX_KEY_PASSWORD")
            if (keystorePath.isPresent && storePassword.isPresent && keyAlias.isPresent && keyPassword.isPresent) {
                storeFile = file(keystorePath.get())
                this.storePassword = storePassword.get()
                this.keyAlias = keyAlias.get()
                this.keyPassword = keyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val signingReady = listOf(
                "MUBX_KEYSTORE_PATH", "MUBX_KEYSTORE_PASSWORD", "MUBX_KEY_ALIAS", "MUBX_KEY_PASSWORD"
            ).all { providers.environmentVariable(it).isPresent }
            if (signingReady) signingConfig = signingConfigs.getByName("release")
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
