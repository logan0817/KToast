#!/usr/bin/env python3
"""保护正式版本 JVM API，只允许经审阅的精确接口增量。"""

import argparse
import json
import re
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / "docs/api/ktoast-1.0.5.json"
RELEASE_BASELINE = ROOT / "docs/api/ktoast-1.2.0.json"
ADDITIONS = ROOT / "docs/api/ktoast-1.3.0-additions.json"


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
            if re.search(r"\$(?:main|library_(?:android|lifecycle|compose)[^$( ]*)(?:\$default)?\(", line):
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


def compare_api(actual, baseline, additions):
    """新旧成员都精确比较；增量清单不能隐性移除或覆盖旧声明。"""
    expected = {name: list(members) for name, members in baseline.items()}
    for name, members in additions.items():
        existing = expected.setdefault(name, [])
        for member in members:
            if member in existing:
                raise ValueError("增量重复已有 API: " + name)
            existing.append(member)
    errors = []
    for name in sorted(set(actual) | set(expected)):
        before = expected.get(name, [])
        after = actual.get(name, [])
        removed = [member for member in before if member not in after]
        added = [member for member in after if member not in before]
        if removed or added or (name in expected) != (name in actual):
            errors.append({"class": name, "removed": removed, "added": added})
    return errors



def synthetic_class(content):
    """只排除 JVM 标记的合成类，保留公开类上的 default/桥接成员。"""
    if content[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("公开 API 扫描遇到无效 class 文件")
    count = struct.unpack_from(">H", content, 8)[0]
    offset, index = 10, 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = content[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", content, offset)[0]
            offset += 2 + length
        elif tag in sizes:
            offset += sizes[tag]
            if tag in (5, 6): index += 1
        else:
            raise ValueError("公开 API 扫描遇到未知 class 常量类型")
        index += 1
    return bool(struct.unpack_from(">H", content, offset)[0] & 0x1000)


def public_api(javap, classes, source_dir):
    # Kotlin internal 在 JVM 上也是 public；以已核对的 Kotlin 源码排除整棵内部类型。
    hidden = set()
    for path in source_dir.rglob("*.kt"):
        source = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", source, re.MULTILINE)
        if package:
            for name in re.findall(
                r"^(?:internal|private)\s+(?:(?:data|fun|enum|sealed|open|abstract|value|annotation)\s+)*"
                r"(?:class|object|interface)\s+(\w+)", source, re.MULTILINE
            ):
                hidden.add(package[1] + "." + name)
    if classes.is_dir():
        contents = {path.relative_to(classes).as_posix(): path.read_bytes() for path in classes.rglob("*.class")}
    else:
        with ZipFile(classes) as archive:
            contents = {name: archive.read(name) for name in archive.namelist() if name.endswith(".class")}
    result = {}
    for entry in sorted(contents):
        if not entry.startswith("com/logan/ktoast/") or not entry.endswith(".class"):
            continue
        name = entry[:-6].replace("/", ".")
        if name.split("$", 1)[0] in hidden or re.search(r"\$\d+(?:\$|$)", name):
            continue
        if synthetic_class(contents[entry]):
            continue
        members = dump_class(javap, classes, name)
        if any(member["declaration"].endswith("{") for member in members):
            result[name] = members
    return result


def inspect_api(javap, classes, source_dir=ROOT / "library-android/src/main/java"):
    legacy = json.loads(BASELINE.read_text(encoding="utf-8"))["classes"]
    previous = json.loads((ROOT / "docs/api/ktoast-1.1.0.json").read_text(encoding="utf-8"))["classes"]
    released = json.loads(RELEASE_BASELINE.read_text(encoding="utf-8"))["classes"]
    for before, after, label in ((legacy, previous, "1.1.0"), (previous, released, "1.2.0")):
        for name, members in before.items():
            if any(member not in after.get(name, []) for member in members):
                raise ValueError(label + " 基线丢失旧 API: " + name)
    approved_120 = json.loads((ROOT / "docs/api/ktoast-1.2.0-additions.json").read_text(encoding="utf-8"))["classes"]
    if compare_api(released, previous, approved_120):
        raise ValueError("1.2.0 正式基线与已审阅历史增量不一致")
    additions = json.loads(ADDITIONS.read_text(encoding="utf-8"))["classes"]
    actual = public_api(javap, classes, source_dir)
    errors = compare_api(actual, released, additions)
    if errors:
        raise ValueError("公开 JVM API 不匹配: " + json.dumps(errors, ensure_ascii=False))
    return {"legacyPublicClasses": len(legacy), "releasedPublicClasses": len(released), "publicApi": actual}


def inspect_module_api(javap, classes, source_dir, artifact):
    """扩展也按冻结发布基线及本版精确增量检查，未知公开 API 一律拒绝。"""
    baseline = ROOT / ("docs/api/" + artifact + "-1.2.0.json")
    expected = json.loads(baseline.read_text(encoding="utf-8"))["classes"] if artifact == "ktoast-lifecycle" else {}
    additions = json.loads((ROOT / ("docs/api/" + artifact + "-1.3.0-additions.json")).read_text(encoding="utf-8"))["classes"]
    actual = public_api(javap, classes, source_dir)
    errors = compare_api(actual, expected, additions)
    if errors:
        raise ValueError(artifact + " 公开 JVM API 不匹配: " + json.dumps(errors, ensure_ascii=False))
    return {"releasedPublicClasses": len(expected), "publicApi": actual}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", required=True, type=Path, help="候选 classes.jar 或编译类目录")
    parser.add_argument("--javap", default="javap", help="现有 JDK 的 javap 路径")
    args = parser.parse_args()
    try:
        report = inspect_api(args.javap, args.classes)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
    print(f"1.0.5/1.1.0/1.2.0 兼容及精确增量通过：{len(report['publicApi'])} 个公开类")


if __name__ == "__main__":
    main()
