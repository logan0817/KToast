import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension

// 核心与可选扩展共用 Publication/签名规则；Central 入口由根脚本保护。
val apiDocsJar = tasks.register<Jar>("apiDocsJar") {
    dependsOn("dokkaHtml")
    archiveClassifier.set("javadoc")
    from(layout.buildDirectory.dir("docs/api"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val signPublication = providers.gradleProperty("signPublication").orNull?.let {
    require(it == "true" || it == "false") { "signPublication must be true or false." }
    it == "true"
} ?: false
val signingMode = providers.gradleProperty("signingMode").getOrElse("memory")
require(signingMode == "memory" || signingMode == "gpg") {
    "signingMode must be memory or gpg."
}

if (signPublication) {
    check(!gradle.startParameter.isConfigurationCacheRequested) {
        "Signing must run with --no-configuration-cache to keep private key configuration out of disk caches."
    }
    when (signingMode) {
        "memory" -> {
            val key = providers.environmentVariable("KTOAST_SIGNING_KEY").orNull
            val password = providers.environmentVariable("KTOAST_SIGNING_PASSWORD").orNull
            check(!key.isNullOrBlank() && password != null) {
                "Signing requires KTOAST_SIGNING_KEY and KTOAST_SIGNING_PASSWORD environment variables."
            }
            configure<SigningExtension> {
                useInMemoryPgpKeys(key, password)
            }
        }
        "gpg" -> {
            val configuredKey = providers.gradleProperty("signing.gnupg.keyName").orNull
                ?.takeIf { it.isNotBlank() }
            val environmentKey = providers.environmentVariable("KTOAST_SIGNING_FINGERPRINT").orNull
            val fullFingerprint = Regex("(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})")
            check((environmentKey == null || fullFingerprint.matches(environmentKey)) &&
                (configuredKey == null || fullFingerprint.matches(configuredKey))) {
                "GPG signing requires a full fingerprint in signing.gnupg.keyName or KTOAST_SIGNING_FINGERPRINT."
            }
            check(environmentKey == null || configuredKey == null ||
                configuredKey.equals(environmentKey, ignoreCase = true)) {
                "signing.gnupg.keyName conflicts with KTOAST_SIGNING_FINGERPRINT."
            }
            val fingerprint = environmentKey ?: configuredKey
            check(fingerprint != null) {
                "GPG signing requires a full fingerprint in signing.gnupg.keyName or KTOAST_SIGNING_FINGERPRINT."
            }
            check(!providers.gradleProperty("signing.gnupg.passphrase").isPresent &&
                providers.gradleProperty("signing.gnupg.useLegacyGpg").orNull.let { it == null || it == "false" }) {
                "GPG signing requires GnuPG 2 and gpg-agent; do not configure signing.gnupg.passphrase or legacy GPG."
            }
            // 复用标准 Gradle 本机配置，环境变量可选；仍明确选钥，避免 GPG 默认密钥误签。
            extensions.extraProperties["signing.gnupg.keyName"] = fingerprint.uppercase()
            configure<SigningExtension> {
                useGpgCmd()
            }
        }
    }
}

afterEvaluate {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifact(apiDocsJar)
                artifactId = if (project.name == "library-lifecycle") "ktoast-lifecycle"
                    else providers.gradleProperty("POM_ARTIFACT_ID").get()
                pom {
                    name.set(if (project.name == "library-lifecycle") "KToast Lifecycle"
                        else providers.gradleProperty("POM_NAME").get())
                    description.set(if (project.name == "library-lifecycle") "Optional LifecycleOwner scopes for KToast."
                        else providers.gradleProperty("POM_DESCRIPTION").get())
                    url.set(providers.gradleProperty("POM_URL"))
                    licenses {
                        license {
                            name.set(providers.gradleProperty("POM_LICENSE_NAME"))
                            url.set(providers.gradleProperty("POM_LICENSE_URL"))
                        }
                    }
                    developers {
                        developer {
                            id.set(providers.gradleProperty("POM_DEVELOPER_ID"))
                            name.set(providers.gradleProperty("POM_DEVELOPER_NAME"))
                        }
                    }
                    scm {
                        url.set(providers.gradleProperty("POM_SCM_URL"))
                        connection.set(providers.gradleProperty("POM_SCM_CONNECTION"))
                        developerConnection.set(providers.gradleProperty("POM_SCM_DEV_CONNECTION"))
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
        configure<SigningExtension> {
            sign(extensions.getByType<PublishingExtension>().publications["release"])
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
            "mavenCentral" -> check(signPublication && !project.version.toString().endsWith("-SNAPSHOT")) {
                "Central upload requires a signed non-SNAPSHOT version."
            }
            else -> error("Only Candidate, Staging and the guarded Central repository are allowed.")
        }
    }
}
