#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -f "$test_dir/fixture.env" "$test_dir/executed"; rmdir "$test_dir"' EXIT

# CRLF, spaces, quoted strings, Windows paths and shell syntax must stay data.
printf '%s\r\n' \
  '# ignored comment' \
  'DB_URL=jdbc:mysql://localhost:13307/media_db?useSSL=false&characterEncoding=utf-8' \
  'QUOTED="value with spaces"' \
  "SINGLE='another value'" \
  'WINDOWS_PATH=C:\Program Files\ffmpeg\bin' \
  'EMPTY=' \
  'SPACED=  trimmed value  ' \
  "LITERAL=\$(touch $test_dir/executed)" > "$test_dir/fixture.env"
printf '%s' 'LAST=no trailing newline' >> "$test_dir/fixture.env"

source "$ROOT_DIR/scripts/load-env.sh" "$test_dir/fixture.env"
[[ "$DB_URL" == 'jdbc:mysql://localhost:13307/media_db?useSSL=false&characterEncoding=utf-8' ]]
[[ "$QUOTED" == 'value with spaces' && "$SINGLE" == 'another value' ]]
[[ "$WINDOWS_PATH" == 'C:\Program Files\ffmpeg\bin' ]]
[[ -z "$EMPTY" && "$SPACED" == 'trimmed value' && "$LAST" == 'no trailing newline' ]]
[[ ! -e "$test_dir/executed" ]]
bash -c '[[ "$QUOTED" == "value with spaces" ]]'
if (source "$ROOT_DIR/scripts/load-env.sh" "$test_dir/missing.env") 2>/dev/null; then
  echo 'Missing .env must fail' >&2
  exit 1
fi
echo 'Bash .env loader tests passed'
