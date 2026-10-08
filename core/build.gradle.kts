// SPDX-License-Identifier: AGPL-3.0-only
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wire)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.wire.runtime)
    testImplementation(libs.junit)
    // New tests use kotlin.test so they can move to commonTest (ADR-0007).
    testImplementation(kotlin("test"))
}

wire {
    // Tesla's protos, vendored verbatim and pinned by TESLA_COMMIT.
    sourcePath {
        srcDir("src/main/proto")
    }
    // The app's own records (ADR-0008) live in a second root, so a re-vendor
    // of Tesla's protos never touches them.
    sourcePath {
        srcDir("src/main/proto-teslable")
    }
    kotlin {
    }
}

// GoVectorTest reads the vectors that the go-vectors CI job regenerates from
// vehicle-command and diffs, so the Kotlin test can't drift from them.
tasks.processTestResources {
    from(rootProject.file("tools/go-fixtures/expected.txt")) {
        into("go-fixtures")
    }
}

// Wire-generated protos follow their own conventions. The task sources are
// narrowed by absolute path: the plugin matches string patterns against
// paths relative to each source root, where "build/generated" never
// appears, so a filter exclude cannot match it.
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>().configureEach {
    // Snapshot to a plain list: deriving from `source` lazily would recurse.
    val lintSources = source.files.filter { !it.invariantSeparatorsPath.contains("/build/generated/") }
    setSource(lintSources)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}
