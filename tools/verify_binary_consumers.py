#!/usr/bin/env python3
"""对正式旧 AAR 编译消费者，再以完全相同字节码运行当前候选；不执行 Android 窗口。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
from urllib.request import urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cached_jar(cache, group, artifact, version):
    matches = list((cache / group / artifact / version).glob("*/" + artifact + "-" + version + ".jar"))
    if len(matches) != 1:
        raise ValueError("需要已缓存的编译依赖：" + artifact + ":" + version)
    return matches[0]


def run(command):
    result = subprocess.run([str(item) for item in command], check=True, capture_output=True, text=True)
    return result.stdout.strip()


def verify(java_home, sdk, gradle_home):
    cache = gradle_home / "caches/modules-2/files-2.1"
    stdlib = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-stdlib", "1.9.23")
    compiler = [cached_jar(cache, "org.jetbrains.kotlin", artifact, version) for artifact, version in (
        ("kotlin-compiler-embeddable", "1.9.23"), ("kotlin-stdlib", "1.9.23"),
        ("kotlin-script-runtime", "1.9.23"), ("kotlin-reflect", "1.6.10"))]
    compiler.append(cached_jar(cache, "org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"))
    compiler.append(cached_jar(cache, "org.jetbrains", "annotations", "13.0"))
    android = sdk / "platforms/android-31/android.jar"
    output = ROOT / "build/reports/compatibility/binary-consumers"
    output.mkdir(parents=True, exist_ok=True)
    candidate = output / "candidate.jar"
    with ZipFile(ROOT / "library-android/build/outputs/aar/library-android-release.aar") as archive:
        candidate.write_bytes(archive.read("classes.jar"))
    reports = []
    for version, suffix in (("1.0.5", ""), ("1.1.0", "110")):
        folder = output / version
        folder.mkdir(exist_ok=True)
        aar = folder / ("ktoast-" + version + ".aar")
        url = "https://repo.maven.apache.org/maven2/io/github/logan0817/ktoast/" + version + "/" + aar.name
        if not aar.exists():
            with urlopen(url, timeout=30) as response: aar.write_bytes(response.read())
        with urlopen(url + ".sha256", timeout=30) as response:
            if digest(aar) != response.read().decode().strip():
                raise ValueError("正式 AAR 摘要不符：" + version)
        baseline = folder / "baseline.jar"
        with ZipFile(aar) as archive: baseline.write_bytes(archive.read("classes.jar"))
        sources = [ROOT / "tools/compatibility" / (language + "Consumer" + suffix + extension)
                   for language, extension in (("Java", ".java"), ("Kotlin", ".kt"))]
        stamp = {"baseline": digest(aar), "sources": [digest(path) for path in sources],
                 "compiler": [digest(path) for path in compiler], "android": digest(android)}
        stamp_file = folder / "compiled.json"
        classes = folder / "classes"
        if not stamp_file.exists() or json.loads(stamp_file.read_text()) != stamp:
            classes.mkdir(exist_ok=True)
            classpath = os.pathsep.join(str(path) for path in (baseline, stdlib, android))
            run([java_home / "bin/javac", "-source", "8", "-target", "8", "-cp", classpath,
                 "-d", classes, sources[0]])
            run([java_home / "bin/java", "-cp", os.pathsep.join(map(str, compiler)),
                 "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "1.8",
                 "-classpath", classpath, "-d", classes, sources[1]])
            stamp_file.write_text(json.dumps(stamp, indent=2) + "\n")
        before = {path.relative_to(classes).as_posix(): digest(path) for path in classes.rglob("*.class")}
        classpath = os.pathsep.join(str(path) for path in (classes, candidate, stdlib, android))
        names = ("JavaConsumer", "KotlinConsumerKt") if not suffix else ("JavaConsumer110", "KotlinConsumer110Kt")
        results = [run([java_home / "bin/java", "-cp", classpath, "compatibility." + name]) for name in names]
        after = {path.relative_to(classes).as_posix(): digest(path) for path in classes.rglob("*.class")}
        if not before or before != after: raise ValueError("消费者字节码改变")
        reports.append({"version": version, "baselineSha256": digest(aar), "classes": before, "results": results})
    report = {"candidateSha256": digest(candidate), "consumers": reports, "androidWindowsExecuted": False}
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--gradle-home", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.java_home, args.sdk, args.gradle_home)
    for consumer in result["consumers"]:
        print(consumer["version"], "Java/Kotlin frozen bytecode PASS")
