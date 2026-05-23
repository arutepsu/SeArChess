# University Server — Remote Dev Runbook

Connect the local Web UI frontend to the k3d backend deployed on the university server,
without needing direct network access to `141.37.74.145`.

Direct TCP access to `141.37.74.145:10000` is blocked by the university network policy.
All traffic is routed through an SSH tunnel that forwards the remote Envoy port to localhost.

---

## Prerequisites

- SSH access to `chess@141.37.74.145` (public-key auth recommended)
- Node.js 20+ installed locally
- Web UI dependencies installed (`npm install` inside `apps/web-ui`)

---

## 1. Open the SSH tunnel

Run in a dedicated terminal and keep it open for the duration of the session:

```bash
ssh -L 10000:localhost:10000 -L 33001:localhost:33001 chess@141.37.74.145
```

| Forwarded port | Remote target | Service |
|---|---|---|
| `localhost:10000` | server `localhost:10000` | Envoy edge proxy (API + WebSocket) |
| `localhost:33001` | server `localhost:33001` | Grafana observability UI |

Leave this terminal open. Closing it drops all forwarded ports.

---

## 2. Verify the tunnel and backend health

In a second terminal, before starting the frontend:

```bash
curl http://localhost:10000/health
```

Expected response:

```json
{"status":"ok"}
```

If the request times out or is refused, the tunnel is not open or the backend is not running.
Check the SSH session in Terminal 1.

---

## 3. Start the Web UI against the deployed backend

```bash
cd apps/web-ui
npm run dev:deployed
```

Vite loads `.env.deployed` automatically when `--mode deployed` is used.
The frontend sends all API calls and WebSocket connections to `http://localhost:10000`,
which the SSH tunnel forwards to the university server's Envoy proxy.

Open the URL Vite prints (default: `http://localhost:5173`).

---

## 4. Switching back to local development

To develop against the local Docker Compose stack instead, use the standard command:

```bash
npm run dev
```

This does not load `.env.deployed`. The API client falls back to `http://localhost:10000`
(the same address), which in local mode is served by the Docker Compose Envoy container.

---

## Environment variables

| Variable | Default (hardcoded) | `.env.deployed` value |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:10000` | `http://localhost:10000` |
| `VITE_WS_URL` | `ws://localhost:10000/ws` | `ws://localhost:10000/ws` |

The `dev:deployed` mode uses the same addresses as local mode because the SSH tunnel
maps the remote port to the same local port number. The separate mode exists to make the
intent explicit and to allow future divergence without changing source code.

To override for a different tunnel port, create `apps/web-ui/.env.deployed.local`
(gitignored) with the alternate values. Do not commit `.env.deployed.local`.

---

## Grafana (optional)

While the tunnel is open, Grafana is available at:

```
http://localhost:33001
```

Default credentials: `admin` / `admin` (dev cluster default — change before sharing).

---

## Troubleshooting

### `curl http://localhost:10000/health` — connection refused

The SSH tunnel is not open. Start it in Terminal 1 (step 1).

### `curl http://localhost:10000/health` — returns HTML or 502

The backend is not healthy on the server side. Log in to the server and check pod status:

```bash
# In the SSH session (Terminal 1):
kubectl get pods -n searchess
kubectl logs -n searchess -l app=game-service --tail=30
```

### Frontend shows "Failed to fetch" for all API calls

Open the browser console. If the request URL is not `http://localhost:10000/...`, Vite
did not pick up `.env.deployed`. Verify:

1. The command used is `npm run dev:deployed` (not `npm run dev`).
2. `apps/web-ui/.env.deployed` exists and contains `VITE_API_BASE_URL=http://localhost:10000`.
3. Restart Vite after any `.env.deployed` change.

### WebSocket disconnects immediately

The tunnel must stay alive for the duration of the session. If the SSH connection drops,
WebSocket connections through the tunnel are also dropped. Re-open the tunnel and reload
the page.

Add `ServerAliveInterval 30` to `~/.ssh/config` to keep idle SSH connections alive:

```
Host 141.37.74.145
    ServerAliveInterval 30
    ServerAliveCountMax 3
```
