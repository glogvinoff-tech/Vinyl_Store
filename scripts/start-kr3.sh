#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/init-env.py
docker compose --env-file .env -f compose.kr3.yml up -d --build --wait
printf '\nSPIN: http://localhost:8080/\nУправление: http://localhost:8080/admin.html\nПисьма (локально): http://localhost:8025/\nHealth: http://localhost:8080/actuator/health\n'
