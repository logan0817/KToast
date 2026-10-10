#!/usr/bin/env python3
"""比较候选 classes.jar 或类目录与 1.0.5 的公开 JVM 签名，不依赖额外 Python 包。"""

import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "docs/api/ktoast-1.0.5.json"


def dump_class(javap, classpath, name):
    result = subprocess.run(
        [javap, "-classpath", str(classpath), "-public", "-s", name],
        check=True, capture_output=True, text=True,
    )
    members = []
    declaration = None
    for raw in result.stdout.splitlines():
        line = raw.strip()
        if line.startswith("public ") or line.startswith("protected "):
            if "access$" in line:
                declaration = None
                continue
            # Kotlin internal 成员带编译模块后缀，不属于承诺稳定的公开 API。
            if re.search(r"\$(?:main|library_android[^$( ]*)(?:\$default)?\(", line):
                declaration = None
                continue
            declaration = line
            if line.endswith("{"):
                members.append({"declaration": line})
                declaration = None
        elif line.startswith("descriptor:") and declaration is not None:
            members.append({"declaration": declaration, "descriptor": line.removeprefix("descriptor:").strip()})
            declaration = None
    return sorted(members, key=lambda entry: entry["declaration"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", required=True, type=Path, help="候选 classes.jar 或编译类目录")
    parser.add_argument("--javap", default="javap", help="现有 JDK 的 javap 路径")
    args = parser.parse_args()
    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    failed = []
    for name, expected in baseline["classes"].items():
        try:
            actual = dump_class(args.javap, args.classes, name)
        except subprocess.CalledProcessError as error:
            print(f"无法读取公开类 {name}: {error.stderr.strip()}")
            failed.append(name)
            continue
        if actual != expected:
            failed.append(name)
            removed = [member for member in expected if member not in actual]
            added = [member for member in actual if member not in expected]
            print(f"公开 API 变化: {name}")
            for member in removed:
                print(f"  移除或改变: {member}")
            for member in added:
                print(f"  新增或改变: {member}")
    if failed:
        raise SystemExit(1)
    print(f"公开 JVM API 与 1.0.5 基线一致：{len(baseline['classes'])} 个公开类")


if __name__ == "__main__":
    main()
