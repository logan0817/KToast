#!/usr/bin/env python3
"""离线核对插件暂存文件与正式 ZIP，失败阻止 build service 上传；不联网。"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from zipfile import BadZipFile, ZipFile

sys.dont_write_bytecode = True
from prepare_central_bundle import GROUP_PATH, deployment_identity, verify_signatures
from verify_candidate import ROOT, require


def check_source(root=ROOT):
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                            capture_output=True, text=True).stdout.strip()
    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=root, check=True,
                           capture_output=True, text=True).stdout.strip()
    require(not dirty, "Central 上传要求干净源码；请先提交并验收发布配置，不能使用未提交修改")
    return {"commit": commit, "dirty": False}


def verify(staging, bundle, bundle_report, version, public_key, fingerprint, gpg="gpg", root=ROOT,
           normalize_signature_checksums=False):
    source = check_source(root)
    identity = deployment_identity(version)
    report = json.loads(bundle_report.read_text(encoding="utf-8"))
    require(report.get("rehearsal") is False, "测试演练包或缺少正式标记的报告不能上传")
    require(report.get("source") == source, "正式报告必须对应当前干净 HEAD")
    require(report.get("deployment") == identity and
            all(report.get("coordinate", {}).get(key) == value for key, value in
                {"group": "io.github.logan0817", "module": "ktoast", "version": version}.items()),
            "正式报告的批次或坐标不一致")
    require(bundle.is_file() and not bundle.is_symlink(), "正式 ZIP 缺失或为符号链接")
    bundle_sha = hashlib.sha256(bundle.read_bytes()).hexdigest()
    require(bundle_sha == report["bundle"]["sha256"], "正式 ZIP 与报告摘要不一致")
    relative = GROUP_PATH / version
    names = ["ktoast-" + version + suffix for suffix in
             (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module")]
    allowed = set(names + [name + ".asc" for name in names]) | {
        name + "." + algorithm for name in names for algorithm in ("md5", "sha1", "sha256", "sha512")}
    expected = {(relative / name).as_posix() for name in allowed}
    signature_checksums = {(relative / (name + ".asc." + algorithm)).as_posix()
                           for name in names for algorithm in ("md5", "sha1", "sha256", "sha512")}
    # 插件只忽略 Maven 元数据；仅允许 Gradle 在当前 artifact 目录生成的精确文件名。
    metadata = {(GROUP_PATH / ("maven-metadata.xml" + suffix)).as_posix()
                for suffix in ("", ".md5", ".sha1", ".sha256", ".sha512")}
    require(staging.is_dir() and not staging.is_symlink(), "插件暂存目录缺失或为符号链接")
    actual = set()
    for path in staging.rglob("*"):
        require(not path.is_symlink(), "插件暂存中不允许符号链接")
        require(path.is_dir() or path.is_file(), "插件暂存中不允许特殊文件")
        if path.is_file():
            actual.add(path.relative_to(staging).as_posix())
    derived = actual & signature_checksums
    permitted_derived = signature_checksums if normalize_signature_checksums else set()
    require(expected <= actual and actual <= expected | metadata | permitted_derived,
            "插件暂存包含缺失或非预期文件，禁止上传")
    if derived:
        require(derived == signature_checksums, "签名派生校验和不完整，禁止规范化或上传")
        require(staging.resolve().parent == (root / "build/publish/staging").resolve(),
                "仅允许规范化插件的临时暂存目录，不能修改本地发布仓库")
    require(set(report.get("files", {})) == allowed and report["bundle"].get("files") == len(allowed),
            "正式报告的逐文件集合不完整")
    checked = {}
    with ZipFile(bundle) as archive:
        entries = archive.namelist()
        require(len(entries) == len(set(entries)) and set(entries) == expected,
                "正式 ZIP 包含缺失、重复或非预期文件")
        for name in sorted(allowed):
            content = archive.read((relative / name).as_posix())
            item = {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
            require(item == report["files"][name], "ZIP 文件与报告不一致: " + name)
            require((staging / relative / name).read_bytes() == content, "插件暂存与正式 ZIP 不一致: " + name)
            checked[name] = item
        for name in names:
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = archive.read((relative / (name + "." + algorithm)).as_posix()).decode("ascii").strip()
                require(checksum == hashlib.new(algorithm, archive.read((relative / name).as_posix())).hexdigest(),
                        "正式 ZIP 校验和错误: " + name)
    signatures = verify_signatures(staging / relative, names, public_key, fingerprint, gpg)
    normalized = {}
    # Gradle 会给五份 .asc 另生成 20 份校验和。它们不属于冻结的 30 文件发布包，
    # 必须全部验证后才从插件临时目录移除；禁止放宽文件集合或静默丢弃未知文件。
    for path in sorted(derived):
        algorithm = path.rsplit(".", 1)[1]
        content = (staging / path).read_bytes()
        signature = (staging / path.rsplit(".", 1)[0]).read_bytes()
        require(content.decode("ascii").strip() == hashlib.new(algorithm, signature).hexdigest(),
                "签名派生校验和错误: " + path)
        normalized[path] = {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
    for path in normalized:
        (staging / path).unlink()
    return {"verifiedAtUtc": datetime.now(timezone.utc).isoformat(), "source": source,
            "deployment": identity, "bundleSha256": bundle_sha, "files": checked, "signatures": signatures,
            "normalizedSignatureChecksums": normalized,
            "stagingDirectory": str(staging.resolve()), "remoteUploaded": False,
            "scope": "仅本地上传前验收；实际网络上传在 Gradle build service 结束时发生，须另行验收 deployment ID"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-source", action="store_true")
    parser.add_argument("--normalize-signature-checksums", action="store_true",
                        help="仅在插件临时目录验证并移除 .asc 的派生校验和，使上传仍精确包含 30 文件")
    parser.add_argument("--staging", type=Path)
    parser.add_argument("--bundle", type=Path)
    parser.add_argument("--bundle-report", type=Path)
    parser.add_argument("--version")
    parser.add_argument("--public-key", type=Path)
    parser.add_argument("--fingerprint")
    parser.add_argument("--gpg", default="gpg")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.check_source and any(getattr(args, key) is None for key in
                                    ("staging", "bundle", "bundle_report", "version", "public_key", "fingerprint", "output")):
        parser.error("暂存验收需要完整的正式包、版本、公钥和输出参数")
    try:
        if args.check_source:
            check_source()
            print("Central 干净源码门禁通过")
            return
        report = verify(args.staging, args.bundle, args.bundle_report, args.version, args.public_key,
                        args.fingerprint, args.gpg, normalize_signature_checksums=args.normalize_signature_checksums)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    except (ValueError, OSError, KeyError, TypeError, BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit("Central 上传前验收失败: " + str(error))
    print("插件暂存与正式 ZIP 的逐文件验收通过:", args.output)
    print("尚未上传或正式发布；必须等待构建结束并记录实际 deployment ID。")


if __name__ == "__main__":
    main()
