#!/usr/bin/env bash
# Retry only recognizable transient Docker registry/network failures.
# Non-transient Compose/build failures stop immediately and preserve the original exit code.
set -Eeuo pipefail

MAX_ATTEMPTS="${DOCKER_COMPOSE_MAX_ATTEMPTS:-3}"
INITIAL_DELAY_SECONDS="${DOCKER_COMPOSE_RETRY_DELAY_SECONDS:-5}"

if ! [[ "$MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  echo "DOCKER_COMPOSE_MAX_ATTEMPTS must be a positive integer." >&2
  exit 2
fi

if ! [[ "$INITIAL_DELAY_SECONDS" =~ ^[0-9]+$ ]]; then
  echo "DOCKER_COMPOSE_RETRY_DELAY_SECONDS must be a non-negative integer." >&2
  exit 2
fi

TRANSIENT_PATTERN='502 Bad Gateway|503 Service Unavailable|504 Gateway Timeout|429 Too Many Requests|TLS handshake timeout|context deadline exceeded|temporary failure in name resolution|connection reset by peer|unexpected EOF|i/o timeout|no such host|network is unreachable|no route to host|net/http: request canceled'

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
  log_file="$(mktemp)"
  set +e
  docker compose up -d --build 2>&1 | tee "$log_file"
  compose_status="${PIPESTATUS[0]}"
  set -e

  if ((compose_status == 0)); then
    rm -f "$log_file"
    exit 0
  fi

  if ((attempt < MAX_ATTEMPTS)) && grep -Eiq "$TRANSIENT_PATTERN" "$log_file"; then
    delay_seconds=$((INITIAL_DELAY_SECONDS * attempt))
    echo "::warning::Transient Docker registry/network failure on attempt ${attempt}/${MAX_ATTEMPTS}; retrying in ${delay_seconds}s." >&2
    rm -f "$log_file"
    sleep "$delay_seconds"
    continue
  fi

  echo "::error::Docker Compose failed with exit code ${compose_status}; no safe transient retry remains." >&2
  rm -f "$log_file"
  exit "$compose_status"
done

# Defensive guard: the loop exits explicitly on success or failure.
echo "Docker Compose retry loop ended unexpectedly." >&2
exit 1
