#!/usr/bin/env python3
"""在临时工程和本机假 Portal 验证插件结束阶段行为，不使用正式凭据或发布密钥。"""

import argparse
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, HTTPServer
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
import tempfile
from threading import Thread
from urllib.parse import parse_qs, urlparse
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def verify(launcher):
    requests = []

    class PortalFixture(BaseHTTPRequestHandler):
        def do_POST(self):
            content = self.rfile.read(int(self.headers["Content-Length"]))
            newline = bytes((13, 10))
            message = BytesParser(policy=default).parsebytes(
                ("Content-Type: " + self.headers["Content-Type"]).encode() + newline + newline + content)
            parts = list(message.iter_parts())
            payload = next(part.get_payload(decode=True) for part in parts
                           if part.get_param("name", header="content-disposition") == "bundle")
            with ZipFile(BytesIO(payload)) as archive:
                files = {name: archive.read(name).decode() for name in archive.namelist()}
            parsed = urlparse(self.path)
            requests.append({"path": parsed.path, "query": parse_qs(parsed.query), "files": files})
            response = b"fixture-deployment-id"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

        def do_DELETE(self):
            requests.append({"unexpected": "DELETE"})
            self.send_response(500)
            self.end_headers()

        def log_message(self, *_):
            pass

    server = HTTPServer(("127.0.0.1", 0), PortalFixture)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    checked = []
    try:
        with tempfile.TemporaryDirectory(prefix="kt-central-service-") as temporary:
            folder = Path(temporary)
            keyring = folder / "keyring"
            keyring.mkdir(mode=0o700)
            environment = dict(os.environ)
            for name in ("KTOAST_SIGNING_KEY", "KTOAST_SIGNING_PASSWORD", "KTOAST_SIGNING_FINGERPRINT",
                         "ORG_GRADLE_PROJECT_mavenCentralUsername", "ORG_GRADLE_PROJECT_mavenCentralPassword"):
                environment.pop(name, None)
            environment["GNUPGHOME"] = str(keyring)
            (folder / "settings.gradle.kts").write_text('rootProject.name = "Fixture"' + chr(10))
            (folder / "payload.txt").write_text("public fixture content")
            # 显式替换该临时工程的 service 地址和 token，不读取或修改用户正式配置。
            script = '''import org.gradle.api.provider.Property
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
buildscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("com.vanniktech:gradle-maven-publish-plugin:0.33.0") }
}
apply(plugin = "com.vanniktech.maven.publish.base")
group = "test.fixture"
version = "1.0.0"
configure<MavenPublishBaseExtension> { publishToMavenCentral(false) }
configure<PublishingExtension> {
    publications {
        create<MavenPublication>("release") { artifact(file("payload.txt")); artifactId = "fixture" }
    }
}
val parameters = gradle.sharedServices.registrations.getByName("sonatype-repository-build-service").parameters
@Suppress("UNCHECKED_CAST")
fun fixtureParameter(name: String): Property<Any> =
    parameters.javaClass.getMethod("get$name").invoke(parameters) as Property<Any>
val host = fixtureParameter("SonatypeHost").get()
val constructor = host.javaClass.getDeclaredConstructor(String::class.java, Boolean::class.javaPrimitiveType)
fixtureParameter("SonatypeHost").set(constructor.newInstance("FIXTURE_URL", true))
fixtureParameter("RepositoryUsername").set("fixture-user")
fixtureParameter("RepositoryPassword").set("fixture-password")
tasks.named("createStagingRepository") {
    @Suppress("UNCHECKED_CAST")
    (property("artifactId") as Property<String>).set("Fixture")
}
tasks.register("verifyBeforeClose") {
    dependsOn("publishReleasePublicationToMavenCentralRepository")
    doLast {
        if (providers.gradleProperty("fixtureFail").get() == "true") throw GradleException("Fixture verification failure")
    }
}
'''
            script = script.replace("FIXTURE_URL", "http://127.0.0.1:" + str(server.server_port))
            (folder / "build.gradle.kts").write_text(script, encoding="utf-8")
            for fails in (True, False):
                requests.clear()
                command = launcher + ["-p", str(folder), "verifyBeforeClose", "--offline", "--no-configuration-cache",
                                      "--console=plain", "-PfixtureFail=" + str(fails).lower()]
                result = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, text=True, timeout=180)
                output = result.stdout + result.stderr
                if fails:
                    passed = result.returncode != 0 and "Fixture verification failure" in output and not requests
                    name = "failed-verification-does-not-upload"
                else:
                    passed = result.returncode == 0 and len(requests) == 1
                    if passed:
                        request = requests[0]
                        passed = (request["path"] == "/api/v1/publisher/upload" and request["query"] == {
                            "name": ["test.fixture-Fixture-1.0.0"], "publishingType": ["USER_MANAGED"]} and
                            request["files"].get("test/fixture/fixture/1.0.0/fixture-1.0.0.txt") == "public fixture content" and
                            all("maven-metadata" not in path for path in request["files"]))
                    name = "successful-build-uploads-once-user-managed"
                if not passed:
                    diagnostic = ROOT / "build/reports/central-publishing/service-failure.log"
                    diagnostic.parent.mkdir(parents=True, exist_ok=True)
                    diagnostic.write_text(output, encoding="utf-8")
                    raise ValueError("Central build service 回归不符合预期: " + name + "（退出码 " + str(result.returncode) + "）")
                checked.append({"case": name, "passed": True, "localFixtureRequests": len(requests)})
                print("PASS:", name, flush=True)
    finally:
        server.shutdown()
        thread.join(timeout=5)
        server.server_close()
    return {"cases": checked, "remoteCentralContacted": False, "releaseExecuted": False,
            "signingExecuted": False, "scope": "仅临时工程和 127.0.0.1 假 Portal，非真实服务端验收"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/reports/central-publishing/service.json")
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
    (ROOT / "build/reports/central-publishing/service-failure.log").unlink(missing_ok=True)
    print("Central build service 隔离回归通过:", args.output)


if __name__ == "__main__":
    main()
