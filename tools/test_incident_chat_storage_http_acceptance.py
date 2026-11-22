#!/usr/bin/env python3
"""Authenticated, synthetic-only HTTP acceptance test for TACResQ incident image Storage.

This intentionally creates one synthetic image message and does not delete it.
Run only against the dedicated non-production TACResQ-Storage-Test project.
No service-role key is accepted or needed.
"""

from __future__ import annotations

import base64
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


PROJECT_REF = "kyomsyprjdseaghdxhdr"
BUCKET = "incident-chat-attachments"
MIME = "image/png"
# A valid 1x1 transparent PNG. It contains no user data.
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/"
    "lWQAAAAASUVORK5CYII="
)


class AcceptanceFailure(RuntimeError):
    pass


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise AcceptanceFailure(f"Required environment variable is missing: {name}")
    return value


def request(
    base_url: str,
    token: str,
    anon_key: str,
    method: str,
    path: str,
    *,
    body: bytes | None = None,
    content_type: str | None = None,
) -> tuple[int, bytes]:
    headers = {
        "apikey": anon_key,
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
    }
    if content_type:
        headers["Content-Type"] = content_type
    if method == "POST" and path.startswith(f"/storage/v1/object/{BUCKET}/"):
        headers["x-upsert"] = "false"
    req = urllib.request.Request(
        base_url + path,
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        # Do not print response bodies: gateway errors can contain request details.
        return error.code, error.read()


def post_rpc(
    base_url: str,
    token: str,
    anon_key: str,
    name: str,
    payload: dict[str, object],
) -> dict[str, object]:
    status, raw = request(
        base_url,
        token,
        anon_key,
        "POST",
        f"/rest/v1/rpc/{name}",
        body=json.dumps(payload).encode("utf-8"),
        content_type="application/json",
    )
    if status < 200 or status >= 300:
        raise AcceptanceFailure(f"{name} failed with HTTP {status}")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise AcceptanceFailure(f"{name} returned invalid JSON") from error
    if not isinstance(value, dict):
        raise AcceptanceFailure(f"{name} returned an unexpected response shape")
    return value


def main() -> int:
    if os.environ.get("TACRESQ_ALLOW_REMOTE_STORAGE_TEST") != "1":
        raise AcceptanceFailure(
            "Set TACRESQ_ALLOW_REMOTE_STORAGE_TEST=1 only after confirming this is the "
            "dedicated synthetic test project."
        )

    base_url = required("SUPABASE_URL").rstrip("/")
    project_ref = required("TACRESQ_STORAGE_TEST_PROJECT_REF")
    if project_ref != PROJECT_REF:
        raise AcceptanceFailure(f"This test is restricted to project ref {PROJECT_REF}")
    expected_url = f"https://{PROJECT_REF}.supabase.co"
    if base_url != expected_url:
        raise AcceptanceFailure(f"SUPABASE_URL must exactly equal {expected_url}")

    anon_key = required("SUPABASE_ANON_KEY")
    token = required("SUPABASE_ACCESS_TOKEN")
    incident_id = int(required("TACRESQ_TEST_INCIDENT_ID"))
    if incident_id < 1:
        raise AcceptanceFailure("TACRESQ_TEST_INCIDENT_ID must be a positive integer")

    client_message_id = str(uuid.uuid4())
    reservation = post_rpc(
        base_url,
        token,
        anon_key,
        "api_reserve_incident_image_upload",
        {
            "p_incident_id": incident_id,
            "p_mime_type": MIME,
            "p_size_bytes": len(PNG_BYTES),
            "p_client_message_id": client_message_id,
        },
    )
    object_path = reservation.get("object_path")
    if not isinstance(object_path, str) or not object_path:
        raise AcceptanceFailure("Reservation RPC did not return an object_path")
    if reservation.get("consumed") is not False:
        raise AcceptanceFailure("A new reservation must be unconsumed")

    encoded_path = urllib.parse.quote(object_path, safe="/")
    upload_status, _ = request(
        base_url,
        token,
        anon_key,
        "POST",
        f"/storage/v1/object/{BUCKET}/{encoded_path}",
        body=PNG_BYTES,
        content_type=MIME,
    )
    if upload_status < 200 or upload_status >= 300:
        raise AcceptanceFailure(f"Binary Storage upload failed with HTTP {upload_status}")
    print("PASS: authenticated binary upload accepted")

    finalize_payload = {
        "p_incident_id": incident_id,
        "p_message": "Synthetic TACResQ Storage acceptance test",
        "p_attachment_path": object_path,
        "p_attachment_mime_type": MIME,
        "p_attachment_size_bytes": len(PNG_BYTES),
        "p_client_message_id": client_message_id,
    }
    first_message = post_rpc(
        base_url, token, anon_key, "api_send_incident_image_message", finalize_payload
    )
    message_id = first_message.get("id")
    if message_id is None:
        raise AcceptanceFailure("Finalization did not return a message id")
    print("PASS: image-message finalization created a message")

    retried_message = post_rpc(
        base_url, token, anon_key, "api_send_incident_image_message", finalize_payload
    )
    if retried_message.get("id") != message_id:
        raise AcceptanceFailure("Duplicate finalization did not return the original message")
    print("PASS: duplicate finalization is idempotent")

    download_status, downloaded = request(
        base_url,
        token,
        anon_key,
        "GET",
        f"/storage/v1/object/{BUCKET}/{encoded_path}",
    )
    if download_status != 200 or downloaded != PNG_BYTES:
        raise AcceptanceFailure(
            f"Authenticated Storage download/content verification failed (HTTP {download_status})"
        )
    print("PASS: authenticated participant download returned exact bytes")

    unrelated_token = os.environ.get("TACRESQ_UNRELATED_ACCESS_TOKEN", "").strip()
    if unrelated_token:
        denied_status, _ = request(
            base_url,
            unrelated_token,
            anon_key,
            "GET",
            f"/storage/v1/object/{BUCKET}/{encoded_path}",
        )
        if 200 <= denied_status < 300:
            raise AcceptanceFailure("Unrelated account unexpectedly downloaded the object")
        print(f"PASS: unrelated account download denied (HTTP {denied_status})")
    else:
        print("SKIP: unrelated-account denial (set TACRESQ_UNRELATED_ACCESS_TOKEN)")

    print(
        "NOTE: one synthetic message and one private Storage object were created; "
        "this test intentionally performs no deletion."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AcceptanceFailure, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
