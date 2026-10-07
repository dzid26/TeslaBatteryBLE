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
}

wire {
    sourcePath {
        srcDir("src/main/proto")
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
