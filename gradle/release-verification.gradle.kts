import org.gradle.api.tasks.Exec

// 只组织本地验证；真实检查继续由现有工具负责，不复制兼容基线或调用嵌套 Gradle。
val verifyPublicationTools = tasks.register<Exec>("verifyPublicationTools") {
    group = "verification"
    description = "运行候选、发布包、远端离线验收及矩阵工具的回归测试。"
    workingDir(rootProject.projectDir)
    commandLine("python3", "-B", "-m", "unittest", "discover", "-s", "tools/tests", "-v")
}

val candidateChecks = listOf("library-android" to "ktoast", "library-lifecycle" to "ktoast-lifecycle").map { (module, artifact) ->
    tasks.register<Exec>(if (artifact == "ktoast") "verifyCandidateArtifacts" else "verifyLifecycleCandidateArtifacts") {
        group = "verification"
        description = "生成并校验 $artifact 本地 SNAPSHOT 候选；不签名或上传。"
        dependsOn(":$module:publishReleasePublicationToCandidateRepository")
        workingDir(rootProject.projectDir)
        val version = providers.gradleProperty("VERSION").get()
        commandLine("python3", "-B", "tools/verify_candidate.py", "--artifact", artifact,
            "--javap", file("${System.getProperty("java.home")}/bin/javap").absolutePath,
            "--aar", "$module/build/outputs/aar/$module-release.aar",
            "--publication", "$module/build/publications/release",
            "--sources", "$module/build/intermediates/source_jar/release/release-sources.jar",
            "--javadoc", "$module/build/libs/$module-$version-javadoc.jar",
            "--report", "build/reports/compatibility/$artifact-candidate.json")
    }
}

tasks.register("verifyReleaseCandidate") {
    group = "verification"
    description = "运行本地候选构建、JVM 测试、库 Lint 与产物/工具校验；消费者和设备须单独验收。"
    dependsOn(
        verifyPublicationTools,
        candidateChecks,
        ":library-android:testDebugUnitTest",
        ":library-android:lintDebug",
        ":library-lifecycle:testDebugUnitTest",
        ":library-lifecycle:lintDebug"
    )
}
