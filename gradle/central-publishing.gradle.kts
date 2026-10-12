import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Exec
import org.gradle.plugins.signing.Sign

// 仅根入口可上传；保留原 Publication/签名，正式 release/drop 仍须独立审批。
val library = project(":library-android")
val releaseVersion = providers.gradleProperty("VERSION").get()
val releaseGroup = providers.gradleProperty("GROUP").get()
val releaseArtifact = providers.gradleProperty("POM_ARTIFACT_ID").get()
val deploymentProject = providers.gradleProperty("POM_NAME").get()
val publicKey = providers.gradleProperty("centralPublicKeyFile")
val fingerprint = providers.environmentVariable("KTOAST_SIGNING_FINGERPRINT")
    .orElse(providers.gradleProperty("signing.gnupg.keyName"))
val bundle = layout.buildDirectory.file("central/$releaseArtifact-$releaseVersion.zip")
val bundleReport = layout.buildDirectory.file("central/$releaseArtifact-$releaseVersion.report.json")

// 插件 0.33.0 没有批次名公开 DSL；仅改 staging 标识，不改 MavenPublication 坐标。
// 依赖此内部模型的访问集中在这里，版本升级前须跑真实配置回归，字段缺失直接失败。
fun stagingProperty(name: String): Property<*> =
    library.tasks.getByName("createStagingRepository").property(name) as Property<*>
fun serviceProperty(name: String): Property<*> {
    val parameters = gradle.sharedServices.registrations.getByName("sonatype-repository-build-service").parameters
    return parameters.javaClass.getMethod("get$name").invoke(parameters) as Property<*>
}
fun verifyCentralModel() {
    val publications = library.extensions.getByType<PublishingExtension>().publications
    check(publications.size == 1 && publications.findByName("release") is MavenPublication) {
        "Central requires exactly one release MavenPublication."
    }
    val publication = publications.getByName("release") as MavenPublication
    check(releaseGroup == "io.github.logan0817" && releaseArtifact == "ktoast" && deploymentProject == "KToast" &&
        publication.groupId == releaseGroup && publication.artifactId == releaseArtifact &&
        publication.version == releaseVersion) { "Central publication coordinates changed unexpectedly." }
    check(stagingProperty("projectGroup").get() == releaseGroup &&
        stagingProperty("artifactId").get() == deploymentProject &&
        stagingProperty("version").get() == releaseVersion) { "Central deployment identity does not match KToast." }
    val host = serviceProperty("SonatypeHost").get()
    check(serviceProperty("AutomaticRelease").get() == false &&
        host == host.javaClass.getField("CENTRAL_PORTAL").get(null) &&
        serviceProperty("VersionIsSnapshot").get() == releaseVersion.endsWith("-SNAPSHOT")) {
        "Central must use CENTRAL_PORTAL with automaticRelease=false and matching version."
    }
}

