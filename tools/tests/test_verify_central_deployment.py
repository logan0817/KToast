"""远端验收边界回归；样例只验证离线协议，不冒充 Portal 或真实签名结果。"""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_central_bundle import GROUP_PATH, deployment_identity
from verify_central_deployment import verify


class DeploymentVerificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ktoast-remote-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.downloads = self.root / "downloads"
        self.downloads.mkdir()
        self.bundle = self.root / "bundle.zip"
        self.report_file = self.root / "report.json"
        self.status_file = self.root / "status.json"
        self.names = ["ktoast-1.1.0" + suffix for suffix in
                      (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module")]
        payload = {}
        for name in self.names:
            content = ("fixture " + name).encode()
            payload[name] = content
            payload[name + ".asc"] = b"fixture signature"
            (self.downloads / name).write_bytes(content)
            (self.downloads / (name + ".asc")).write_bytes(payload[name + ".asc"])
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                payload[name + "." + algorithm] = hashlib.new(algorithm, content).hexdigest().encode()
        with ZipFile(self.bundle, "w") as archive:
            for name, content in payload.items():
                archive.writestr((GROUP_PATH / "1.1.0" / name).as_posix(), content)
        self.report = {
            "rehearsal": False,
            "coordinate": {"group": "io.github.logan0817", "module": "ktoast", "version": "1.1.0"},
            "deployment": deployment_identity("1.1.0"),
            "bundle": {"sha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest()},
            "files": {name: {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                      for name, content in payload.items()},
            "source": {"commit": "fixture-commit", "dirty": False},
        }
        self.status = {"deploymentId": "fixture-id", "deploymentName": "io.github.logan0817-KToast-1.1.0",
                       "deploymentState": "VALIDATED", "errors": {},
                       "purls": ["pkg:maven/io.github.logan0817/ktoast@1.1.0?type=aar"]}
        self.signature_check = patch("verify_central_deployment.verify_signatures", return_value={"fixture": True}).start()
        self.addCleanup(patch.stopall)

    def run_check(self):
        self.report_file.write_text(json.dumps(self.report), encoding="utf-8")
        self.status_file.write_text(json.dumps(self.status), encoding="utf-8")
        return verify(self.bundle, self.report_file, self.status_file, "fixture-id", self.downloads,
                      self.root / "public.asc", "A" * 40, "gpg")

    def test_complete_downloads_verified_without_claiming_release(self):
        result = self.run_check()
        self.assertEqual(10, len(result["files"]))
        self.assertFalse(result["formallyPublished"])
        self.signature_check.assert_called_once()

    def test_wrong_deployment_id_rejected(self):
        self.status["deploymentId"] = "another-id"
        with self.assertRaisesRegex(ValueError, "ID"):
            self.run_check()

    def test_wrong_project_name_rejected(self):
        self.status["deploymentName"] = "io.github.logan0817-OtherLibrary-1.1.0"
        with self.assertRaisesRegex(ValueError, "名称"):
            self.run_check()

    def test_pending_or_published_state_rejected(self):
        for state in ("PENDING", "FAILED", "PUBLISHED"):
            with self.subTest(state=state):
                self.status["deploymentState"] = state
                with self.assertRaisesRegex(ValueError, "VALIDATED"):
                    self.run_check()

    def test_errors_or_missing_errors_field_rejected(self):
        self.status["errors"] = {"fixture": ["invalid signature"]}
        with self.assertRaisesRegex(ValueError, "校验错误"):
            self.run_check()
        del self.status["errors"]
        with self.assertRaisesRegex(ValueError, "错误字段"):
            self.run_check()

    def test_other_project_or_empty_coordinates_rejected(self):
        for purls in ([], ["pkg:maven/io.github.logan0817/other-library@1.1.0"],
                      ["pkg:maven/io.github.logan0817/ktoast@1.0.5"]):
            with self.subTest(purls=purls):
                self.status["purls"] = purls
                with self.assertRaisesRegex(ValueError, "坐标"):
                    self.run_check()

    def test_rehearsal_report_rejected(self):
        self.report["rehearsal"] = True
        with self.assertRaisesRegex(ValueError, "演练"):
            self.run_check()

    def test_dirty_or_missing_source_identity_rejected(self):
        for source in ({"commit": "fixture", "dirty": True}, {}):
            with self.subTest(source=source):
                self.report["source"] = source
                with self.assertRaisesRegex(ValueError, "发布源码"):
                    self.run_check()

    def test_changed_zip_rejected(self):
        with ZipFile(self.bundle, "a") as archive:
            archive.writestr("unrelated.txt", "fixture")
        with self.assertRaisesRegex(ValueError, "ZIP"):
            self.run_check()

    def test_missing_downloaded_signature_rejected(self):
        (self.downloads / (self.names[0] + ".asc")).unlink()
        with self.assertRaisesRegex(ValueError, "缺失"):
            self.run_check()

    def test_stale_downloaded_artifact_rejected(self):
        (self.downloads / self.names[0]).write_bytes(b"old release")
        with self.assertRaisesRegex(ValueError, "不一致"):
            self.run_check()

    def test_downloaded_symlink_rejected(self):
        path = self.downloads / self.names[0]
        target = self.root / "outside.aar"
        target.write_bytes(path.read_bytes())
        path.unlink()
        path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "符号链接"):
            self.run_check()

    def test_optional_downloaded_checksum_must_match(self):
        (self.downloads / (self.names[0] + ".sha256")).write_text("wrong")
        with self.assertRaisesRegex(ValueError, "不一致"):
            self.run_check()

    def test_signature_failure_not_swallowed(self):
        self.signature_check.side_effect = ValueError("签名验证失败")
        with self.assertRaisesRegex(ValueError, "签名验证失败"):
            self.run_check()


if __name__ == "__main__":
    unittest.main()
