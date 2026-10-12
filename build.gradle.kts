plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    kotlin("android") apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
    base
}

apply(from = "gradle/release-verification.gradle.kts")
apply(from = "gradle/central-publishing.gradle.kts")
