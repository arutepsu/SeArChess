# Evidence — University Server k3d Cutover

Capture these artifacts after the cutover from Docker Compose to k3d on the university server.
Store them in this directory. Do not commit sensitive output (secrets, passwords).

---

## Files to capture

### `01-cluster-nodes.txt`

```bash
kubectl get nodes -o wide > docs/deployment/evidence/uni-server-cutover/01-cluster-nodes.txt
```

Shows the k3d node is `Ready` and identifies the k3d version.

---

### `02-pods-wide.txt`

```bash
kubectl get pods -n searchess -o wide \
  > docs/deployment/evidence/uni-server-cutover/02-pods-wide.txt
```

Shows all pods `Running` with node assignment and IP allocation.

---

### `03-services.txt`

```bash
kubectl get svc -n searchess \
  > docs/deployment/evidence/uni-server-cutover/03-services.txt
```

Shows envoy and grafana as `LoadBalancer` with assigned external IPs.

---

### `04-health-envoy.json`

```bash
curl -fsS http://localhost:10000/health \
  > docs/deployment/evidence/uni-server-cutover/04-health-envoy.json
```

Expected: `{"status":"ok"}` or equivalent health response from game-service.

---

### `05-health-grafana.json`

```bash
curl -fsS http://localhost:33001/api/health \
  > docs/deployment/evidence/uni-server-cutover/05-health-grafana.json
```

Expected: `{"commit":"...","database":"ok","version":"..."}`.

---

### `06-game-smoke-test.json`

Run a complete HumanVsAI game cycle through the SSH tunnel from the developer machine.
Capture the final game state showing both moves applied:

```bash
# From developer machine (tunnel open):
GAME_ID=$(curl -fsS -X POST http://localhost:10000/api/sessions \
  -H 'Content-Type: application/json' \
  -d '{"mode":"HumanVsAI"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['game']['gameId'])")

curl -fsS -X POST "http://localhost:10000/api/games/$GAME_ID/moves" \
  -H 'Content-Type: application/json' \
  -d '{"from":"e2","to":"e4","controller":"HumanLocal"}'

curl -fsS -X POST "http://localhost:10000/api/games/$GAME_ID/ai-move" \
  | python3 -m json.tool \
  > docs/deployment/evidence/uni-server-cutover/06-game-smoke-test.json
```

Expected: `moveHistory` contains two moves, `currentPlayer` is `"White"`, `fullmoveNumber` is `2`.

---

### `07-mongo-image.txt`

Confirm the downgraded Mongo image is running (AVX fix):

```bash
kubectl get statefulset mongo -n searchess \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}' \
  > docs/deployment/evidence/uni-server-cutover/07-mongo-image.txt
```

Expected: `mongo:4.4`

---

### `08-verify-script-output.txt`

```bash
bash deployment/server/verify-server-k3d.sh \
  > docs/deployment/evidence/uni-server-cutover/08-verify-script-output.txt 2>&1
```

Expected: all checks `[OK]`, final line `Results: N passed, 0 failed`.

---

## What to check before committing evidence

- No passwords or tokens in any captured output
- All pod phases are `Running` (not `Pending`, `CrashLoopBackOff`, etc.)
- `07-mongo-image.txt` contains `mongo:4.4` (not `mongo:7.0`)
- `04-health-envoy.json` and `05-health-grafana.json` are non-error JSON
- `06-game-smoke-test.json` shows `fullmoveNumber: 2` and two entries in `moveHistory`
