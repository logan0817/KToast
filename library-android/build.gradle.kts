import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.dokka.gradle.DokkaTask

plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
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
    moduleName.set("KToast")
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
val apiDocsJar = tasks.register<Jar>("apiDocsJar") {
    dependsOn(apiDocs)
    archiveClassifier.set("javadoc")
    from(apiDocs.flatMap { it.outputDirectory })
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val signPublication = providers.gradleProperty("signPublication").orNull?.let {
    require(it == "true" || it == "false") { "signPublication must be true or false." }
    it == "true"
} ?: false

if (signPublication) {
    check(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signing must run with --no-configuration-cache to keep private key configuration out of disk caches."
    }
    val key = providers.environmentVariable("KTOAST_SIGNING_KEY").orNull
    val password = providers.environmentVariable("KTOAST_SIGNING_PASSWORD").orNull
    check(!key.isNullOrBlank() && password != null) {
        "Signing requires KTOAST_SIGNING_KEY and KTOAST_SIGNING_PASSWORD environment variables."
    }
    signing {
        useInMemoryPgpKeys(key, password)
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(apiDocsJar)
                artifactId = "ktoast"
                pom {
                    name.set("KToast")
                    description.set("Android Toast library with lifecycle-aware presentation and optional Activity scopes.")
                    url.set("https://github.com/logan0817/KToast")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("logan0817")
                            name.set("Logan Gan")
                        }
                    }
                    scm {
                        url.set("https://github.com/logan0817/KToast")
                        connection.set("scm:git:https://github.com/logan0817/KToast.git")
                        developerConnection.set("scm:git:ssh://git@github.com/logan0817/KToast.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "Candidate"
                url = rootProject.layout.buildDirectory.dir("candidate-repository").get().asFile.toURI()
            }
            // 仅生成本地签名发布产物，不配置远程地址或上传凭据。
            maven {
                name = "Staging"
                url = rootProject.layout.buildDirectory.dir("staging-repository").get().asFile.toURI()
            }
        }
    }
    if (signPublication) {
        signing {
            sign(publishing.publications["release"])
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    doFirst {
        when (repository.name) {
            "Candidate" -> check(project.version.toString().endsWith("-SNAPSHOT")) {
                "Local candidate publication requires a SNAPSHOT version supplied with -PVERSION."
            }
            "Staging" -> check(signPublication && !project.version.toString().endsWith("-SNAPSHOT")) {
                "Local staging requires a non-SNAPSHOT version and -PsignPublication=true."
            }
            else -> error("Only local Candidate and Staging repositories are allowed.")
        }
    }
}
