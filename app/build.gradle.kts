plugins {
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.herdr.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.herdr.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 85
        versionName = "2.0.20"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Dev default: sandbox daemon via adb reverse (this emulator
            // image blackholes app traffic to the 10.0.2.2 SLiRP gateway;
            // localhost+reverse is the reliable dev path).
            buildConfigField("String", "DEFAULT_DIRECT_URL", "\"http://127.0.0.1:8766\"")
        }
        release {
            // One ABI per APK (download size): -Pabi=armeabi-v7a builds the
            // 32-bit artifact; the default is the arm64 one. Debug keeps all
            // four for the emulator.
            val releaseAbi = providers.gradleProperty("abi").orNull ?: "arm64-v8a"
            ndk {
                abiFilters.clear()
                abiFilters += releaseAbi
            }
            buildConfigField("String", "DEFAULT_DIRECT_URL", "\"http://10.0.2.2:8765\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release signing: keystore lives OUTSIDE the repo
            // (~/.config/herdr-mobile/release.keystore). The password comes from the
            // HERDR_RELEASE_KEYSTORE_PASSWORD env var, never from a committed file.
            // Unset password = unsigned APK (CI/verification builds).
            val homeDir = providers.systemProperty("user.home").get()
            val keystoreCandidate = rootProject.file("$homeDir/.config/herdr-mobile/release.keystore")
            val keystorePassword: String? = providers.environmentVariable("HERDR_RELEASE_KEYSTORE_PASSWORD").orNull
            if (keystoreCandidate.isFile && !keystorePassword.isNullOrBlank()) {
                signingConfig = signingConfigs.maybeCreate("herdrRelease").apply {
                    storeFile = keystoreCandidate
                    storePassword = keystorePassword
                    keyAlias = providers.environmentVariable("HERDR_RELEASE_KEY_ALIAS").orNull ?: "herdr"
                    keyPassword = keystorePassword
                }
            }
        }
        // Baseline-profile generation runs this non-debuggable, minified build.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.findByName("herdrRelease")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed native libs: the APK is a sideloaded artifact, and 5 MB
        // of uncompressed .so dominated the download size.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            // bcprov ships multi-megabyte Picnic (PQC) lookup tables as
            // resources; R8 strips the classes but not these blobs. The app
            // never selects a Picnic signature (SSH uses standard algorithms).
            "org/bouncycastle/pqc/**",
        )
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Generated startup profile (see :baselineprofile). Consumed by the
    // baselineprofile plugin; it does not ship as a runtime dependency.
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.profileinstaller)
    implementation(project(":core-model"))
    implementation(project(":core-network"))
    implementation(project(":core-designsystem"))
    implementation(project(":feature-home"))
    implementation(project(":feature-terminal"))
    implementation(project(":feature-settings"))
    implementation(project(":connection-ssh"))
    implementation(project(":connection-direct"))
    implementation(project(":notifications"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}