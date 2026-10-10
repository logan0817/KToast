"""发布校验回归：错误变体不能因依赖并集或空产物列表而通过。"""

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_candidate import inspect_publication


class PublicationValidationTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="ktoast-publication-test-")
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        self.source_dir = self.root / "source"
        self.source_dir.mkdir()
        (self.source_dir / "KToast.kt").write_text("package com.logan.ktoast", encoding="utf-8")
        self.aar = self.root / "candidate.aar"
        self.aar.write_bytes(b"fixture AAR")
        self.sources = self.root / "sources.jar"
        self.javadoc = self.root / "javadoc.jar"
        with ZipFile(self.javadoc, "w") as archive:
            for name in (
                "index.html", "com.logan.ktoast/-k-toast/index.html",
                "com.logan.ktoast/-k-toast-scope/index.html",
                "com.logan.ktoast/-k-toast-scope-options/index.html",
                "com.logan.ktoast.config/-k-toast-config/index.html",
                "com.logan.ktoast.runtime/-toast-handle/index.html",
            ):
                archive.writestr(name, "<html>API fixture</html>")
        self.write_sources(b"package com.logan.ktoast")
        stdlib = {"group": "org.jetbrains.kotlin", "module": "kotlin-stdlib", "version": {"requires": "1.9.23"}}
        startup = {"group": "androidx.startup", "module": "startup-runtime", "version": {"requires": "1.1.1"}}
        self.module = {
            "component": {"group": "io.github.logan0817", "module": "ktoast", "version": "1.1.0-SNAPSHOT"},
            "variants": [
                self.variant("java-api", [stdlib], self.aar, "ktoast-1.1.0-SNAPSHOT.aar"),
                self.variant("java-runtime", [stdlib, startup], self.aar, "ktoast-1.1.0-SNAPSHOT.aar"),
                self.variant("java-runtime", [], self.sources, "ktoast-1.1.0-SNAPSHOT-sources.jar", sources=True),
            ],
        }
        self.pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
        for tag, value in (("groupId", "io.github.logan0817"), ("artifactId", "ktoast"),
                           ("version", "1.1.0-SNAPSHOT"), ("packaging", "aar")):
            ET.SubElement(self.pom, tag).text = value
        dependencies = ET.SubElement(self.pom, "dependencies")
        for entry, scope in ((stdlib, "compile"), (startup, "runtime")):
            node = ET.SubElement(dependencies, "dependency")
            for tag, value in (("groupId", entry["group"]), ("artifactId", entry["module"]),
                               ("version", entry["version"]["requires"]), ("scope", scope)):
                ET.SubElement(node, tag).text = value

    def write_sources(self, content):
        with ZipFile(self.sources, "w") as archive:
            archive.writestr("KToast.kt", content)

    def variant(self, usage, dependencies, artifact, name, sources=False):
        return {
            "attributes": {
                "org.gradle.category": "documentation" if sources else "library",
                "org.gradle.usage": usage,
                "org.gradle.dependency.bundling": "external",
                "org.gradle.docstype" if sources else "org.gradle.libraryelements": "sources" if sources else "aar",
            },
            "dependencies": dependencies,
            "files": [{"name": name, "url": name, "size": artifact.stat().st_size,
                       "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}],
        }

    def inspect(self):
        (self.root / "module.json").write_text(json.dumps(self.module), encoding="utf-8")
        ET.ElementTree(self.pom).write(self.root / "pom-default.xml", encoding="utf-8")
        return inspect_publication(self.aar, self.sources, self.root, self.source_dir, self.javadoc)

    def test_valid_publication(self):
        self.assertEqual(1, self.inspect()["sourceFiles"])

    def test_runtime_missing_stdlib_cannot_pass_via_api_union(self):
        self.module["variants"][1]["dependencies"].pop(0)
        with self.assertRaisesRegex(ValueError, "变体依赖"):
            self.inspect()

    def test_missing_api_aar_cannot_pass_empty_loop(self):
        self.module["variants"][0]["files"] = []
        with self.assertRaisesRegex(ValueError, "缺少"):
            self.inspect()

    def test_missing_runtime_variant(self):
        self.module["variants"].pop(1)
        with self.assertRaisesRegex(ValueError, "唯一发布变体"):
            self.inspect()

    def test_wrong_coordinate_even_when_pom_and_module_agree(self):
        self.module["component"]["group"] = "wrong.group"
        self.pom.find("groupId").text = "wrong.group"
        with self.assertRaisesRegex(ValueError, "坐标变化"):
            self.inspect()

    def test_wrong_packaging(self):
        self.pom.find("packaging").text = "jar"
        with self.assertRaisesRegex(ValueError, "包装"):
            self.inspect()

    def test_wrong_pom_runtime_scope(self):
        self.pom.find("dependencies").findall("dependency")[1].find("scope").text = "compile"
        with self.assertRaisesRegex(ValueError, "scope"):
            self.inspect()

    def test_wrong_artifact_checksum(self):
        self.module["variants"][1]["files"][0]["sha256"] = "wrong"
        with self.assertRaisesRegex(ValueError, "SHA256"):
            self.inspect()

    def test_missing_source_variant(self):
        self.module["variants"].pop()
        with self.assertRaisesRegex(ValueError, "唯一发布变体"):
            self.inspect()

    def test_missing_source_artifact(self):
        self.sources.unlink()
        with self.assertRaises(FileNotFoundError):
            self.inspect()

    def test_stale_sources_even_when_metadata_hash_matches(self):
        self.write_sources(b"stale source")
        item = self.module["variants"][2]["files"][0]
        item["size"] = self.sources.stat().st_size
        item["sha256"] = hashlib.sha256(self.sources.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, "源码包"):
            self.inspect()

    def test_unchecked_extra_variant(self):
        self.module["variants"].append({"attributes": {"org.gradle.category": "platform"}})
        with self.assertRaisesRegex(ValueError, "额外发布变体"):
            self.inspect()

    def test_placeholder_docs_rejected(self):
        with ZipFile(self.javadoc, "w") as archive:
            archive.writestr("README.txt", "placeholder")
        with self.assertRaisesRegex(ValueError, "文档"):
            self.inspect()

    def test_docs_missing_scope_rejected(self):
        with ZipFile(self.javadoc) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()
                       if "-k-toast-scope/index.html" not in name}
        with ZipFile(self.javadoc, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        with self.assertRaisesRegex(ValueError, "公开页面"):
            self.inspect()

    def test_docs_internal_dispatcher_rejected(self):
        with ZipFile(self.javadoc, "a") as archive:
            archive.writestr("com.logan.ktoast.runtime/-toast-dispatcher/index.html", "<html>internal</html>")
        with self.assertRaisesRegex(ValueError, "internal"):
            self.inspect()


if __name__ == "__main__":
    unittest.main()
