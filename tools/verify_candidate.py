#!/usr/bin/env python3
"""检查本地候选的旧 JVM API、字节码目标、AAR 元数据及发布依赖，输出可追溯报告。"""

import argparse
import hashlib
import io
import json
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import BadZipFile, ZipFile

sys.dont_write_bytecode = True
from check_public_api import BASELINE, dump_class

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DEPENDENCIES = {
    ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23"),
    ("androidx.startup", "startup-runtime", "1.1.1"),
}
STDLIB = ("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect_api_docs(javadoc):
    """文档包必须包含真实首页和关键公开 API 页面，拒绝空包或占位包。"""
    with ZipFile(javadoc) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "API 文档包包含重复文件")
        require("index.html" in names and b"<html" in archive.read("index.html").lower(), "API 文档缺少 HTML 首页")
        for suffix in (
            "com.logan.ktoast/-k-toast/index.html",
            "com.logan.ktoast/-k-toast-scope/index.html",
            "com.logan.ktoast/-k-toast-scope-options/index.html",
            "com.logan.ktoast.config/-k-toast-config/index.html",
            "com.logan.ktoast.runtime/-toast-handle/index.html",
        ):
            matches = [name for name in names if name.endswith(suffix)]
            require(len(matches) == 1 and b"<html" in archive.read(matches[0]).lower(),
                    "API 文档缺少公开页面: " + suffix)
        require(not any("-toast-dispatcher/" in name or "-toast-request/" in name for name in names),
                "API 文档暴露 internal 调度实现")
    return {"size": javadoc.stat().st_size, "sha256": hashlib.sha256(javadoc.read_bytes()).hexdigest()}


