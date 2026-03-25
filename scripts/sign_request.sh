#!/usr/bin/env bash
set -euo pipefail

METHOD="${1:-POST}"
PATH_ONLY="${2:-/messages/sms}"
BODY_FILE="${3:-/tmp/body.json}"
API_KEY="${API_KEY:-}"
API_SECRET="${API_SECRET:-}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

if [[ -z "${API_KEY}" || -z "${API_SECRET}" ]]; then
  echo "Set API_KEY and API_SECRET env vars"
  exit 1
fi

TIMESTAMP="$(date +%s)"
NONCE="$(openssl rand -hex 12)"
BODY_HASH="$(openssl dgst -sha256 -r "${BODY_FILE}" | awk '{print $1}')"
PAYLOAD="${METHOD}"$'\n'"${PATH_ONLY}"$'\n'"${TIMESTAMP}"$'\n'"${NONCE}"$'\n'"${BODY_HASH}"
SIGNATURE="$(printf "%s" "${PAYLOAD}" | openssl dgst -sha256 -hmac "${API_SECRET}" -r | awk '{print $1}')"

curl --location "${BASE_URL}${PATH_ONLY}" \
  --header "Content-Type: application/json" \
  --header "X-API-KEY: ${API_KEY}" \
  --header "X-TIMESTAMP: ${TIMESTAMP}" \
  --header "X-NONCE: ${NONCE}" \
  --header "X-SIGNATURE: ${SIGNATURE}" \
  --data "@${BODY_FILE}"
