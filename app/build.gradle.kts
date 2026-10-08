// SPDX-License-Identifier: AGPL-3.0-only
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// The owner-held signing identity, shared by local builds and CI (see
// keystore.properties, gitignored). Without it, debug builds fall back to the
// standard debug key so contributors can still build.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

// CI debug builds (-PciDebugBuild=true, the Android CI `teslable-debug-apk`
// artifact) get their own application ID, launcher label and icon background
// (src/pr/res), so a PR build installs next to the release app instead of
// clashing with its signature.
// Preview, release and demo builds never set it and keep the real ID.
val ciDebugBuild = project.findProperty("ciDebugBuild") == "true"

android {
    namespace = "com.dzid26.teslable"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dzid26.teslable"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0-beta.2"
        buildConfigField(
            "boolean",
            "DEMO_CAR",
            (project.findProperty("demoCar") as String?) ?: "false",
        )
        // android:label in the manifest; CI debug builds override it below.
        manifestPlaceholders["appLabel"] = "@string/app_name"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    sourceSets {
        if (ciDebugBuild) getByName("debug").res.srcDir("src/pr/res")
    }

    buildTypes {
        debug {
            // Every build shares the stable signing identity, so preview,
            // release and local builds update each other in place.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            if (ciDebugBuild) {
                applicationIdSuffix = ".pr"
                versionNameSuffix = "-pr"
                manifestPlaceholders["appLabel"] = "TeslaBatteryBLE PR"
            }
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)
    testImplementation(libs.junit)
}

// Generated code (BuildConfig, etc.) follows its own conventions. The task
// sources are narrowed by absolute path: the plugin matches string patterns
// against paths relative to each source root, where "build/generated"
// never appears, so a filter exclude cannot match it.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    // Snapshot to a plain list: deriving from `source` lazily would recurse.
    val lintSources = source.files.filter { !it.invariantSeparatorsPath.contains("/build/generated/") }
    setSource(lintSources)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
