#!/usr/bin/env python3
"""离线检查本地签名发布仓库，生成 Central Portal 验收包；不执行网络上传。"""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import BadZipFile, ZIP_DEFLATED, ZipFile

sys.dont_write_bytecode = True
from verify_candidate import ROOT, inspect, require

GROUP_PATH = Path("io/github/logan0817/ktoast")


def deployment_identity(version):
    require(bool(re.fullmatch(r"[0-9]+[.][0-9]+[.][0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?", version))
            and not version.endswith("-SNAPSHOT"), "Central 发布包要求明确的非 SNAPSHOT 版本")
    return {"name": "io.github.logan0817-KToast-" + version,
            "coordinate": "io.github.logan0817:ktoast:" + version}


def collect(repository, version, artifact="ktoast"):
    require(artifact in ("ktoast", "ktoast-lifecycle"), "未知模块")
    deployment_identity(version)
    directory = repository / GROUP_PATH.parent / artifact / version
    prefix = artifact + "-" + version
    names = [prefix + suffix for suffix in (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module")]
    payload = {}
    for name in names:
        path = directory / name
        require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0, "缺少真实产物: " + name)
        payload[name] = path.read_bytes()
        signature = directory / (name + ".asc")
        require(signature.is_file() and not signature.is_symlink(), "缺少签名: " + name)
        payload[signature.name] = signature.read_bytes()
        require(payload[signature.name].startswith(b"-----BEGIN PGP SIGNATURE-----"), "签名必须为 ASCII armor: " + name)
        # Central 强制 MD5/SHA1，额外提供 SHA256/SHA512；读取并核对 Gradle 实际生成的校验文件。
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            checksum = directory / (name + "." + algorithm)
            require(checksum.is_file() and not checksum.is_symlink(), "缺少校验文件: " + checksum.name)
            digest = hashlib.new(algorithm, payload[name]).hexdigest()
            require(checksum.read_text(encoding="ascii").strip().lower() == digest,
                    "校验和不一致: " + checksum.name)
            payload[checksum.name] = digest.encode("ascii")
    return names, payload


def verify_signatures(folder, names, public_key, fingerprint, gpg):
    require(bool(re.fullmatch(r"(?:[0-9A-Fa-f]{40}|[0-9A-Fa-f]{64})", fingerprint)), "必须提供完整签名公钥指纹")
    # macOS 临时目录本身较长，短前缀避免 gpg-agent 的 Unix socket 路径超过系统限制。
    with tempfile.TemporaryDirectory(prefix="kt-pgp-") as temporary:
        keyring = Path(temporary)
        keyring.chmod(0o700)
        command = [gpg, "--no-options", "--homedir", str(keyring), "--batch", "--no-auto-key-retrieve"]
        try:
            imported = subprocess.run(command + ["--import", str(public_key.resolve())], capture_output=True)
            require(imported.returncode == 0, "签名公钥无法导入临时密钥环")
            results = {}
            for name in names:
                result = subprocess.run(command + ["--status-fd", "1", "--verify", str(folder / (name + ".asc")),
                                                   str(folder / name)], capture_output=True, text=True)
                valid = [line.split() for line in result.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
                require(result.returncode == 0 and len(valid) == 1 and "[GNUPG:] GOODSIG " in result.stdout,
                        "签名验证失败或密钥已过期/撤销: " + name)
                signer = valid[0][2].upper()
                primary = valid[0][-1].upper()
                require(fingerprint.upper() in (signer, primary), "签名与指定公钥指纹不一致: " + name)
                results[name] = {"signer": signer, "primary": primary}
            return results
        finally:
            gpgconf = shutil.which("gpgconf")
            if gpgconf:
                subprocess.run([gpgconf, "--homedir", str(keyring), "--kill", "gpg-agent"], capture_output=True)


def check_pom(pom):
    namespace = {"p": "http://maven.apache.org/POM/4.0.0"}
    root = ET.parse(pom).getroot()
    for selector in ("name", "description", "url", "licenses/license/name", "licenses/license/url",
                     "developers/developer/id", "developers/developer/name", "scm/url",
                     "scm/connection", "scm/developerConnection"):
        node = root.find("/".join("p:" + part for part in selector.split("/")), namespace)
        require(node is not None and bool((node.text or "").strip()), "POM 缺少发布信息: " + selector)


def prepare_one(repository, version, public_key, fingerprint, output, javap, gpg, artifact="ktoast"):
    names, payload = collect(repository, version, artifact)
    with tempfile.TemporaryDirectory(prefix="ktoast-central-check-") as temporary:
        folder = Path(temporary)
        for name, content in payload.items():
            (folder / name).write_bytes(content)
        prefix = artifact + "-" + version
        signatures = verify_signatures(folder, names, public_key, fingerprint, gpg)
        check_pom(folder / (prefix + ".pom"))
        shutil.copyfile(folder / (prefix + ".pom"), folder / "pom-default.xml")
        shutil.copyfile(folder / (prefix + ".module"), folder / "module.json")
        # 使用实际 staging 内容做完整兼容检查，不依赖可能被其他构建覆盖的 publications 目录。
        report = inspect(folder / (prefix + ".aar"), folder, javap, folder / (prefix + "-sources.jar"),
                         folder / (prefix + "-javadoc.jar"), expected_version=version, artifact=artifact)
        report["signatures"] = signatures
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".zip", delete=False) as temporary:
        archive_path = Path(temporary.name)
    try:
        with ZipFile(archive_path, "w", ZIP_DEFLATED) as archive:
            for name, content in sorted(payload.items()):
                archive.writestr((GROUP_PATH.parent / artifact / version / name).as_posix(), content)
        archive_path.replace(output)
    finally:
        archive_path.unlink(missing_ok=True)
    report["bundle"] = {"file": output.name, "files": len(payload),
                        "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}
    report["deployment"] = deployment_identity(version)
    report["files"] = {name: {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                       for name, content in sorted(payload.items())}
    report["source"] = {
        "commit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
                                 capture_output=True, text=True).stdout.strip(),
        "dirty": bool(subprocess.run(["git", "status", "--porcelain"], cwd=ROOT, check=True,
                                     capture_output=True, text=True).stdout.strip()),
    }
    report["remoteValidation"] = "未执行 Central Portal 上传或服务端校验"
    return report


def prepare(repository, version, public_key, fingerprint, output, javap, gpg, artifacts=("ktoast",)):
    require(tuple(artifacts) in (("ktoast",), ("ktoast", "ktoast-lifecycle")), "必须验收核心或完整双模块")
    if len(artifacts) == 1:
        return prepare_one(repository, version, public_key, fingerprint, output, javap, gpg)
    modules = {}
    payload = {}
    with tempfile.TemporaryDirectory(prefix="ktoast-modules-") as temporary:
        for artifact in artifacts:
            child = Path(temporary) / (artifact + ".zip")
            modules[artifact] = prepare_one(repository, version, public_key, fingerprint, child, javap, gpg, artifact)
            with ZipFile(child) as archive:
                payload.update({name: archive.read(name) for name in archive.namelist()})
        require(modules["ktoast"]["source"] == modules["ktoast-lifecycle"]["source"], "两模块源码状态发生变化")
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".zip", delete=False) as handle:
            merged = Path(handle.name)
        try:
            with ZipFile(merged, "w", ZIP_DEFLATED) as archive:
                for name, content in sorted(payload.items()):
                    archive.writestr(name, content)
            merged.replace(output)
        finally:
            merged.unlink(missing_ok=True)
    return {"modules": modules, "source": modules["ktoast"]["source"],
            "coordinate": modules["ktoast"]["coordinate"], "deployment": deployment_identity(version),
            "files": {name: {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                      for name, content in sorted(payload.items())},
            "bundle": {"file": output.name, "files": len(payload), "sha256": hashlib.sha256(output.read_bytes()).hexdigest()},
            "remoteValidation": "未执行 Central Portal 上传或服务端校验"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", nargs="+", choices=("ktoast", "ktoast-lifecycle"), default=["ktoast"])
    parser.add_argument("--repository", type=Path, default=ROOT / "build/staging-repository")
    parser.add_argument("--version", required=True)
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--javap", default="javap")
    parser.add_argument("--gpg", default="gpg")
    parser.add_argument("--rehearsal", action="store_true", help="仅测试密钥演练，输出文件名必须以 -TEST-KEY.zip 结尾")
    args = parser.parse_args()
    if args.rehearsal and not args.output.name.endswith("-TEST-KEY.zip"):
        parser.error("测试演练包必须使用 -TEST-KEY.zip 文件名")
    try:
        report = prepare(args.repository, args.version, args.public_key, args.fingerprint, args.output, args.javap, args.gpg, tuple(args.artifacts))
    except (ValueError, OSError, KeyError, TypeError, BadZipFile, ET.ParseError, subprocess.CalledProcessError) as error:
        raise SystemExit("发布包生成失败: " + str(error))
    report["rehearsal"] = args.rehearsal
    report_path = args.output.with_suffix(".report.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print("本地签名、兼容和发布包检查通过:", args.output)
    print("核验报告:", report_path)
    print("未执行远程上传；正式发布前须确认发布密钥与授权。")
    if args.rehearsal:
        print("本包使用测试密钥，仅供本地演练，禁止正式上传。")


if __name__ == "__main__":
    main()
