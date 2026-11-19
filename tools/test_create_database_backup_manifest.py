import importlib.util
import subprocess
import sys
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

    def test_rejects_non_object_manifest_and_invalid_component_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            self.make_dump_set(root)
            output = Path(temp) / "manifest.json"

            output.write_text("[]", encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.create_manifest(root, "kttguamhznoflzagfutr", "d" * 40)
            manifest["components"][0]["path"] = []
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.create_manifest(root, "kttguamhznoflzagfutr", "e" * 40)
            manifest["component_count"] = True
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.create_manifest(root, "kttguamhznoflzagfutr", "f" * 40)
            manifest["total_size_bytes"] = True
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

    def test_rejects_symlink_backup_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "real-dumps"
            self.make_dump_set(root)
            link = Path(temp) / "linked-dumps"
            link.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError):
                MODULE.collect_components(link)

    def test_verifier_can_pin_manifest_to_expected_project(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            self.make_dump_set(root)
            output = Path(temp) / "manifest.json"
            MODULE.write_manifest(
                MODULE.create_manifest(root, "kttguamhznoflzagfutr", "a" * 40),
                output,
            )

            self.assertEqual(
                MODULE.verify_manifest(root, output, "kttguamhznoflzagfutr"),
                (True, []),
            )
            with self.assertRaisesRegex(ValueError, "expected project"):
                MODULE.verify_manifest(root, output, "bttguamhznoflzagfutr")

    def test_cli_enforces_expected_project_during_verification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "dumps"
            self.make_dump_set(root)
            output = Path(temp) / "manifest.json"
            MODULE.write_manifest(
                MODULE.create_manifest(root, "kttguamhznoflzagfutr", "a" * 40),
                output,
            )

            command = [
                sys.executable, str(MODULE_PATH),
                "--directory", str(root),
                "--project-ref", "bttguamhznoflzagfutr",
                "--verify", str(output),
            ]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)

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
