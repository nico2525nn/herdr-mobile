plugins {
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
        versionCode = 10
        versionName = "1.2.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
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