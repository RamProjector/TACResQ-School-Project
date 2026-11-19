import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).with_name("create_database_backup_manifest.py")
SPEC = importlib.util.spec_from_file_location("database_manifest", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class DatabaseBackupManifestTests(unittest.TestCase):
    def make_dump_set(self, root):
        root.mkdir()
        for name, content in {
            "schema.sql": "-- schema\nCREATE TABLE example(id integer);\n",
            "data.sql": "-- data\nCOPY example (id) FROM stdin;\n1\n\\.\n",
            "roles.sql": "CREATE ROLE tacresq_app;\n",
        }.items():
            (root / name).write_text(content, encoding="utf-8")

    def test_creates_and_verifies_all_components(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            self.make_dump_set(root)
            manifest = MODULE.create_manifest(root, "kttguamhznoflzagfutr", "a" * 40)
            output = Path(temp) / "manifest.json"
            MODULE.write_manifest(manifest, output)
            self.assertEqual(manifest["component_count"], 3)
            self.assertEqual(MODULE.verify_manifest(root, output), (True, []))

    def test_detects_modified_dump(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            self.make_dump_set(root)
            output = Path(temp) / "manifest.json"
            MODULE.write_manifest(MODULE.create_manifest(root, "kttguamhznoflzagfutr", "b" * 40), output)
            (root / "data.sql").write_text("changed", encoding="utf-8")
            ok, errors = MODULE.verify_manifest(root, output)
            self.assertFalse(ok)
            self.assertTrue(any("data.sql" in error for error in errors))

    def test_requires_all_nonempty_components(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            root.mkdir()
            (root / "schema.sql").write_text("schema", encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.collect_components(root)

    def test_rejects_bad_project_ref_and_commit(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            self.make_dump_set(root)
            with self.assertRaises(ValueError):
                MODULE.create_manifest(root, "wrong", "c" * 40)
            with self.assertRaises(ValueError):
                MODULE.create_manifest(root, "kttguamhznoflzagfutr", "not-a-commit")


if __name__ == "__main__":
    unittest.main()
