#!/usr/bin/env bash
# Import locally-built application images into the searchess-server k3d cluster.
# Run from any directory on the server after building the images.
#
# Usage:
#   bash deployment/server/import-images.sh
set -euo pipefail

export PATH="$HOME/bin:$PATH"

CLUSTER=searchess-server

echo "==> Importing images into k3d cluster '$CLUSTER'"

k3d image import \
  searchess/game-service:local \
  searchess/history-service:local \
  searchess/ai-service:local \
  searchess/python-ai-service:local \
  searchess/web-ui:local \
  searchess/lichess-bot:local \
  --cluster "$CLUSTER"

echo "==> Import complete. Verify:"
k3d image list --cluster "$CLUSTER" | grep -E "searchess/(game|history|ai|python-ai|lichess-bot)-service|searchess/(web-ui|lichess-bot)"
