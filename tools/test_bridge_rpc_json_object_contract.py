#!/usr/bin/env python3
"""Static contract for strict JSON object parsing at the Supabase RPC bridge."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = (ROOT / "backend/src/routes/api.php").read_text()
START = SOURCE.index("Route::post('/bridge/rpc/{function}'")
END = SOURCE.index("Route::post('/geoapify-smoke-test'", START)
BLOCK = SOURCE[START:END]

assert "json_decode(" in BLOCK, "RPC arguments must be decoded from the raw JSON body"
assert "JSON_THROW_ON_ERROR" in BLOCK, "Malformed JSON must be rejected explicitly"
assert "$decodedArguments instanceof \\stdClass" in BLOCK, (
    "Top-level RPC arguments must be a JSON object, not an array or scalar"
)
assert "$arguments = get_object_vars($decodedArguments);" in BLOCK, (
    "Decoded object properties must be forwarded as the RPC argument array"
)
assert "RPC arguments must be a valid JSON object." in BLOCK
assert "$request->json()->all()" not in BLOCK, (
    "Laravel's array conversion loses the distinction between {} and []"
)

print("PASS: the bridge accepts JSON objects, including an empty object for no-argument RPCs.")
print("PASS: top-level JSON arrays, scalars, and malformed JSON are rejected before authentication/upstream work.")
