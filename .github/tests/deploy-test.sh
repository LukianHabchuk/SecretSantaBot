#!/usr/bin/env bash
set -eu

root=$(cd "$(dirname "$0")/../.." && pwd)
tmp=$(mktemp -d)
trap 'rm -f "$tmp/bin/docker" "$tmp/bin/sleep" "$tmp/log"; rmdir "$tmp/bin" "$tmp/secretsanta" "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/secretsanta"
export HOME="$tmp" PATH="$tmp/bin:$PATH" TEST_LOG="$tmp/log"

cat > "$tmp/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -eu
[ "$SECRET_SANTA_IMAGE" = "$EXPECTED_IMAGE" ]
echo "$*" >> "$TEST_LOG"
case "$*" in
  *" pull bot") [ "${FAKE_PULL_FAIL:-0}" != 1 ] ;;
  *" ps -q bot") [ "${FAKE_MISSING:-0}" = 1 ] || echo container-id ;;
  inspect*) echo "${FAKE_STATUS:-running}" ;;
esac
EOF
cat > "$tmp/bin/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$tmp/bin/docker" "$tmp/bin/sleep"

image=ghcr.io/example/bot:sha-0123456789012345678901234567890123456789
export EXPECTED_IMAGE="$image"
bash "$root/.github/scripts/deploy.sh" "$image"
grep -q ' pull bot$' "$TEST_LOG"
grep -q ' up -d --no-build$' "$TEST_LOG"
grep -q '^inspect ' "$TEST_LOG"

: > "$TEST_LOG"
if FAKE_PULL_FAIL=1 bash "$root/.github/scripts/deploy.sh" "$image"; then
  echo "Expected a failed image pull to fail deployment." >&2
  exit 1
fi
if grep -q ' up -d --no-build$' "$TEST_LOG"; then
  echo "A failed pull must not replace the running container." >&2
  exit 1
fi

if FAKE_STATUS=restarting bash "$root/.github/scripts/deploy.sh" "$image"; then
  echo "Expected a stopped bot container to fail deployment." >&2
  exit 1
fi
if FAKE_MISSING=1 bash "$root/.github/scripts/deploy.sh" "$image"; then
  echo "Expected a missing bot container to fail deployment." >&2
  exit 1
fi
echo "Deployment script tests passed."
