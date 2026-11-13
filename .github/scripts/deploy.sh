#!/usr/bin/env bash
set -eu

cd "$HOME/secretsanta"
export SECRET_SANTA_IMAGE="$1"

docker compose -p secretsanta -f compose.yaml -f compose.deploy.yaml pull bot
docker compose -p secretsanta -f compose.yaml -f compose.deploy.yaml up -d --no-build
sleep 10
container=$(docker compose -p secretsanta -f compose.yaml -f compose.deploy.yaml ps -q bot)
if [ -z "$container" ] || [ "$(docker inspect --format '{{.State.Status}}' "$container")" != running ]; then
  echo "Deployment failed: the bot container is not running." >&2
  exit 1
fi
