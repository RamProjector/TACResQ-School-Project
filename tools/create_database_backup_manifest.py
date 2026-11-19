#!/usr/bin/env python3
"""Create or verify an integrity manifest for Supabase logical dump components.

This utility is offline-only. It does not connect to Postgres, inspect SQL
semantics, encrypt files, or upload backups. Dump contents and manifests are
sensitive and must stay out of Git and ordinary CI artifacts.
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

COMPONENTS = ("schema.sql", "data.sql", "roles.sql")
FORMAT = "tacresq-postgres-backup-manifest-v1"


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def collect_components(directory: Path) -> list[dict[str, Any]]:
    if not directory.is_dir():
        raise ValueError("backup directory must exist and be a directory")
    entries = []
    for filename in COMPONENTS:
        path = directory / filename
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"required regular dump component missing: {filename}")
        size = path.stat().st_size
        if size == 0:
            raise ValueError(f"dump component is empty: {filename}")
        with path.open("rb") as stream:
            if not stream.read(1).strip():
                raise ValueError(f"dump component contains only whitespace: {filename}")
        entries.append({"path": filename, "size_bytes": size, "sha256": digest_file(path)})
    return entries


def create_manifest(directory: Path, project_ref: str, commit: str) -> dict[str, Any]:
    if not re.fullmatch(r"[a-z0-9]{20}", project_ref):
        raise ValueError("project ref must be a 20-character lowercase alphanumeric ref")
    if not re.fullmatch(r"[0-9a-f]{7,40}", commit):
        raise ValueError("source commit must be a hexadecimal Git commit SHA")
    components = collect_components(directory)
    return {
        "format": FORMAT,
        "project_ref": project_ref,
        "source_commit": commit,
        "component_count": len(components),
        "total_size_bytes": sum(item["size_bytes"] for item in components),
        "components": components,
    }


def write_manifest(manifest: dict[str, Any], destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    fd, temporary = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    except Exception:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def verify_manifest(directory: Path, manifest_path: Path) -> tuple[bool, list[str]]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("format") != FORMAT:
        raise ValueError("unsupported database backup manifest format")
    expected = manifest.get("components")
    if not isinstance(expected, list):
        raise ValueError("manifest components must be a list")
    expected_by_name: dict[str, dict[str, Any]] = {}
    for item in expected:
        if not isinstance(item, dict):
            raise ValueError("manifest component must be an object")
        name, size, digest = item.get("path"), item.get("size_bytes"), item.get("sha256")
        if name not in COMPONENTS or name in expected_by_name:
            raise ValueError("manifest has an unknown or duplicate component")
        if not isinstance(size, int) or isinstance(size, bool) or size <= 0:
            raise ValueError(f"invalid size for component: {name}")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"invalid SHA-256 for component: {name}")
        expected_by_name[name] = item
    if set(expected_by_name) != set(COMPONENTS):
        raise ValueError("manifest must contain schema.sql, data.sql, and roles.sql")
    if manifest.get("component_count") != len(COMPONENTS):
        raise ValueError("manifest component_count is inconsistent")
    if manifest.get("total_size_bytes") != sum(x["size_bytes"] for x in expected_by_name.values()):
        raise ValueError("manifest total_size_bytes is inconsistent")

    actual = collect_components(directory)
    errors = []
    for item in actual:
        if expected_by_name[item["path"]] != item:
            errors.append(f"component integrity mismatch: {item['path']}")
    return not errors, errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--project-ref")
    parser.add_argument("--commit")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if args.verify:
        ok, errors = verify_manifest(args.directory, args.verify)
        if ok:
            print("Postgres backup component manifest verified.")
            return 0
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    if not args.project_ref or not args.commit or not args.output:
        parser.error("--project-ref, --commit, and --output are required when creating a manifest")
    manifest = create_manifest(args.directory, args.project_ref, args.commit)
    write_manifest(manifest, args.output)
    print(f"Manifest created for {manifest['component_count']} components ({manifest['total_size_bytes']} bytes).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
