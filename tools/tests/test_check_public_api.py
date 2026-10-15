"""接口门禁回归：不能以允许新增为由漏掉删除、改签名或额外公开类型。"""

from pathlib import Path
import sys
import unittest
import tempfile
from unittest.mock import patch
from zipfile import ZipFile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_public_api import compare_api, public_api


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

    def test_inventory_detects_new_public_types_and_excludes_internal_and_anonymous(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "Api.kt").write_text("package com.logan.ktoast\ninternal class Hidden\nprivate object Private\nclass Visible\n")
            names = ("Visible", "Visible$Companion", "NewPublicFacadeKt", "Hidden", "Hidden$Companion", "Private", "Visible$callback$1")
            jar = root / "classes.jar"
            with ZipFile(jar, "w") as archive:
                for name in names: archive.writestr("com/logan/ktoast/" + name + ".class", b"fixture")
            classes = root / "classes"
            for name in names:
                path = classes / "com/logan/ktoast" / (name + ".class")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"fixture")
            with patch("check_public_api.dump_class", side_effect=lambda javap, path, name: [{"declaration": "public class " + name + " {"}]):
                for classpath in (jar, classes):
                    actual = public_api("javap", classpath, source)
                    self.assertEqual({"com.logan.ktoast.Visible", "com.logan.ktoast.Visible$Companion", "com.logan.ktoast.NewPublicFacadeKt"}, set(actual))
                    self.assertTrue(compare_api(actual, {}, {}))


if __name__ == "__main__":
    unittest.main()
