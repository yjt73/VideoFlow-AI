#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env. Set the service passwords and SILICONFLOW_API_KEY, then rerun."
  exit 1
fi

compose=(docker compose --env-file .env -f compose.yaml)
if [[ "${1:-}" == "--full-stack" ]]; then
  "${compose[@]}" config --quiet
  "${compose[@]}" up -d --build --wait
  echo "VideoFlow: http://127.0.0.1:5173"
else
  compose+=(-f compose.dev.yaml)
  "${compose[@]}" config --quiet
  "${compose[@]}" up -d --wait mysql redis qdrant minio rmqnamesrv rmqbroker rmq-topics
  echo "Middleware ready. Debug VideoFlow Backend (IDEA) in IDEA, then run npm run dev in client/."
fi
"${compose[@]}" ps
