import org.gradle.api.tasks.Exec

// 只组织本地验证；真实检查继续由现有工具负责，不复制兼容基线或调用嵌套 Gradle。
val verifyPublicationTools = tasks.register<Exec>("verifyPublicationTools") {
    group = "verification"
    description = "运行候选、发布包、远端离线验收及矩阵工具的回归测试。"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "tools/tests", "-v")
}

val verifyCandidateArtifacts = tasks.register<Exec>("verifyCandidateArtifacts") {
    group = "verification"
    description = "生成并校验本地 SNAPSHOT 候选；须使用 -PVERSION 指定候选版本，不签名或上传。"
    dependsOn(":library-android:publishReleasePublicationToCandidateRepository")
    workingDir(rootProject.projectDir)
    val version = providers.gradleProperty("VERSION").get()
    commandLine(
        "python3", "-B", "tools/verify_candidate.py",
        "--javap", file("${System.getProperty("java.home")}/bin/javap").absolutePath,
        "--javadoc", layout.projectDirectory.file("library-android/build/libs/library-android-$version-javadoc.jar").asFile.absolutePath
    )
}

tasks.register("verifyReleaseCandidate") {
    group = "verification"
    description = "运行本地候选构建、JVM 测试、库 Lint 与产物/工具校验；消费者和设备须单独验收。"
    dependsOn(
        verifyPublicationTools,
        verifyCandidateArtifacts,
        ":library-android:testDebugUnitTest",
        ":library-android:lintDebug"
    )
}