val verifyCentralDeploymentName = tasks.register("verifyCentralDeploymentName") {
    group = "verification"
    description = "离线核对 Central 批次名、真实坐标和人工审核模式；不签名或上传。"
    doLast {
        verifyCentralModel()
        logger.lifecycle("Central model verified: $releaseGroup-$deploymentProject-$releaseVersion; $releaseGroup:$releaseArtifact:$releaseVersion; USER_MANAGED")
    }
}
val verifyCentralUploadEnvironment = tasks.register<Exec>("verifyCentralUploadEnvironment") {
    group = "verification"
    description = "上传前要求干净源码；不联网、不读取 Central 凭据。"
    dependsOn(verifyCentralDeploymentName)
    workingDir(rootDir)
    commandLine("python3", "-B", "tools/verify_central_staging.py", "--check-source")
    doFirst {
        check(publicKey.isPresent && file(publicKey.get()).isFile) {
            "Central requires -PcentralPublicKeyFile pointing to the release public key."
        }
        check(fingerprint.isPresent) { "Central requires the expected full signing fingerprint." }
        check(providers.gradleProperty("mavenCentralUsername").isPresent &&
            providers.gradleProperty("mavenCentralPassword").isPresent) {
            "Central requires standard mavenCentralUsername/mavenCentralPassword in the controlled Gradle environment."
        }
    }
}
val prepareCentralBundle = tasks.register<Exec>("prepareCentralBundle") {
    group = "release"
    description = "生成本地签名产物并完整验收 Central ZIP；不上传。"
    dependsOn(":library-android:publishReleasePublicationToStagingRepository")
    workingDir(rootDir)
    doFirst {
        check(publicKey.isPresent && file(publicKey.get()).isFile && fingerprint.isPresent) {
            "Bundle verification requires centralPublicKeyFile and the expected full signing fingerprint."
        }
        commandLine(
            "python3", "-B", "tools/prepare_central_bundle.py", "--version", releaseVersion,
            "--public-key", file(publicKey.get()).absolutePath, "--fingerprint", fingerprint.get(),
            "--output", bundle.get().asFile.absolutePath,
            "--javap", file("${System.getProperty("java.home")}/bin/javap").absolutePath
        )
    }
}
val verifyCentralStaging = tasks.register<Exec>("verifyCentralStaging") {
    group = "verification"
    description = "网络上传前核对插件暂存的所有文件与已验收 ZIP；任一差异终止上传。"
    dependsOn(":library-android:publishReleasePublicationToMavenCentralRepository")
    workingDir(rootDir)
    doFirst {
        verifyCentralModel()
        val repository = library.extensions.getByType<PublishingExtension>().repositories
            .getByName("mavenCentral") as MavenArtifactRepository
        check(repository.url.scheme == "file") { "Central release must use a local staging directory." }
        commandLine(
            "python3", "-B", "tools/verify_central_staging.py",
            "--normalize-signature-checksums",
            "--staging", file(repository.url).absolutePath,
            "--bundle", bundle.get().asFile.absolutePath, "--bundle-report", bundleReport.get().asFile.absolutePath,
            "--version", releaseVersion, "--public-key", file(publicKey.get()).absolutePath,
            "--fingerprint", fingerprint.get(),
            "--output", layout.buildDirectory.file("central/$releaseArtifact-$releaseVersion.staging-verification.json").get().asFile.absolutePath
        )
    }
}
tasks.register("publishToMavenCentral") {
    group = "release"
    description = "完整验收后上传 Central USER_MANAGED 批次；不 clean、不正式发布。"
    dependsOn(verifyCentralStaging)
}

gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    (stagingProperty("artifactId") as Property<String>).set(deploymentProject)
    // 环境门禁先于签名、打包和插件 create；最终验收在 build service 的网络上传前执行。
    prepareCentralBundle.configure { mustRunAfter(verifyCentralUploadEnvironment) }
    library.tasks.withType<Sign>().configureEach { mustRunAfter(verifyCentralUploadEnvironment) }
    library.tasks.named("publishReleasePublicationToStagingRepository") {
        mustRunAfter(verifyCentralUploadEnvironment)
    }
    library.tasks.named("createStagingRepository") {
        dependsOn(verifyCentralUploadEnvironment, prepareCentralBundle)
    }
}

gradle.taskGraph.whenReady {
    val centralTasks = allTasks.filter {
        it.project == library && (it.name.endsWith("ToMavenCentralRepository") ||
            it.name in setOf("createStagingRepository", "releaseRepository", "dropRepository",
                "publishToMavenCentral", "publishAndReleaseToMavenCentral"))
    }
    if (centralTasks.isNotEmpty() || hasTask(":publishToMavenCentral")) {
        check(allTasks.none { it.name in setOf("clean", "publish", "releaseRepository", "dropRepository", "publishAndReleaseToMavenCentral") }) {
            "Central upload forbids clean, generic publish, automatic release and drop tasks."
        }
        check(hasTask(":publishToMavenCentral") && hasTask(verifyCentralStaging.get()) &&
            hasTask(verifyCentralUploadEnvironment.get()) && hasTask(prepareCentralBundle.get())) {
            "Central upload must use the root :publishToMavenCentral entry with complete verification."
        }
        check(!gradle.startParameter.isConfigurationCacheRequested) { "Central requires --no-configuration-cache." }
        check(providers.gradleProperty("signPublication").orNull == "true" && !releaseVersion.endsWith("-SNAPSHOT")) {
            "Central upload requires a signed non-SNAPSHOT version."
        }
        check(!gradle.startParameter.isContinueOnFailure && gradle.startParameter.excludedTaskNames.isEmpty()) {
            "Central upload forbids --continue and excluded tasks."
        }
        check(!gradle.startParameter.isOffline || gradle.startParameter.isDryRun) {
            "Central forbids an offline upload; use --dry-run for an offline task plan."
        }
        verifyCentralModel()
    }
}

// 常规候选聚合同时覆盖发布模型，不引入签名、凭据或远程任务。
tasks.named("verifyReleaseCandidate") { dependsOn(verifyCentralDeploymentName) }
