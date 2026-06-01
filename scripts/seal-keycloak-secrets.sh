#!/usr/bin/env bash
# seal-keycloak-secrets.sh
#
# Generates a SealedSecret for Keycloak credentials and writes it to the
# uni-server-registry overlay.
#
# Prerequisites:
#   - kubeseal installed (https://github.com/bitnami-labs/sealed-secrets)
#   - kubectl context set to the university k3d cluster
#   - Sealed Secrets controller running in kube-system
#
# Usage:
#   bash scripts/seal-keycloak-secrets.sh
#
# You will be prompted for three values:
#   1. Keycloak bootstrap admin username  (e.g. admin — do NOT use admin/admin; choose a real value)
#   2. Keycloak bootstrap admin password  (choose a strong password)
#   3. Keycloak database password         (choose a strong password, passed to Postgres init Job too)
#
# The output file is committed to:
#   deployment/k8s/overlays/uni-server-registry/keycloak-sealed-secret.yaml
#
# After generating, commit the file and push to main. Argo CD will sync the SealedSecret.

set -euo pipefail

OVERLAY="deployment/k8s/overlays/uni-server-registry/keycloak-sealed-secret.yaml"
NAMESPACE="searchess"
SECRET_NAME="keycloak-secrets"

echo ""
echo "=== Keycloak SealedSecret generator ==="
echo ""
echo "Enter credentials for the university server Keycloak deployment."
echo "These values are encrypted with the cluster key and committed to Git."
echo ""

read -rsp "Bootstrap admin username: " KC_ADMIN_USER; echo
read -rsp "Bootstrap admin password: " KC_ADMIN_PASS; echo
read -rsp "Keycloak DB password:     " KC_DB_PASS;    echo

echo ""
echo "Sealing credentials with kubeseal..."

kubectl create secret generic "$SECRET_NAME" \
  --namespace="$NAMESPACE" \
  --from-literal=bootstrap-admin-username="$KC_ADMIN_USER" \
  --from-literal=bootstrap-admin-password="$KC_ADMIN_PASS" \
  --from-literal=db-password="$KC_DB_PASS" \
  --dry-run=client -o yaml \
| kubeseal \
    --controller-name=sealed-secrets-controller \
    --controller-namespace=kube-system \
    --format=yaml \
> "$OVERLAY"

echo ""
echo "Written: $OVERLAY"
echo ""
echo "Next steps:"
echo "  1. Review the generated file."
echo "  2. git add $OVERLAY && git commit -m 'chore: seal keycloak credentials'"
echo "  3. git push origin main"
echo "  4. Argo CD will sync the SealedSecret and create Secret/keycloak-secrets."
echo "  5. Then run the Postgres init Job:"
echo "     kubectl apply -f deployment/k8s/base/postgres/job-keycloak-db-init.yaml"
echo "     kubectl wait --for=condition=complete -n searchess job/postgres-init-keycloak --timeout=120s"
echo ""
echo "Done."
