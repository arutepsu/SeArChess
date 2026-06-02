# Evidence — Argo CD Bootstrap

Capture these artifacts after a successful Argo CD installation and first manual sync
on the university server to document the validated state.

---

## Files to capture

### 1. `01-argocd-pods.txt`

```bash
kubectl get pods -n argocd -o wide
```

Expected: all Argo CD pods `Running` with `READY` showing `1/1` or `2/2`.
Key pods: `argocd-server`, `argocd-repo-server`, `argocd-application-controller`,
`argocd-dex-server`, `argocd-redis`.

---

### 2. `02-argocd-applications.txt`

```bash
kubectl get applications -n argocd
```

Expected: one row `searchess`, `SYNC STATUS` column shows `Synced` (after first sync)
or `OutOfSync` (before first sync — expected on fresh install).

---

### 3. `03-argocd-application-describe.txt`

```bash
kubectl describe application searchess -n argocd
```

Confirm:
- `Repo URL: https://github.com/arutepsu/SeArChess.git`
- `Target Revision: performance`
- `Path: deployment/k8s/overlays/uni-server-registry`
- `Destination Namespace: searchess`
- `Sync Policy: <none>` (manual)
- `Health Status` and `Sync Status` fields

---

### 4. `04-argocd-app-yaml.txt`

```bash
kubectl get application searchess -n argocd -o yaml
```

Full application object for audit trail.

---

### 5. `05-argocd-cli-status.txt` *(if argocd CLI available)*

```bash
argocd app get searchess
argocd app diff searchess
```

If argocd CLI is not installed, document `N/A` and use the UI screenshots instead.

---

### 6. `06-searchess-pods.txt`

```bash
kubectl get pods -n searchess -o wide
```

Confirm all Searchess pods are `Running` after the first Argo CD sync.

---

### 7. `07-health-envoy.txt`

```bash
curl -s http://localhost:10000/health
```

Expected: `{"status":"ok",...}`

---

### 8. `08-install-output.txt`

```bash
bash deployment/argocd/install-argocd.sh 2>&1
```

Or save the output of `deploy-server-argocd.sh` at setup time.

---

### 9. `09-ui-screenshot.txt` *(optional)*

Notes or screenshot from the Argo CD web UI showing:
- Application `searchess` in project `searchess`
- Source: `https://github.com/arutepsu/SeArChess.git`, branch `performance`
- Sync status and health status
- Resource tree (Deployments, StatefulSets, Services visible)

---

## Capture command (one-liner)

Run from the repo root on the server after Argo CD is installed and the first sync
has completed:

```bash
D=docs/deployment/evidence/argocd
kubectl get pods -n argocd -o wide                             > $D/01-argocd-pods.txt
kubectl get applications -n argocd                             > $D/02-argocd-applications.txt
kubectl describe application searchess -n argocd               > $D/03-argocd-application-describe.txt
kubectl get application searchess -n argocd -o yaml            > $D/04-argocd-app-yaml.txt
argocd app get searchess                                       > $D/05-argocd-cli-status.txt 2>&1 || echo "N/A" > $D/05-argocd-cli-status.txt
kubectl get pods -n searchess -o wide                          > $D/06-searchess-pods.txt
curl -s http://localhost:10000/health                          > $D/07-health-envoy.txt
```
