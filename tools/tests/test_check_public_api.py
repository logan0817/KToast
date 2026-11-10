"""接口门禁回归：不能以允许新增为由漏掉删除、改签名或额外公开类型。"""

from pathlib import Path
import sys
import json
import struct
import unittest
import tempfile
from unittest.mock import patch
from zipfile import ZipFile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_public_api import ROOT, compare_api, public_api, synthetic_class


class PublicApiTest(unittest.TestCase):
    def setUp(self):
        self.old = {"Api": [{"declaration": "public void show();", "descriptor": "()V"}]}
        self.new = {"Api": [{"declaration": "public void cancel();", "descriptor": "()V"}]}

    def test_exact_approved_addition(self):
        actual = {"Api": self.old["Api"] + self.new["Api"]}
        self.assertEqual([], compare_api(actual, self.old, self.new))

    def test_removal_cannot_be_hidden_by_addition(self):
        self.assertTrue(compare_api(self.new, self.old, self.new))

    def test_changed_descriptor_is_rejected(self):
        actual = {"Api": [{**self.old["Api"][0], "descriptor": "(I)V"}]}
        self.assertTrue(compare_api(actual, self.old, {}))

    def test_unapproved_member_and_class_are_rejected(self):
        self.assertTrue(compare_api({"Api": self.old["Api"] + self.new["Api"]}, self.old, {}))
        self.assertTrue(compare_api({**self.old, "Unexpected": self.new["Api"]}, self.old, {}))

    def test_approved_new_class_and_missing_addition(self):
        addition = {"NewApi": self.new["Api"]}
        self.assertEqual([], compare_api({**self.old, **addition}, self.old, addition))
        self.assertTrue(compare_api(self.old, self.old, addition))

    def test_duplicate_allowance_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "重复"):
            compare_api(self.old, self.old, self.old)

    def test_released_120_baseline_is_exact_historical_increment(self):
        old = json.loads((ROOT / "docs/api/ktoast-1.1.0.json").read_text())["classes"]
        additions = json.loads((ROOT / "docs/api/ktoast-1.2.0-additions.json").read_text())["classes"]
        released = json.loads((ROOT / "docs/api/ktoast-1.2.0.json").read_text())
        self.assertEqual([], compare_api(released["classes"], old, additions))
        self.assertEqual("0b3b3d6979099d4ac86ed36b70da5a4981f04d71047bd31ca4c0f125c948cabf", released["aarSha256"])

    def test_only_synthetic_class_flags_are_filtered(self):
        # 常量池含 UTF8、long（占两槽）、Class 和 MethodHandle，验证偏移不会误读成员标志。
        pool = b'\x01\x00\x03Api' + b'\x05' + bytes(8) + b'\x07\x00\x01' + b'\x0f\x01\x00\x04'
        header = b'\xca\xfe\xba\xbe\x00\x00\x00\x34' + struct.pack('>H', 6)
        self.assertFalse(synthetic_class(header + pool + struct.pack('>H', 0x0031)))
        self.assertTrue(synthetic_class(header + pool + struct.pack('>H', 0x1031)))

    def test_inventory_detects_new_public_types_and_excludes_internal_and_anonymous(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "Api.kt").write_text("package com.logan.ktoast\ninternal class Hidden\nprivate object Private\nclass Visible\n")
            names = ("Visible", "Visible$Companion", "NewPublicFacadeKt", "Hidden", "Hidden$Companion", "Private", "Visible$callback$1", "Visible$WhenMappings")
            content = lambda name: b'\xca\xfe\xba\xbe\x00\x00\x00\x34\x00\x01' + struct.pack('>H', 0x1031 if name.endswith('WhenMappings') else 0x0031)
            jar = root / "classes.jar"
            with ZipFile(jar, "w") as archive:
                for name in names: archive.writestr("com/logan/ktoast/" + name + ".class", content(name))
            classes = root / "classes"
            for name in names:
                path = classes / "com/logan/ktoast" / (name + ".class")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content(name))
            with patch("check_public_api.dump_class", side_effect=lambda javap, path, name: [{"declaration": "public class " + name + " {"}]):
                for classpath in (jar, classes):
                    actual = public_api("javap", classpath, source)
                    self.assertEqual({"com.logan.ktoast.Visible", "com.logan.ktoast.Visible$Companion", "com.logan.ktoast.NewPublicFacadeKt"}, set(actual))
                    self.assertTrue(compare_api(actual, {}, {}))


if __name__ == "__main__":
    unittest.main()
