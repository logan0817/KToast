"""两个真实模块必须同时完整验收；夹具只测文件协议，不冒充签名或 Portal。"""

import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_central_bundle import GROUP_PATH, prepare
from verify_central_staging import verify as verify_staging
from verify_central_deployment import verify as verify_deployment


class DualPublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="ktoast-dual-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repository = self.root / "repository"
        for artifact in ("ktoast", "ktoast-lifecycle"):
            directory = self.repository / GROUP_PATH.parent / artifact / "1.2.0"
            directory.mkdir(parents=True)
            for suffix in (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module"):
                name = artifact + "-1.2.0" + suffix
                content = ("fixture " + name).encode()
                (directory / name).write_bytes(content)
                (directory / (name + ".asc")).write_bytes(b"-----BEGIN PGP SIGNATURE-----")
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    (directory / (name + "." + algorithm)).write_text(hashlib.new(algorithm, content).hexdigest())
        self.bundle = self.root / "bundle.zip"
        self.report_file = self.root / "report.json"
        with patch("prepare_central_bundle.verify_signatures", return_value={}), \
                patch("prepare_central_bundle.check_pom"), \
                patch("prepare_central_bundle.inspect", side_effect=lambda *args, **kwargs: {
                    "coordinate": {"group": "io.github.logan0817", "module": kwargs["artifact"], "version": "1.2.0"}}):
            self.report = prepare(self.repository, "1.2.0", self.root / "key.asc", "A" * 40,
                                  self.bundle, "javap", "gpg", ("ktoast", "ktoast-lifecycle"))
        self.report["source"] = {"commit": "a" * 40, "dirty": False}
        self.report["rehearsal"] = False
        for module in self.report["modules"].values(): module["source"] = self.report["source"]
        self.staging = self.root / "build/publish/staging/test-id"
        shutil.copytree(self.repository, self.staging)
        self.downloads = self.root / "downloads"
        self.downloads.mkdir()
        for path in self.repository.rglob("*"):
            if path.is_file(): shutil.copy2(path, self.downloads / path.name)
        self.status = {"deploymentId": "fixture-id", "deploymentName": "io.github.logan0817-KToast-1.2.0",
                       "deploymentState": "VALIDATED", "errors": {},
                       "purls": ["pkg:maven/io.github.logan0817/" + artifact + "@1.2.0?type=aar"
                                 for artifact in self.report["modules"]]}
        self.addCleanup(patch.stopall)
        patch("verify_central_staging.check_source", return_value=self.report["source"]).start()
        patch("verify_central_staging.verify_signatures", return_value={}).start()
        patch("verify_central_deployment.verify_signatures", return_value={}).start()

    def staging_check(self, normalize=False):
        self.report_file.write_text(json.dumps(self.report))
        return verify_staging(self.staging, self.bundle, self.report_file, "1.2.0", self.root / "key.asc",
                              "A" * 40, root=self.root, normalize_signature_checksums=normalize)

    def remote_check(self):
        self.report_file.write_text(json.dumps(self.report))
        status = self.root / "status.json"
        status.write_text(json.dumps(self.status))
        return verify_deployment(self.bundle, self.report_file, status, "fixture-id", self.downloads,
                                 self.root / "key.asc", "A" * 40, "gpg")

    def test_complete_dual_bundle_staging_and_remote_protocol(self):
        self.assertEqual(60, self.report["bundle"]["files"])
        self.assertEqual(60, len(self.staging_check()["files"]))
        self.assertEqual(60, len(self.remote_check()["files"]))

    def test_missing_lifecycle_file_and_unknown_third_module_are_rejected(self):
        path = self.staging / GROUP_PATH.parent / "ktoast-lifecycle/1.2.0/ktoast-lifecycle-1.2.0.aar"
        content = path.read_bytes()
        path.unlink()
        with self.assertRaisesRegex(ValueError, "缺失"): self.staging_check()
        path.write_bytes(content)
        (self.staging / "unexpected.aar").write_bytes(b"unknown")
        with self.assertRaisesRegex(ValueError, "非预期"): self.staging_check()

    def test_lifecycle_tampering_and_different_source_are_rejected(self):
        path = self.staging / GROUP_PATH.parent / "ktoast-lifecycle/1.2.0/ktoast-lifecycle-1.2.0.aar"
        original = path.read_bytes()
        path.write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "不一致"): self.staging_check()
        path.write_bytes(original)
        self.report["modules"]["ktoast-lifecycle"]["source"] = {"commit": "b" * 40, "dirty": False}
        with self.assertRaisesRegex(ValueError, "源码"): self.staging_check()

    def test_partial_portal_coordinates_and_bad_download_are_rejected(self):
        self.status["purls"].pop()
        with self.assertRaisesRegex(ValueError, "坐标"): self.remote_check()
        self.status["purls"].append("pkg:maven/io.github.logan0817/ktoast-lifecycle@1.2.0?type=aar")
        (self.downloads / "ktoast-lifecycle-1.2.0.aar").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "远端文件"): self.remote_check()

    def test_signature_checksum_normalization_is_atomic_across_modules(self):
        derived = []
        for path in self.staging.rglob("*.asc"):
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = Path(str(path) + "." + algorithm)
                checksum.write_text(hashlib.new(algorithm, path.read_bytes()).hexdigest())
                derived.append(checksum)
        original = derived[-1].read_text()
        derived[-1].write_text("bad")
        with self.assertRaisesRegex(ValueError, "签名派生校验和错误"): self.staging_check(True)
        self.assertTrue(all(path.exists() for path in derived))
        derived[-1].write_text(original)
        self.assertEqual(40, len(self.staging_check(True)["normalizedSignatureChecksums"]))
        self.assertFalse(any(path.exists() for path in derived))

    def test_rehearsal_and_missing_module_are_rejected(self):
        self.report["rehearsal"] = True
        with self.assertRaisesRegex(ValueError, "演练"): self.staging_check()
        self.report["rehearsal"] = False
        del self.report["modules"]["ktoast-lifecycle"]
        with self.assertRaises(ValueError): self.staging_check()


if __name__ == "__main__": unittest.main()
