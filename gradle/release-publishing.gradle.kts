import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension

// 由 library-android 在插件与发布变体配置完成后应用；不配置远程仓库或上传凭据。
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
            val fingerprint = providers.environmentVariable("KTOAST_SIGNING_FINGERPRINT").orNull
            check(fingerprint != null && Regex("(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})").matches(fingerprint)) {
                "GPG signing requires a full KTOAST_SIGNING_FINGERPRINT."
            }
            val configuredKey = providers.gradleProperty("signing.gnupg.keyName").orNull
            check(configuredKey == null || configuredKey.equals(fingerprint, ignoreCase = true)) {
                "signing.gnupg.keyName conflicts with KTOAST_SIGNING_FINGERPRINT."
            }
            check(!providers.gradleProperty("signing.gnupg.passphrase").isPresent &&
                providers.gradleProperty("signing.gnupg.useLegacyGpg").orNull.let { it == null || it == "false" }) {
                "GPG signing requires GnuPG 2 and gpg-agent; do not configure signing.gnupg.passphrase or legacy GPG."
            }
            // 显式选择已核对的完整指纹，避免共享配置或 GPG 默认密钥误签；口令只交给 agent。
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
                artifactId = providers.gradleProperty("POM_ARTIFACT_ID").get()
                pom {
                    name.set(providers.gradleProperty("POM_NAME"))
                    description.set(providers.gradleProperty("POM_DESCRIPTION"))
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
            else -> error("Only local Candidate and Staging repositories are allowed.")
        }
    }
}
