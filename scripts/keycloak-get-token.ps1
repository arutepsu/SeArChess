# LEGACY DEV HELPER — not the browser auth path.
#
# The Web UI uses Authorization Code + PKCE via keycloak-js.
# This script uses the Resource Owner Password Credentials grant (ROPC),
# which is disabled by default on the searchess-web client.
#
# To use this script you must first enable "Direct Access Grants" on the
# searchess-web client in the Keycloak admin console (http://localhost:8080).
# Do NOT enable it permanently; it is useful only for debugging token contents.
#
# Requires: Keycloak running on port 8080.

param(
    [string]$KeycloakUrl = "http://localhost:8080",
    [string]$ClientId    = "searchess-web",
    [string]$Username    = "demo",
    [string]$Password    = "demo"
)

$realm    = "searchess"
$tokenUrl = "$KeycloakUrl/realms/$realm/protocol/openid-connect/token"

Write-Host "NOTE: ROPC grant — for debug only. Browser auth uses PKCE, not this flow." -ForegroundColor Yellow
Write-Host "Requesting token from $tokenUrl ..." -ForegroundColor Cyan

$body = @{
    client_id   = $ClientId
    username    = $Username
    password    = $Password
    grant_type  = "password"
}

try {
    $response = Invoke-RestMethod -Method Post -Uri $tokenUrl `
        -ContentType "application/x-www-form-urlencoded" `
        -Body $body
    Write-Host "`nAccess token:" -ForegroundColor Green
    Write-Host $response.access_token
    Write-Host "`nExpires in: $($response.expires_in)s" -ForegroundColor Gray
} catch {
    Write-Host "Error: $_" -ForegroundColor Red
    Write-Host "Did you enable Direct Access Grants on $ClientId in Keycloak admin?" -ForegroundColor Yellow
    Write-Host "Admin console: $KeycloakUrl  (admin / admin)" -ForegroundColor Gray
}
