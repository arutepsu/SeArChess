# Python AI Service Integration

## Overview

The Python AI service (`searchess-ai-service`) is deployed as an internal Kubernetes workload.
It is never exposed through Envoy or any external port. The Scala `ai-service` acts as the stable
API/facade and proxies move-suggestion requests to the Python service when configured.

---

## Service roles

| Service | Language | Role | Caller |
|---|---|---|---|
| `game-service` | Scala / JVM | Game orchestration | Web UI via Envoy |
| `ai-service` | Scala / JVM | AI facade, validation, proxy | game-service |
| `python-ai-service` | Python / FastAPI | Inference engine (supervised policy, random, fake) | ai-service |

The game-service has no direct knowledge of the Python service. The contract between
game-service and ai-service (`POST /v1/move-suggestions`) is unchanged.

---

## Request flow

```
game-service
  │  POST /v1/move-suggestions
  ▼
ai-service (Scala)
  │  validate request
  │  PYTHON_AI_BASE_URL set?
  ├─ yes ──► POST http://python-ai-service:8765/v1/move-suggestions
  │            │  HTTP 2xx? ──► return Python response to game-service
  │            │  error/timeout? ──► ai_proxy_fallback log + local selection
  └─ no ───► local selection (legalMoves.head)
```

---

## Configuration

### ai-service

| Variable | Default | Effect |
|---|---|---|
| `PYTHON_AI_BASE_URL` | *(empty)* | When set, requests are proxied to this URL |
| `PYTHON_AI_TIMEOUT_MILLIS` | `5000` | Proxy call timeout; on expiry triggers local fallback |
| `AI_ENGINE_ID` | `random-legal` | Engine label reported in responses |

### python-ai-service

| Variable | Default | Effect |
|---|---|---|
| `INFERENCE_BACKEND` | `fake` | `fake` / `random` / `openspiel` / `supervised` |
| `MODEL_ARTIFACT_DIR` | *(empty)* | Required when `INFERENCE_BACKEND=supervised`; path to run artifact directory |

---

## Fallback behavior

The ai-service falls back to local move selection (first legal move) on any proxy failure:

- Python service not yet started (connection refused)
- Network partition between ai-service and python-ai-service pods
- Timeout (`PYTHON_AI_TIMEOUT_MILLIS` exceeded)
- Python service returns non-2xx response

All fallbacks emit a structured log event:
```json
{"event": "ai_proxy_fallback", "requestId": "...", "upstreamUrl": "...", "reason": "...", "message": "..."}
```

Upstream errors (non-2xx) emit `ai_proxy_upstream_error` before falling back.

---

## Kubernetes topology

```
            ClusterIP Service
game-service ──► ai-service:8765 (Scala)
                      │
                      │ ClusterIP Service (internal only)
                      ▼
                python-ai-service:8765 (Python/FastAPI)
```

Neither `ai-service` nor `python-ai-service` is exposed via Envoy.
Envoy only routes to `game-service`.

---

## Supervised backend and model artifacts

For `INFERENCE_BACKEND=supervised`, the Python service requires a model artifact directory
containing `manifest.json` and `model.pt`. In Kubernetes, this requires either:

- A HostPath volume (current approach on the university server, manually managed)
- A PVC backed by the node's local storage

Example overlay patch to enable supervised mode:

```yaml
# In your overlay's kustomization.yaml or a separate patch file:
- patch: |-
    - op: replace
      path: /data/INFERENCE_BACKEND
      value: supervised
    - op: add
      path: /data/MODEL_ARTIFACT_DIR
      value: /artifacts/run_YYYYMMDD_HHMMSS_xxxxxxxx
  target:
    kind: ConfigMap
    name: python-ai-service-env
```

And add a HostPath volume to the Deployment:

```yaml
- patch: |-
    - op: add
      path: /spec/template/spec/volumes
      value:
        - name: artifacts
          hostPath:
            path: /home/chess/artifacts
            type: Directory
    - op: add
      path: /spec/template/spec/containers/0/volumeMounts
      value:
        - name: artifacts
          mountPath: /artifacts
          readOnly: true
  target:
    kind: Deployment
    name: python-ai-service
```

---

## Port reference

| Service | Container port | Protocol | Accessible from |
|---|---|---|---|
| `python-ai-service` | 8765 | HTTP | ai-service pod only (ClusterIP) |
| `ai-service` | 8765 | HTTP | game-service pod only (ClusterIP) |
