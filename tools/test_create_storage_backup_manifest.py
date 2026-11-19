import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

MODULE_PATH = Path(__file__).with_name("create_storage_backup_manifest.py")
SPEC = importlib.util.spec_from_file_location("storage_manifest", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class StorageBackupManifestTests(unittest.TestCase):
    def test_manifest_is_sorted_and_verifies_content(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "bucket"
            root.mkdir()
            (root / "z.txt").write_text("last", encoding="utf-8")
            (root / "a.txt").write_text("first", encoding="utf-8")
            manifest = MODULE.build_manifest(root, "incident-chat-attachments")
            self.assertEqual([x["path"] for x in manifest["objects"]], ["a.txt", "z.txt"])
            self.assertEqual(manifest["object_count"], 2)
            output = Path(temp) / "manifest.json"
            MODULE.write_manifest(manifest, output)
            self.assertEqual(MODULE.verify_manifest(root, output), (True, []))

    def test_verification_detects_changed_and_unexpected_objects(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "bucket"
            root.mkdir()
            target = root / "photo.png"
            target.write_bytes(b"original")
            output = Path(temp) / "manifest.json"
            MODULE.write_manifest(MODULE.build_manifest(root, "incident-chat-attachments"), output)
            target.write_bytes(b"changed")
            (root / "extra.png").write_bytes(b"extra")
            ok, errors = MODULE.verify_manifest(root, output)
            self.assertFalse(ok)
            self.assertTrue(any("content mismatch: photo.png" in e for e in errors))
            self.assertTrue(any("unexpected object: extra.png" in e for e in errors))

    def test_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "bucket"
            root.mkdir()
            outside = Path(temp) / "outside.txt"
            outside.write_text("secret", encoding="utf-8")
            (root / "linked.txt").symlink_to(outside)
            with self.assertRaises(ValueError):
                MODULE.build_manifest(root, "incident-chat-attachments")

    def test_verifier_rejects_path_traversal_and_inconsistent_totals(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "bucket"
            root.mkdir()
            (root / "safe.txt").write_text("safe", encoding="utf-8")
            manifest = MODULE.build_manifest(root, "incident-chat-attachments")
            output = Path(temp) / "manifest.json"
            manifest["objects"][0]["path"] = "../outside.txt"
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.build_manifest(root, "incident-chat-attachments")
            manifest["total_size_bytes"] += 1
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

    def test_verifier_rejects_non_object_and_noncanonical_manifest_values(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "bucket"
            root.mkdir()
            (root / "safe.txt").write_text("safe", encoding="utf-8")
            output = Path(temp) / "manifest.json"

            output.write_text("[]", encoding="utf-8")
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.build_manifest(root, "incident-chat-attachments")
            manifest["objects"][0]["path"] = "folder//safe.txt"
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.build_manifest(root, "incident-chat-attachments")
            manifest["object_count"] = True
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

            manifest = MODULE.build_manifest(root, "incident-chat-attachments")
            manifest["total_size_bytes"] = True
            MODULE.write_manifest(manifest, output)
            with self.assertRaises(ValueError):
                MODULE.verify_manifest(root, output)

    def test_rejects_symlink_backup_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "real-bucket"
            root.mkdir()
            (root / "object.txt").write_text("object", encoding="utf-8")
            link = Path(temp) / "linked-bucket"
            link.symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError):
                MODULE.collect_entries(link)

    def test_rejects_bucket_paths(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(ValueError):
                MODULE.build_manifest(root, "../other")


if __name__ == "__main__":
    unittest.main()
