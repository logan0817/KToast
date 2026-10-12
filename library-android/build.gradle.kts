import org.gradle.kotlin.dsl.withType
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
    compileSdk = libs.versions.compile.sdk.version.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.version.get().toInt()
        namespace = "com.logan.ktoast"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }


    lint {
        warningsAsErrors = true
        abortOnError = true
        disable.add("GradleDependency")
        disable.add("AndroidGradlePluginVersion")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.startup)
    testImplementation(libs.junit)
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION").get()

// 文档插件仅用于构建，不改变消费者的 Kotlin/Android 依赖要求。
val apiDocs = tasks.named<DokkaTask>("dokkaHtml") {
    moduleName.set(providers.gradleProperty("POM_NAME"))
    moduleVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("docs/api"))
    dokkaSourceSets.configureEach {
        includes.from(rootProject.file("docs/API.md"))
        documentedVisibilities.set(setOf(org.jetbrains.dokka.DokkaConfiguration.Visibility.PUBLIC))
        // 发布包离线可读，避免生成文档时请求远程 JDK、Kotlin 或 Android 索引。
        noJdkLink.set(true)
        noStdlibLink.set(true)
        noAndroidSdkLink.set(true)
    }
}
apply(from = rootProject.file("gradle/release-publishing.gradle.kts"))

// base 插件只提供 Central 上传，保留现有 Publication、Dokka 和双模式签名。
mavenPublishing {
    publishToMavenCentral(false)
}
