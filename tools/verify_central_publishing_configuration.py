#!/usr/bin/env python3
"""真实 Gradle 模型与上传任务图回归；所有上传图均 dry-run，不签名、不联网。"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def verify(launcher):
    environment = dict(os.environ)
    for name in ("KTOAST_SIGNING_KEY", "KTOAST_SIGNING_PASSWORD", "KTOAST_SIGNING_FINGERPRINT"):
        environment.pop(name, None)
    root_task = ":publishToMavenCentral"
    model_task = ":verifyCentralDeploymentName"
    cases = [
        ("snapshot-model", [model_task, "-PVERSION=1.2.0-SNAPSHOT"], False, None, None),
        ("signed-upload-plan", [root_task, "--dry-run"], True, None, None),
        ("unsigned-upload", [root_task, "--dry-run"], False, None, "signed non-SNAPSHOT"),
        ("snapshot-upload", [root_task, "--dry-run", "-PVERSION=1.2.0-SNAPSHOT"], True, None, "signed non-SNAPSHOT"),
        ("direct-leaf", [":library-android:publishReleasePublicationToMavenCentralRepository", "--dry-run"], True,
         None, "root :publishToMavenCentral"),
        ("direct-module-entry", [":library-android:publishToMavenCentral", "--dry-run"], True,
         None, "root :publishToMavenCentral"),
        ("direct-create", [":library-android:createStagingRepository", "--dry-run"], True,
         None, "root :publishToMavenCentral"),
        ("direct-lifecycle-leaf", [":library-lifecycle:publishReleasePublicationToMavenCentralRepository", "--dry-run"], True,
         None, "root :publishToMavenCentral"),
        ("generic-publish", [":library-android:publish", "--dry-run"], True, None, "forbids clean, generic publish"),
        ("automatic-entry", [":library-android:publishAndReleaseToMavenCentral", "--dry-run"], True,
         None, "forbids clean, generic publish"),
        ("direct-release", [":library-android:releaseRepository", "--dry-run"], True, None, "forbids clean, generic publish"),
        ("direct-drop", [":library-android:dropRepository", "--dry-run"], True, None, "forbids clean, generic publish"),
        ("clean-upload", [root_task, "clean", "--dry-run"], True, None, "forbids clean, generic publish"),
        ("skip-final-verifier", [root_task, "--dry-run", "-x", "verifyCentralStaging"], True,
         None, "complete verification"),
        ("skip-signing", [root_task, "--dry-run", "-x", "signReleasePublication"], True,
         None, "excluded tasks"),
        ("offline-real-upload", [root_task], True, None, "forbids an offline upload"),
        ("skip-environment", [root_task, "--dry-run", "-x", "verifyCentralUploadEnvironment"], True,
         None, "complete verification"),
        ("continue-after-failure", [root_task, "--dry-run", "--continue"], True, None, "forbids --continue"),
        ("wrong-deployment-name", [model_task], False, "name", "deployment identity"),
        ("wrong-publication-coordinate", [model_task], False, "coordinate", "coordinates changed"),
        ("wrong-lifecycle-coordinate", [model_task], False, "lifecycle-coordinate", "coordinates changed"),
        ("automatic-model", [model_task], False, "automatic", "automaticRelease=false"),
        ("wrong-central-host", [model_task], False, "host", "CENTRAL_PORTAL"),
    ]
    checked = []
    with tempfile.TemporaryDirectory(prefix="kt-central-config-") as temporary:
        environment["GNUPGHOME"] = temporary
        init = Path(temporary) / "model-fixture.init.gradle"
        # 仅测试夹具改变配置模型；所有网络任务仍 dry-run，测试密钥环始终为空。
        init.write_text('''gradle.taskGraph.whenReady {
    def fixture = gradle.startParameter.projectProperties['centralModelFixture']
    if (!fixture) return
    def module = gradle.rootProject.project(':library-android')
    if (fixture == 'name') module.tasks.getByName('createStagingRepository').artifactId.set('wrong')
    if (fixture == 'lifecycle-coordinate') gradle.rootProject.project(':library-lifecycle').extensions.getByName('publishing').publications.getByName('release').artifactId = 'wrong'
    if (fixture == 'coordinate') module.extensions.getByName('publishing').publications.getByName('release').artifactId = 'wrong'
    def parameters = gradle.sharedServices.registrations.getByName('sonatype-repository-build-service').parameters
    if (fixture == 'automatic') parameters.automaticRelease.set(true)
    if (fixture == 'host') {
        def host = parameters.sonatypeHost.get()
        parameters.sonatypeHost.set(host.getClass().getField('S01').get(null))
    }
}
''', encoding="utf-8")
        for name, arguments, signed, mutation, expected_error in cases:
            command = launcher + ["--offline", "--console=plain", "--no-configuration-cache", "-PVERSION=1.2.0",
                                  "-PsignPublication=" + str(signed).lower(), "-PsigningMode=gpg",
                                  "-Psigning.gnupg.keyName=" + "A" * 40,
                                  "-Psigning.gnupg.homeDir=" + temporary,
                                  "-Psigning.gnupg.useLegacyGpg=false"]
            if mutation:
                command += ["--init-script", str(init), "-PcentralModelFixture=" + mutation]
            result = subprocess.run(command + arguments, cwd=ROOT, env=environment,
                                    capture_output=True, text=True, timeout=180)
            output = result.stdout + result.stderr
            passed = (result.returncode == 0 and "BUILD SUCCESSFUL" in output) if expected_error is None else (
                result.returncode != 0 and expected_error in output)
            if not passed:
                # 此处只有空测试密钥环和虚构指纹；仍不输出环境值或完整命令日志。
                raise ValueError("Central 配置回归不符合预期: " + name + "（退出码 " + str(result.returncode) + "）")
            if name == "signed-upload-plan":
                order = [":verifyCentralUploadEnvironment SKIPPED", ":library-android:signReleasePublication SKIPPED",
                         ":library-android:publishReleasePublicationToStagingRepository SKIPPED", ":prepareCentralBundle SKIPPED",
                         ":library-android:createStagingRepository SKIPPED",
                         ":library-android:publishReleasePublicationToMavenCentralRepository SKIPPED",
                         ":verifyCentralStaging SKIPPED", ":publishToMavenCentral SKIPPED"]
                positions = [output.find(task) for task in order]
                if any(position < 0 for position in positions) or positions != sorted(positions):
                    raise ValueError("Central 上传前验收任务排序不符合预期")
                for phase in ("signReleasePublication", "publishReleasePublicationToStagingRepository", "createStagingRepository", "publishReleasePublicationToMavenCentralRepository"):
                    position = output.find(":library-lifecycle:" + phase + " SKIPPED")
                    before = output.find(":verifyCentralUploadEnvironment SKIPPED")
                    after = output.find(":prepareCentralBundle SKIPPED") if phase in ("signReleasePublication", "publishReleasePublicationToStagingRepository") else output.find(":verifyCentralStaging SKIPPED")
                    if position < before or position >= after:
                        raise ValueError("Lifecycle 上传前任务排序不符合预期")
            checked.append({"case": name, "passed": True, "expectedRejection": expected_error is not None})
            print("PASS:", name, flush=True)
    return {"cases": checked, "remoteUploadExecuted": False, "signingExecuted": False, "personalKeyringAccessed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/central-publishing/configuration.json")
    parser.add_argument("launcher", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    launcher = args.launcher[1:] if args.launcher and args.launcher[0] == "--" else args.launcher
    if not launcher:
        parser.error("必须在 -- 后提供 Gradle 启动命令")
    try:
        report = verify(launcher)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print("Central 模型和任务图回归通过:", args.output)


if __name__ == "__main__":
    main()
