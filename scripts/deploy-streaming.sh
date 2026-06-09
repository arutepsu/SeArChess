#!/usr/bin/env bash
# Deploy the chess-streaming reactive application directly on the server.
# Run from the repository root on the server.

set -euo pipefail

IMAGE_NAME="searchess/chess-streaming:local"
CONTAINER_NAME="chess-streaming"
PORT=8082

echo "==> 1. Building Docker image locally on the server..."
docker build -t "$IMAGE_NAME" -f Dockerfile.streaming .

echo "==> 2. Stopping old container if exists..."
docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

echo "==> 3. Starting new container on port $PORT..."
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  -p "$PORT:8082" \
  -e STREAMING_HOST=0.0.0.0 \
  -e STREAMING_PORT=8082 \
  -e INDEX_HTML_PATH=/app/index.html \
  "$IMAGE_NAME"

echo ""
echo "==> Success! The chess-streaming service has been deployed on the server."
echo "Listing running containers:"
docker ps --filter name="$CONTAINER_NAME"

echo ""
echo "To access the service, open an SSH tunnel from your local machine:"
echo "    ssh -L $PORT:localhost:$PORT chess@141.37.74.145"
echo "Then navigate to: http://localhost:$PORT"
