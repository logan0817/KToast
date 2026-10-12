#!/usr/bin/env python3
"""离线核对 Portal 状态快照和下载文件；不查询网络、不上传、不执行 release 或 drop。"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
from zipfile import BadZipFile, ZipFile

sys.dont_write_bytecode = True
from prepare_central_bundle import GROUP_PATH, deployment_identity, verify_signatures
from verify_candidate import require


def verify(bundle, bundle_report, status_file, deployment_id, downloads, public_key, fingerprint, gpg):
    report = json.loads(bundle_report.read_text(encoding="utf-8"))
    require(report.get("rehearsal") is False, "测试演练包或缺少正式标记的报告不能用于远端验收")
    source = report.get("source", {})
    require(isinstance(source.get("commit"), str) and bool(source["commit"]) and
            source.get("dirty") is False, "正式验收要求可追溯且干净的发布源码")
    version = report["coordinate"]["version"]
    identity = deployment_identity(version)
    require(report.get("deployment") == identity and
            report["coordinate"]["group"] == "io.github.logan0817" and
            report["coordinate"]["module"] == "ktoast", "本地报告的批次或坐标不一致")
    bundle_sha = hashlib.sha256(bundle.read_bytes()).hexdigest()
    require(bundle_sha == report["bundle"]["sha256"], "待验收 ZIP 与正式签名报告不一致")
    status = json.loads(status_file.read_text(encoding="utf-8"))
    require(bool(deployment_id.strip()) and status.get("deploymentId") == deployment_id,
            "Portal deployment ID 与指定批次不一致")
    require(status.get("deploymentName") == identity["name"], "Portal 批次名称不一致")
    require(status.get("deploymentState") == "VALIDATED", "Portal 批次尚未 VALIDATED 或已进入其他状态")
    require(status.get("errors") == {}, "Portal 存在校验错误或缺少错误字段")
    purl = "pkg:maven/io.github.logan0817/ktoast@" + version
    purls = status.get("purls")
    require(isinstance(purls, list) and bool(purls) and len(purls) == len(set(purls)) and
            set(purls) <= {purl, purl + "?type=aar"}, "Portal 包含其他坐标或缺少坐标")
    names = ["ktoast-" + version + suffix for suffix in
             (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module")]
    required = set(names + [name + ".asc" for name in names])
    allowed = required | {name + "." + algorithm for name in names
                          for algorithm in ("md5", "sha1", "sha256", "sha512")}
    relative = GROUP_PATH / version
    with ZipFile(bundle) as archive:
        entries = archive.namelist()
        expected = {(relative / name).as_posix() for name in allowed}
        require(len(entries) == len(set(entries)) and set(entries) == expected,
                "上传 ZIP 包含缺失、重复或非预期文件")
        require(set(report.get("files", {})) == allowed, "本地报告缺少逐文件校验值")
        checked = {}
        for name in sorted(allowed):
            content = archive.read((relative / name).as_posix())
            item = {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
            require(item == report["files"][name], "ZIP 与报告中的文件不一致: " + name)
            path = downloads / name
            # 下载目录由维护者按本次 ID 建立；必需十个制品/签名，校验文件如有也必须一致。
            if name in required or path.exists() or path.is_symlink():
                require(path.is_file() and not path.is_symlink(), "远端文件缺失或为符号链接: " + name)
                require(path.read_bytes() == content, "远端文件与上传包不一致: " + name)
                checked[name] = item
    signatures = verify_signatures(downloads, names, public_key, fingerprint, gpg)
    return {
        "verifiedAtUtc": datetime.now(timezone.utc).isoformat(),
        "source": source,
        "deployment": {"id": deployment_id, "name": identity["name"], "state": "VALIDATED",
                       "coordinate": identity["coordinate"]},
        "bundleSha256": bundle_sha,
        "statusSha256": hashlib.sha256(status_file.read_bytes()).hexdigest(),
        "files": checked, "signatures": signatures,
        "formallyPublished": False,
        "scope": "仅核对指定的本地 Portal 快照和下载字节；不认证快照来源或远端实时状态",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--bundle-report", type=Path, required=True)
    parser.add_argument("--status", type=Path, required=True)
    parser.add_argument("--deployment-id", required=True)
    parser.add_argument("--downloads", type=Path, required=True,
                        help="本次 ID 对应的十个制品/签名文件所在目录，文件名保持 Maven 原名")
    parser.add_argument("--public-key", type=Path, required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--gpg", default="gpg")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = verify(args.bundle, args.bundle_report, args.status, args.deployment_id,
                        args.downloads, args.public_key, args.fingerprint, args.gpg)
    except (ValueError, OSError, KeyError, TypeError, BadZipFile) as error:
        raise SystemExit("Portal 文件验收失败: " + str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print("Portal 快照与下载文件离线验收通过:", args.output)
    print("未查询远端实时状态，未执行正式发布。")


if __name__ == "__main__":
    main()
