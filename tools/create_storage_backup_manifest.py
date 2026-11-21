#!/usr/bin/env python3
"""Create or verify a SHA-256 manifest for a downloaded Supabase Storage bucket.

The manifest contains object-relative paths, sizes, and hashes. Treat it as
sensitive metadata: object paths may contain user or incident identifiers.
This tool never connects to Supabase and never uploads, deletes, or encrypts data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import tempfile
from typing import Any


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def collect_entries(root: Path) -> list[dict[str, Any]]:
    if root.is_symlink() or not root.exists() or not root.is_dir():
        raise ValueError(f"backup directory must exist, be a real directory, and not be a symlink: {root}")
    entries: list[dict[str, Any]] = []
    for current, dirs, files in os.walk(root, followlinks=False):
        current_path = Path(current)
        for name in list(dirs):
            candidate = current_path / name
            if candidate.is_symlink():
                raise ValueError(f"symlink is not allowed in backup: {candidate.relative_to(root)}")
        for name in files:
            candidate = current_path / name
            if candidate.is_symlink():
                raise ValueError(f"symlink is not allowed in backup: {candidate.relative_to(root)}")
            if not candidate.is_file():
                raise ValueError(f"non-regular file in backup: {candidate.relative_to(root)}")
            relative = candidate.relative_to(root).as_posix()
            entries.append({
                "path": relative,
                "size_bytes": candidate.stat().st_size,
                "sha256": sha256_file(candidate),
            })
    return sorted(entries, key=lambda item: item["path"])


def build_manifest(root: Path, bucket: str) -> dict[str, Any]:
    if not bucket or "/" in bucket or "\\" in bucket or bucket in {".", ".."}:
        raise ValueError("bucket must be a non-empty bucket name, not a path")
    entries = collect_entries(root)
    return {
        "format": "tacresq-storage-backup-manifest-v1",
        "bucket": bucket,
        "object_count": len(entries),
        "total_size_bytes": sum(item["size_bytes"] for item in entries),
        "objects": entries,
    }


def write_manifest(manifest: dict[str, Any], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    fd, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_name, destination)
    except Exception:
        try:
            os.unlink(temporary_name)
        except FileNotFoundError:
            pass
        raise


def verify_manifest(root: Path, manifest_path: Path, expected_bucket: str | None = None) -> tuple[bool, list[str]]:
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("manifest root must be an object")
    if data.get("format") != "tacresq-storage-backup-manifest-v1":
        raise ValueError("unsupported manifest format")
    actual = collect_entries(root)
    expected = data.get("objects")
    if not isinstance(expected, list):
        raise ValueError("manifest objects must be a list")
    bucket = data.get("bucket")
    if not isinstance(bucket, str) or not bucket or "/" in bucket or chr(92) in bucket or bucket in {".", ".."}:
        raise ValueError("manifest bucket is invalid")
    if expected_bucket is not None and bucket != expected_bucket:
        raise ValueError("manifest bucket does not match the expected bucket")
    expected_by_path: dict[str, dict[str, Any]] = {}
    for item in expected:
        if not isinstance(item, dict):
            raise ValueError("manifest object entry must be an object")
        path = item.get("path")
        size = item.get("size_bytes")
        digest = item.get("sha256")
        relative = PurePosixPath(path) if isinstance(path, str) else None
        if (not path or relative is None or relative.is_absolute()
                or path != relative.as_posix()
                or any(part in {"", ".", ".."} for part in path.split("/"))
                or chr(92) in path or not isinstance(size, int) or isinstance(size, bool)
                or size < 0 or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise ValueError("manifest contains an invalid object entry")
        if path in expected_by_path:
            raise ValueError(f"manifest contains duplicate object path: {path}")
        expected_by_path[path] = item
    object_count = data.get("object_count")
    total_size = data.get("total_size_bytes")
    expected_total = sum(item["size_bytes"] for item in expected_by_path.values())
    if (not isinstance(object_count, int) or isinstance(object_count, bool)
            or object_count != len(expected_by_path)):
        raise ValueError("manifest object_count does not match its entries")
    if (not isinstance(total_size, int) or isinstance(total_size, bool)
            or total_size != expected_total):
        raise ValueError("manifest total_size_bytes does not match its entries")
    errors: list[str] = []
    actual_by_path = {item["path"]: item for item in actual}
    for path in sorted(set(expected_by_path) - set(actual_by_path)):
        errors.append(f"missing object: {path}")
    for path in sorted(set(actual_by_path) - set(expected_by_path)):
        errors.append(f"unexpected object: {path}")
    for path in sorted(set(expected_by_path) & set(actual_by_path)):
        if expected_by_path[path] != actual_by_path[path]:
            errors.append(f"content mismatch: {path}")
    if len(expected_by_path) != len(expected):
        errors.append("manifest contains duplicate or invalid object entries")
    return not errors, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path, help="local directory containing one downloaded bucket")
    parser.add_argument("--bucket", help="bucket name; required when creating a manifest and optionally pins verification to an expected bucket")
    parser.add_argument("--output", type=Path, help="manifest output path; required when creating a manifest")
    parser.add_argument("--verify", type=Path, help="verify the directory against an existing manifest")
    args = parser.parse_args()

    if args.verify:
        ok, errors = verify_manifest(args.directory, args.verify, args.bucket)
        if ok:
            print("Storage backup manifest verified.")
            return 0
        for error in errors:
            print(f"ERROR: {error}")
        return 1

    if not args.bucket or not args.output:
        parser.error("--bucket and --output are required unless --verify is used")
    manifest = build_manifest(args.directory, args.bucket)
    write_manifest(manifest, args.output)
    print(f"Manifest created: {manifest['object_count']} objects, {manifest['total_size_bytes']} bytes.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
