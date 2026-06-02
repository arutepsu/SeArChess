# Evidence — uni-server-registry Deployment

Capture these artifacts after a successful registry-based deployment on the university
server to document the validated state.

---

## Files to capture

### 1. `01-pods-wide.txt`

```bash
kubectl get pods -n searchess -o wide
```

Confirm: all pods `Running`, `READY` column shows `1/1`, no restarts.
Check the `IMAGE` column: app services must show `ghcr.io/arutepsu/searchess-*`.

---

### 2. `02-services.txt`

```bash
kubectl get svc -n searchess
```

Confirm: `envoy` and `grafana` show `TYPE=LoadBalancer` and an `EXTERNAL-IP`.

---

### 3. `03-describe-game-service.txt`

```bash
kubectl describe deployment game-service -n searchess
```

Confirm: `Image` field shows `ghcr.io/arutepsu/searchess-game-service:<sha-or-tag>`,
`imagePullPolicy: IfNotPresent`.

---

### 4. `04-describe-python-ai-service.txt`

```bash
kubectl describe deployment python-ai-service -n searchess
```

Confirm: `Image` field shows `ghcr.io/arutepsu/searchess-python-ai-service:<sha-or-tag>`,
`imagePullPolicy: IfNotPresent`.

---

### 5. `05-mongo-image.txt`

```bash
kubectl get statefulset mongo -n searchess \
  -o jsonpath='{.spec.template.spec.containers[0].image}'
```

Must print: `mongo:4.4`

---

### 6. `06-health-envoy.txt`

```bash
curl -s http://localhost:10000/health
```

Expected:
```json
{"status":"ok","service":"searchess-game-service",...}
```

---

### 7. `07-health-grafana.txt`

```bash
curl -s http://localhost:33001/api/health
```

Expected: JSON with `"database": "ok"`.

---

### 8. `08-verify-output.txt`

```bash
bash deployment/server/verify-server-registry.sh 2>&1
```

Must end with: `Results: N passed, 0 failed`

---

### 9. `09-workflow-run.txt`

From GitHub Actions UI or CLI:
```bash
gh run list --workflow=build-images.yml --limit=5
gh run view <run-id>
```

Confirm: most recent build-images run completed with status `success` on the
`performance` branch. Note the SHA and the images pushed.

---

## Capture command (one-liner)

Run from the repo root on the server after all pods are Running:

```bash
kubectl get pods -n searchess -o wide               > docs/deployment/evidence/uni-server-registry/01-pods-wide.txt
kubectl get svc -n searchess                        > docs/deployment/evidence/uni-server-registry/02-services.txt
kubectl describe deployment game-service -n searchess \
                                                    > docs/deployment/evidence/uni-server-registry/03-describe-game-service.txt
kubectl describe deployment python-ai-service -n searchess \
                                                    > docs/deployment/evidence/uni-server-registry/04-describe-python-ai-service.txt
kubectl get statefulset mongo -n searchess \
  -o jsonpath='{.spec.template.spec.containers[0].image}' \
                                                    > docs/deployment/evidence/uni-server-registry/05-mongo-image.txt
curl -s http://localhost:10000/health               > docs/deployment/evidence/uni-server-registry/06-health-envoy.txt
curl -s http://localhost:33001/api/health           > docs/deployment/evidence/uni-server-registry/07-health-grafana.txt
bash deployment/server/verify-server-registry.sh 2>&1 \
                                                    > docs/deployment/evidence/uni-server-registry/08-verify-output.txt
```
