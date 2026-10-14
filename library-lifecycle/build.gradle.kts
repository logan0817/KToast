import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
    id("com.vanniktech.maven.publish.base")
    signing
    alias(libs.plugins.dokka)
}

android {
    namespace = "com.logan.ktoast.lifecycle"
    compileSdk = libs.versions.compile.sdk.version.get().toInt()
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable.add("GradleDependency")
        disable.add("AndroidGradlePluginVersion")
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}

dependencies {
    api(project(":library-android"))
    api(libs.lifecycle.common)
    testImplementation(libs.junit)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION").get()

tasks.named<DokkaTask>("dokkaHtml") {
    moduleName.set("KToast Lifecycle")
    moduleVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("docs/api"))
    dokkaSourceSets.configureEach {
        includes.from(project.file("API.md"))
        documentedVisibilities.set(setOf(org.jetbrains.dokka.DokkaConfiguration.Visibility.PUBLIC))
        noJdkLink.set(true)
        noStdlibLink.set(true)
        noAndroidSdkLink.set(true)
    }
}

apply(from = rootProject.file("gradle/release-publishing.gradle.kts"))
mavenPublishing { publishToMavenCentral(false) }
