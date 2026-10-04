plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.wire)
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
