"""本地发布包边界回归；完整 PGP 路径由临时密钥演练验证。"""

import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_central_bundle import GROUP_PATH, check_pom, collect


class BundleValidationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ktoast-bundle-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.directory = self.root / GROUP_PATH / "1.1.0"
        self.directory.mkdir(parents=True)
        self.names = ["ktoast-1.1.0" + suffix for suffix in (".aar", "-sources.jar", "-javadoc.jar", ".pom", ".module")]
        for name in self.names:
            content = ("fixture " + name).encode()
            (self.directory / name).write_bytes(content)
            (self.directory / (name + ".asc")).write_bytes(b"-----BEGIN PGP SIGNATURE-----")
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                (self.directory / (name + "." + algorithm)).write_text(hashlib.new(algorithm, content).hexdigest())

    def test_collect_complete_layout(self):
        names, payload = collect(self.root, "1.1.0")
        self.assertEqual(self.names, names)
        self.assertEqual(30, len(payload))
        self.assertNotIn("maven-metadata.xml", payload)

    def test_snapshot_rejected(self):
        with self.assertRaisesRegex(ValueError, "非 SNAPSHOT"):
            collect(self.root, "1.1.0-SNAPSHOT")

    def test_version_path_traversal_rejected(self):
        with self.assertRaises(ValueError):
            collect(self.root, "../../secret")

    def test_missing_docs_rejected(self):
        (self.directory / "ktoast-1.1.0-javadoc.jar").unlink()
        with self.assertRaisesRegex(ValueError, "缺少真实产物"):
            collect(self.root, "1.1.0")

    def test_empty_artifact_rejected(self):
        (self.directory / self.names[0]).write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "真实产物"):
            collect(self.root, "1.1.0")

    def test_missing_signature_rejected(self):
        (self.directory / (self.names[0] + ".asc")).unlink()
        with self.assertRaisesRegex(ValueError, "缺少签名"):
            collect(self.root, "1.1.0")

    def test_invalid_signature_format_rejected(self):
        (self.directory / (self.names[0] + ".asc")).write_text("placeholder")
        with self.assertRaisesRegex(ValueError, "ASCII armor"):
            collect(self.root, "1.1.0")

    def test_changed_artifact_rejected(self):
        (self.directory / self.names[0]).write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "校验和"):
            collect(self.root, "1.1.0")

    def test_missing_checksum_rejected(self):
        (self.directory / (self.names[0] + ".sha1")).unlink()
        with self.assertRaisesRegex(ValueError, "校验文件"):
            collect(self.root, "1.1.0")

    def test_symbolic_link_rejected(self):
        path = self.directory / self.names[0]
        content = path.read_bytes()
        path.unlink()
        target = self.root / "outside.aar"
        target.write_bytes(content)
        path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "真实产物"):
            collect(self.root, "1.1.0")

    def test_pom_missing_scm_rejected(self):
        pom = self.root / "pom.xml"
        root = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
        for path in ("name", "description", "url", "licenses/license/name", "licenses/license/url",
                     "developers/developer/id", "developers/developer/name"):
            node = root
            for tag in path.split("/"):
                child = node.find(tag)
                node = child if child is not None else ET.SubElement(node, tag)
            node.text = "fixture"
        ET.ElementTree(root).write(pom)
        with self.assertRaisesRegex(ValueError, "scm"):
            check_pom(pom)


if __name__ == "__main__":
    unittest.main()
