"""上传前暂存验收边界；假签名仅测试布局，真实公钥验签由本地正式包覆盖。"""

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_central_bundle import GROUP_PATH, deployment_identity
from verify_central_staging import check_source, verify


class StagingValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kt-stage-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.staging = self.root / "build/publish/staging/fixture-id"
        self.directory = self.staging / GROUP_PATH / "1.1.0"
        self.directory.mkdir(parents=True)
        self.bundle = self.root / "bundle.zip"
        self.report_file = self.root / "report.json"
        self.source = {"commit": "a" * 40, "dirty": False}
        self.payload = {}
        for suffix in (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module"):
            name = "ktoast-1.1.0" + suffix
            content = ("fixture " + name).encode()
            self.payload[name] = content
            self.payload[name + ".asc"] = b"fixture signature"
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                self.payload[name + "." + algorithm] = hashlib.new(algorithm, content).hexdigest().encode()
        for name, content in self.payload.items():
            (self.directory / name).write_bytes(content)
        self.report = {"source": self.source, "rehearsal": False,
                       "coordinate": {"group": "io.github.logan0817", "module": "ktoast", "version": "1.1.0",
                                      "attributes": {"org.gradle.status": "release"}},
                       "deployment": deployment_identity("1.1.0")}
        self.write_bundle()
        self.addCleanup(patch.stopall)
        patch("verify_central_staging.check_source", return_value=self.source).start()
        self.signatures = patch("verify_central_staging.verify_signatures", return_value={}).start()

    def write_bundle(self):
        with ZipFile(self.bundle, "w") as archive:
            for name, content in self.payload.items():
                archive.writestr((GROUP_PATH / "1.1.0" / name).as_posix(), content)
        self.report["bundle"] = {"sha256": hashlib.sha256(self.bundle.read_bytes()).hexdigest(), "files": 30}
        self.report["files"] = {name: {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                                for name, content in self.payload.items()}
        self.save_report()

    def save_report(self):
        self.report_file.write_text(json.dumps(self.report), encoding="utf-8")

    def verify(self, normalize=False):
        return verify(self.staging, self.bundle, self.report_file, "1.1.0", self.root / "public.asc", "A" * 40,
                      root=self.root, normalize_signature_checksums=normalize)

    def add_signature_checksums(self):
        paths = []
        for name, content in self.payload.items():
            if name.endswith(".asc"):
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    path = self.directory / (name + "." + algorithm)
                    path.write_text(hashlib.new(algorithm, content).hexdigest())
                    paths.append(path)
        return paths

    def test_signature_checksums_require_explicit_normalization(self):
        paths = self.add_signature_checksums()
        with self.assertRaisesRegex(ValueError, "非预期"):
            self.verify()
        self.assertTrue(all(path.is_file() for path in paths))

    def test_verified_signature_checksums_removed_only_from_plugin_stage(self):
        paths = self.add_signature_checksums()
        original_bundle = self.bundle.read_bytes()
        result = self.verify(normalize=True)
        self.assertEqual(20, len(result["normalizedSignatureChecksums"]))
        self.assertFalse(any(path.exists() for path in paths))
        self.assertEqual(30, len(list(self.directory.iterdir())))
        self.assertEqual(original_bundle, self.bundle.read_bytes())

    def test_bad_derived_checksum_preserves_all_files(self):
        paths = self.add_signature_checksums()
        paths[-1].write_text("invalid checksum")
        with self.assertRaisesRegex(ValueError, "签名派生校验和错误"):
            self.verify(normalize=True)
        self.assertTrue(all(path.is_file() for path in paths))

    def test_partial_derived_checksums_rejected_without_mutation(self):
        paths = self.add_signature_checksums()
        paths[-1].unlink()
        with self.assertRaisesRegex(ValueError, "校验和不完整"):
            self.verify(normalize=True)
        self.assertTrue(all(path.is_file() for path in paths[:-1]))

    def test_signature_failure_preserves_derived_checksums(self):
        paths = self.add_signature_checksums()
        self.signatures.side_effect = ValueError("签名验证失败")
        with self.assertRaisesRegex(ValueError, "签名验证失败"):
            self.verify(normalize=True)
        self.assertTrue(all(path.is_file() for path in paths))

    def test_normalization_cannot_modify_other_repository(self):
        paths = self.add_signature_checksums()
        original = self.staging
        self.staging = self.root / "other-repository"
        original.rename(self.staging)
        with self.assertRaisesRegex(ValueError, "临时暂存目录"):
            self.verify(normalize=True)
        self.assertEqual(50, len(list((self.staging / GROUP_PATH / "1.1.0").iterdir())))

    def test_complete_payload_reaches_signature_verifier(self):
        result = self.verify()
        self.assertEqual(30, len(result["files"]))
        self.assertFalse(result["remoteUploaded"])
        self.signatures.assert_called_once()

    def test_exact_gradle_metadata_excluded_from_upload(self):
        path = self.staging / GROUP_PATH / "maven-metadata.xml"
        path.write_text("fixture metadata")
        path.with_name(path.name + ".sha1").write_text("fixture checksum")
        self.assertEqual(30, len(self.verify()["files"]))

    def test_unknown_file_rejected(self):
        (self.directory / "private-key.asc").write_text("fixture")
        with self.assertRaisesRegex(ValueError, "非预期"):
            self.verify()

    def test_unknown_metadata_name_rejected(self):
        (self.directory / "unexpected-maven-metadata.bin").write_text("fixture")
        with self.assertRaisesRegex(ValueError, "非预期"):
            self.verify()

    def test_missing_signature_rejected(self):
        (self.directory / "ktoast-1.1.0.aar.asc").unlink()
        with self.assertRaisesRegex(ValueError, "缺失"):
            self.verify()

    def test_modified_stage_rejected(self):
        (self.directory / "ktoast-1.1.0.aar").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "暂存与正式 ZIP"):
            self.verify()

    def test_symlink_rejected(self):
        path = self.directory / "ktoast-1.1.0.aar"
        path.unlink()
        path.symlink_to(self.bundle)
        with self.assertRaisesRegex(ValueError, "符号链接"):
            self.verify()

    def test_dirty_report_rejected(self):
        self.report["source"] = {"commit": "a" * 40, "dirty": True}
        self.save_report()
        with self.assertRaisesRegex(ValueError, "当前干净 HEAD"):
            self.verify()

    def test_stale_head_rejected(self):
        self.report["source"] = {"commit": "b" * 40, "dirty": False}
        self.save_report()
        with self.assertRaisesRegex(ValueError, "当前干净 HEAD"):
            self.verify()

    def test_rehearsal_rejected(self):
        self.report["rehearsal"] = True
        self.save_report()
        with self.assertRaisesRegex(ValueError, "演练"):
            self.verify()

    def test_coordinate_or_name_change_rejected(self):
        original = deepcopy(self.report)
        for section, key, value in (("coordinate", "module", "other"), ("deployment", "name", "other")):
            with self.subTest(section=section):
                self.report = deepcopy(original)
                self.report[section][key] = value
                self.save_report()
                with self.assertRaisesRegex(ValueError, "批次或坐标"):
                    self.verify()

    def test_changed_zip_rejected(self):
        self.bundle.write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "摘要"):
            self.verify()

    def test_report_file_digest_changed_rejected(self):
        self.report["files"]["ktoast-1.1.0.aar"]["sha256"] = "0" * 64
        self.save_report()
        with self.assertRaisesRegex(ValueError, "文件与报告"):
            self.verify()

    def test_bad_checksum_even_matching_stage_and_report_rejected(self):
        self.payload["ktoast-1.1.0.aar.sha1"] = b"0" * 40
        (self.directory / "ktoast-1.1.0.aar.sha1").write_bytes(b"0" * 40)
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "校验和错误"):
            self.verify()

    def test_signature_failure_not_swallowed(self):
        self.signatures.side_effect = ValueError("签名验证失败")
        with self.assertRaisesRegex(ValueError, "签名验证失败"):
            self.verify()

    def test_source_check_rejects_uncommitted_files(self):
        with patch("verify_central_staging.subprocess.run", side_effect=[
                subprocess.CompletedProcess([], 0, stdout="a" * 40),
                subprocess.CompletedProcess([], 0, stdout=" M build.gradle.kts")]):
            with self.assertRaisesRegex(ValueError, "干净源码"):
                check_source(self.root)


if __name__ == "__main__":
    unittest.main()