def inspect_publication(aar, sources, publication, source_dir, javadoc, expected_version=None):
    namespace = {"p": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(publication / "pom-default.xml").getroot()
    module = json.loads((publication / "module.json").read_text(encoding="utf-8"))
    component = module["component"]
    require(component["group"] == "io.github.logan0817" and component["module"] == "ktoast", "候选发布坐标变化")
    version = component["version"]
    require(version == expected_version if expected_version is not None else version.endswith("-SNAPSHOT"),
            "发布版本与预期不一致" if expected_version is not None else "候选版本必须为 SNAPSHOT")
    for tag, expected in (("groupId", component["group"]), ("artifactId", component["module"]),
                          ("version", version), ("packaging", "aar")):
        require(pom.findtext("p:" + tag, namespaces=namespace) == expected, "POM/module 坐标或包装不一致: " + tag)
    pom_dependencies = pom.findall("p:dependencies/p:dependency", namespace)
    expected_pom = {(*STDLIB, "compile"), ("androidx.startup", "startup-runtime", "1.1.1", "runtime")}
    actual_pom = {
        tuple(node.findtext("p:" + key, namespaces=namespace) for key in ("groupId", "artifactId", "version", "scope"))
        for node in pom_dependencies
    }
    require(actual_pom == expected_pom and len(pom_dependencies) == len(expected_pom), "POM 生产依赖或 scope 变化")
    require(all(node.find("p:exclusions", namespace) is None and
                node.findtext("p:optional", default="false", namespaces=namespace) == "false"
                for node in pom_dependencies), "POM 依赖被排除或变为 optional")

    variants = module["variants"]
    selected = {}
    for key, category, usage, element in (
        ("api", "library", "java-api", ("org.gradle.libraryelements", "aar")),
        ("runtime", "library", "java-runtime", ("org.gradle.libraryelements", "aar")),
        ("sources", "documentation", "java-runtime", ("org.gradle.docstype", "sources")),
    ):
        matching = [variant for variant in variants
                    if variant.get("attributes", {}).get("org.gradle.category") == category
                    and variant["attributes"].get("org.gradle.usage") == usage
                    and variant["attributes"].get(element[0]) == element[1]]
        require(len(matching) == 1, "必须存在唯一发布变体: " + key)
        selected[key] = matching[0]
    require(len(variants) == len(selected), "存在未检查的额外发布变体")
    artifacts = {}
    for key, variant in selected.items():
        nodes = variant.get("dependencies", [])
        expected = {STDLIB} if key == "api" else EXPECTED_DEPENDENCIES if key == "runtime" else set()
        actual = {(node["group"], node["module"], node["version"]["requires"]) for node in nodes}
        require(actual == expected and len(nodes) == len(expected), "发布变体依赖变化: " + key)
        require(all(set(node) == {"group", "module", "version"} and set(node["version"]) == {"requires"}
                    for node in nodes) and not variant.get("dependencyConstraints"),
                "发布变体新增依赖限制或排除: " + key)
        require(variant["attributes"].get("org.gradle.dependency.bundling") == "external",
                "发布变体依赖打包方式变化: " + key)
        files = variant.get("files", [])
        require(len(files) == 1, "发布变体缺少或包含额外产物: " + key)
        item = files[0]
        artifact = sources if key == "sources" else aar
        expected_name = "ktoast-" + version + ("-sources.jar" if key == "sources" else ".aar")
        require(item["name"] == expected_name and item["url"] == expected_name, "发布产物名称或地址变化: " + key)
        content = artifact.read_bytes()
        digest = hashlib.sha256(content).hexdigest()
        require(item["size"] == len(content) and item["sha256"] == digest, "发布产物大小或 SHA256 不一致: " + key)
        for algorithm in ("sha512", "sha1", "md5"):
            if algorithm in item:
                require(item[algorithm] == hashlib.new(algorithm, content).hexdigest(), "发布产物校验和不一致: " + key)
        artifacts[key] = {"name": expected_name, "size": len(content), "sha256": digest}

    expected_sources = {path.relative_to(source_dir).as_posix(): path.read_bytes()
                        for path in source_dir.rglob("*.kt")}
    require(bool(expected_sources), "未找到待校验源码")
    with ZipFile(sources) as archive:
        entries = [name for name in archive.namelist() if name.endswith(".kt")]
        require(len(entries) == len(set(entries)), "源码包包含重复文件")
        actual_sources = {name: archive.read(name) for name in entries}
    require(actual_sources == expected_sources, "源码包缺失、过期或与当前源码不一致")
    artifacts["javadoc"] = {"name": "ktoast-" + version + "-javadoc.jar", **inspect_api_docs(javadoc)}
    return {"coordinate": component, "dependencies": sorted(EXPECTED_DEPENDENCIES),
            "publicationArtifacts": artifacts, "sourceFiles": len(expected_sources)}


def inspect(aar, publication, javap, sources, javadoc, expected_version=None):
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    report = {"aar": aar.name, "sha256": hashlib.sha256(aar.read_bytes()).hexdigest(), "size": aar.stat().st_size}
    report.update(inspect_publication(aar, sources, publication, ROOT / "library-android/src/main/java",
                                      javadoc, expected_version))
    with ZipFile(aar) as archive:
        classes = archive.read("classes.jar")
        manifest = ET.fromstring(archive.read("AndroidManifest.xml"))
        metadata = dict(
            line.split("=", 1)
            for line in archive.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode().splitlines()
            if "=" in line and not line.startswith("#")
        )
    require(metadata.get("minCompileSdk") == "1", "minCompileSdk 变化")
    require(metadata.get("minAndroidGradlePluginVersion") == "1.0.0", "最低 AGP 元数据变化")
    require(metadata.get("coreLibraryDesugaringEnabled") == "false", "新增了 core library desugaring 要求")
    report["aarMetadata"] = metadata
    uses_sdk = manifest.find("uses-sdk")
    require(uses_sdk is not None and uses_sdk.get("{http://schemas.android.com/apk/res/android}minSdkVersion") == "21", "未确认 minSdk 21")
    report["minSdk"] = 21
    with ZipFile(io.BytesIO(classes)) as class_archive:
        versions = sorted({
            struct.unpack(">H", class_archive.read(name)[6:8])[0]
            for name in class_archive.namelist() if name.endswith(".class")
        })
    require(versions == [52], "候选库必须使用 JVM 1.8 字节码")
    report["classMajorVersions"] = versions
    with tempfile.TemporaryDirectory(prefix="ktoast-api-") as folder:
        jar = Path(folder) / "classes.jar"
        jar.write_bytes(classes)
        for name, expected in baseline["classes"].items():
            require(dump_class(javap, jar, name) == expected, "旧公开 JVM API 变化: " + name)
        for name in ("com.logan.ktoast.KToast", "com.logan.ktoast.KToastScope", "com.logan.ktoast.config.KToastConfig"):
            verbose = subprocess.run([javap, "-v", "-classpath", str(jar), name], check=True, capture_output=True, text=True).stdout
            require("mv=[1,9,0]" in verbose, "Kotlin metadata 版本变化: " + name)
    report["legacyPublicClasses"] = len(baseline["classes"])
    report["kotlinMetadataVersion"] = [1, 9, 0]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aar", type=Path, default=ROOT / "library-android/build/outputs/aar/library-android-release.aar")
    parser.add_argument("--publication", type=Path, default=ROOT / "library-android/build/publications/release")
    parser.add_argument("--sources", type=Path, default=ROOT / "library-android/build/intermediates/source_jar/release/release-sources.jar")
    parser.add_argument("--javadoc", type=Path, default=ROOT / "library-android/build/libs/library-android-1.1.0-SNAPSHOT-javadoc.jar")
    parser.add_argument("--javap", default="javap")
    parser.add_argument("--report", type=Path, default=ROOT / "build/reports/compatibility/candidate.json")
    args = parser.parse_args()
    try:
        report = inspect(args.aar, args.publication, args.javap, args.sources, args.javadoc)
    except (ValueError, OSError, KeyError, TypeError, BadZipFile, ET.ParseError, subprocess.CalledProcessError) as error:
        raise SystemExit("候选验证失败: " + str(error))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print("候选兼容与发布元数据验证通过:", args.report)
    print("该结果不替代消费者 APK、R8 或设备验证。")


if __name__ == "__main__":
    main()
