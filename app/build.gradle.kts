// SPDX-License-Identifier: AGPL-3.0-only
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Version identity comes from the nearest `v*` git tag, so tags are the only
// place a release version is written down:
//   v0.3.0-beta.3          -> versionName 0.3.0-beta.3
//   v0.3.0-beta.3 + 12 commits -> versionName 0.3.0-beta.3-12-g6ac4527
// versionCode is computed from the tag's semver: major * 1_000_000 +
// minor * 10_000 + patch * 100 + stage, where stage is the pre-release number
// (beta.3 -> 3, no number -> 0) and a stable release is 99, so it sorts above
// its own pre-releases. Use one pre-release series per patch version (do not
// mix alpha/beta/rc). Without git history (a tarball, a shallow clone with no
// tags) it falls back to 0.0.0-dev / 1.
val gitDescribe: String =
    try {
        providers
            .exec {
                commandLine("git", "describe", "--tags", "--match", "v[0-9]*")
                isIgnoreExitValue = true
            }.standardOutput.asText
            .get()
            .trim()
    } catch (_: Exception) {
        ""
    }
val tagVersion =
    Regex("""^v(\d+)[.](\d+)[.](\d+)(?:-([0-9A-Za-z.]+?))?(?:-\d+-g[0-9a-f]+)?$""")
        .matchEntire(gitDescribe)
val gitVersionName = if (tagVersion != null) gitDescribe.removePrefix("v") else "0.0.0-dev"
val gitVersionCode: Int =
    tagVersion?.let { match ->
        val (majorText, minorText, patchText, preRelease) = match.destructured
        val major = majorText.toInt()
        val minor = minorText.toInt()
        val patch = patchText.toInt()
        require(minor < 100 && patch < 100) { "minor and patch must stay below 100: $gitDescribe" }
        val stage =
            when {
                preRelease.isEmpty() -> 99
                else -> Regex("""\d+$""").find(preRelease)?.value?.toInt() ?: 0
            }
        require(preRelease.isEmpty() || stage < 99) { "pre-release number must stay below 99: $gitDescribe" }
        major * 1_000_000 + minor * 10_000 + patch * 100 + stage
    } ?: 1

// The owner-held signing identity, shared by local builds and CI (see
// keystore.properties, gitignored). Without it, debug builds fall back to the
// standard debug key so contributors can still build.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

// CI debug builds (-PciDebugBuild=true, the Android CI `teslable-debug-apk`
// artifact) get their own application ID and launcher label, so a PR build
// installs next to the release app instead of clashing with its signature.
// Preview, release and demo builds never set it and keep the real ID.
val ciDebugBuild = project.findProperty("ciDebugBuild") == "true"

android {
    namespace = "com.dzid26.teslable"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dzid26.teslable"
        minSdk = 26
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = gitVersionName
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
