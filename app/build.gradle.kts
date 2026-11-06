plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "io.github.cloudburst.grayscaler"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.cloudburst.grayscaler"
        minSdk = 24
        targetSdk = 35
        // Commit count: grows with every commit, the same on the PC and in CI (full clone),
        // so an update never looks like a downgrade.
        versionCode = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
            .standardOutput.asText.get().trim().toInt()
        versionName = "2.0"
    }

    // Signed with the release key, so new builds install over old ones. Set in
    // ~/.gradle/gradle.properties on the PC and as ORG_GRADLE_PROJECT_* secrets in CI.
    // Without it, release builds fall back to the debug key.
    val releaseKeystore = providers.gradleProperty("PERSONAL_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.gradleProperty("PERSONAL_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("PERSONAL_KEYSTORE_ALIAS").get()
                keyPassword = storePassword
            }
        } else {
            logger.warn("PERSONAL_KEYSTORE_FILE not set: signing release with the debug key (won't install over the released app).")
        }
    }

    buildTypes {
        debug {
            // A separate app next to the released one (ADR 7 in android-apps), signed with the
            // same key as release builds when it's set.
            applicationIdSuffix = ".debug"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
