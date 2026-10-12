#!/usr/bin/env python3
"""用真实 Gradle 配置验证签名开关和拒绝边界；不签名、不读取个人密钥环。"""

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
    fingerprint = "A" * 40
    cases = [
        ("unsigned-default", [], None, None),
        ("unsigned-gpg", ["-PsigningMode=gpg"], None, None),
        ("gpg-config-only", ["-PsignPublication=true", "-PsigningMode=gpg"], fingerprint, None),
        ("invalid-mode", ["-PsigningMode=invalid"], None, "signingMode must be memory or gpg"),
        ("invalid-switch", ["-PsignPublication=invalid"], None, "signPublication must be true or false"),
        ("memory-missing-key", ["-PsignPublication=true"], None,
         "Signing requires KTOAST_SIGNING_KEY and KTOAST_SIGNING_PASSWORD"),
        ("gpg-missing-fingerprint", ["-PsignPublication=true", "-PsigningMode=gpg"], None,
         "GPG signing requires a full KTOAST_SIGNING_FINGERPRINT"),
        ("gpg-short-fingerprint", ["-PsignPublication=true", "-PsigningMode=gpg"], "1234ABCD",
         "GPG signing requires a full KTOAST_SIGNING_FINGERPRINT"),
        ("gpg-conflicting-key", ["-PsignPublication=true", "-PsigningMode=gpg",
                                 "-Psigning.gnupg.keyName=" + "B" * 40], fingerprint,
         "signing.gnupg.keyName conflicts with KTOAST_SIGNING_FINGERPRINT"),
        ("gpg-gradle-passphrase", ["-PsignPublication=true", "-PsigningMode=gpg",
                                   "-Psigning.gnupg.passphrase=fixture"], fingerprint,
         "GPG signing requires GnuPG 2 and gpg-agent"),
        ("gpg-legacy", ["-PsignPublication=true", "-PsigningMode=gpg",
                        "-Psigning.gnupg.useLegacyGpg=true"], fingerprint,
         "GPG signing requires GnuPG 2 and gpg-agent"),
        ("gpg-configuration-cache", ["-PsignPublication=true", "-PsigningMode=gpg",
                                     "--configuration-cache"], fingerprint,
         "Signing must run with --no-configuration-cache"),
    ]
    checked = []
    with tempfile.TemporaryDirectory(prefix="kt-config-") as temporary:
        # 即使签名被意外开启，也只能选择空测试密钥环，不能访问维护者私钥。
        environment["GNUPGHOME"] = temporary
        for name, arguments, selected, expected_error in cases:
            current = dict(environment)
            if selected is not None:
                current["KTOAST_SIGNING_FINGERPRINT"] = selected
            command = launcher + [":library-android:help", "--console=plain", "--no-configuration-cache",
                                  "-PsignPublication=false", "-PsigningMode=memory",
                                  "-Psigning.gnupg.homeDir=" + temporary,
                                  "-Psigning.gnupg.keyName=" + (selected or fingerprint),
                                  "-Psigning.gnupg.useLegacyGpg=false"] + arguments
            result = subprocess.run(command, cwd=ROOT, env=current, capture_output=True, text=True,
                                    timeout=180)
            output = result.stdout + result.stderr
            if expected_error is None:
                valid = result.returncode == 0 and "BUILD SUCCESSFUL" in output
            else:
                valid = result.returncode != 0 and expected_error in output
            if not valid:
                raise ValueError("签名配置回归不符合预期: " + name + "（退出码 " + str(result.returncode) + "）")
            checked.append({"case": name, "passed": True, "expectedRejection": expected_error is not None})
            print("PASS:", name, flush=True)
    return {"cases": checked, "signingExecuted": False, "personalKeyringAccessed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path,
                        default=ROOT / "build/reports/signing-modes/configuration.json")
    parser.add_argument("launcher", nargs=argparse.REMAINDER,
                        help="在 -- 后给出 Gradle 启动命令，可使用 Android Studio 环境脚本及其固定参数")
    arguments = parser.parse_args()
    launcher = arguments.launcher
    if launcher and launcher[0] == "--":
        launcher = launcher[1:]
    if not launcher:
        parser.error("必须在 -- 后提供 Gradle 启动命令")
    try:
        report = verify(launcher)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error))
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    print("签名配置边界验证通过:", arguments.output)


if __name__ == "__main__":
    main()
