# SeArChess Server TUI

A small local TypeScript CLI/TUI for repeatable SeArChess demo readiness checks on the university deployment server.

It is designed for Windows PowerShell and uses normal `ssh` behavior for authentication. The tool wraps a safe set of connection and read-only inspection commands so demo preparation is easier to run without remembering every command by hand.

## What It Does

- Opens an SSH shell to the deployment server.
- Checks whether passwordless SSH key authentication is configured.
- Opens local SSH tunnels for the API and Grafana.
- Runs read-only Kubernetes, Argo CD, rollout, health, and OpenTelemetry evidence checks.
- Opens local browser URLs after the tunnel is running.
- Streams command output live in the terminal.

## What It Does Not Do

- It does not manage VPN.
- It does not store, ask for, or write SSH passwords.
- It does not install packages automatically.
- It does not require admin rights.
- It does not mutate Kubernetes resources.

## VPN Reminder

Turn on VPN first.

The deployment server is only reachable when the university VPN is already active. Start VPN manually before running this tool.

## Security Note

SSH handles authentication. If you use a password, the prompt comes from `ssh`. If you use a key, your local SSH configuration handles it. This tool never stores secrets.

## Passwordless SSH Setup

The TUI does not store passwords. The recommended solution is normal SSH key authentication.

You may need the server password once when copying your public key to the university server. After setup, direct SSH and the TUI should connect without asking for the server password.

You can verify setup from the TUI by choosing:

```text
Check passwordless SSH setup
```

That check runs with `BatchMode=yes`, so it will not ask for a password.

Step 1: Create an SSH key if you do not already have one:

```powershell
ssh-keygen -t ed25519 -C "searchess-server"
```

Accept the default path unless you know what you are doing.

Step 2: Copy the public key to the university server. This will ask for the SSH password one last time:

```powershell
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh chess@141.37.74.145 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
```

Step 3: Test it:

```powershell
ssh chess@141.37.74.145
```

After this, the normal TUI actions should connect without asking for the server password.

### Optional SSH Config Alias

The target is configured in `src/config.ts` as `SSH_TARGET`. By default:

```ts
export const SSH_TARGET = "chess@141.37.74.145";
```

You can add an SSH config alias:

```text
Host searchess-server
  HostName 141.37.74.145
  User chess
  IdentityFile ~/.ssh/id_ed25519
  IdentitiesOnly yes
```

Then change the target to:

```ts
export const SSH_TARGET = "searchess-server";
```

The commands become cleaner, for example:

```powershell
ssh searchess-server
```

## Installation

```powershell
cd tools/server-tui
npm install
```

## Normal Usage

```powershell
cd tools/server-tui
npm install
npm run server-tui
```

## Development Usage

```powershell
npm run dev
```

## Build

```powershell
npm run build
```

## Run Built Version

```powershell
npm start
```

## Example Menu Output

```text
================================
  SeArChess Server TUI
================================

Turn on VPN first.

Startup
Warning:
Turn on VPN first.

Target:
chess@141.37.74.145

Authentication:
Uses normal SSH authentication.
No passwords are stored.
For passwordless login, configure an SSH key.

Remote repo: ~/searchess-k3d-deploy
Kubernetes namespace: searchess
Argo CD namespace: argocd

Choose a safe read-only action
  Check passwordless SSH setup
  Connect SSH shell
  Open API + Grafana tunnel
  Check Argo CD status
  Check Kubernetes pods
  Check unhealthy pods only
  Check Python AI rollout
  Run full demo readiness check
  Check F4 OpenTelemetry evidence
  Open local browser URLs
  Exit
```

## Commands

`Check passwordless SSH setup`

Runs a non-interactive SSH key-authentication check:

```text
ssh -o BatchMode=yes -o ConnectTimeout=5 chess@141.37.74.145 "echo SSH_OK"
```

If it prints `SSH_OK` and exits with code 0, passwordless SSH is configured. If it fails, the TUI prints the PowerShell setup steps above.

`Connect SSH shell`

Opens an interactive SSH session:

```text
ssh chess@141.37.74.145
```

This is blocking and asks for confirmation before it starts.

`Open API + Grafana tunnel`

Opens local port forwards:

```text
ssh -L 10000:127.0.0.1:10000 -L 33001:127.0.0.1:33001 chess@141.37.74.145
```

The tunnel stays open until Ctrl+C. Local URLs:

- API: `http://127.0.0.1:10000/health`
- Grafana: `http://127.0.0.1:33001`

`Check Argo CD status`

Shows Argo CD applications and the deployed revision for the `searchess` application.

`Check Kubernetes pods`

Lists pods in the `searchess` namespace.

`Check unhealthy pods only`

Filters pod output for common unhealthy states: `0/1`, `Pending`, `CrashLoop`, `Error`, `BackOff`, and `Unknown`. The `grep` command runs remotely on Linux through SSH.

`Check Python AI rollout`

Reads the Argo Rollouts status for `python-ai-service` in the `searchess` namespace.

`Run full demo readiness check`

Checks local and remote Git revisions, Argo CD status, Python AI rollout health, unhealthy pods, API health, API route health, and Grafana health.

`Check F4 OpenTelemetry evidence`

Finds the Python AI pod, prints OpenTelemetry environment variables, and queries Tempo for recent traces from `searchess-python-ai-service`.

`Open local browser URLs`

Opens the API health URL and Grafana URL with the platform browser opener:

- Windows: `start`
- macOS: `open`
- Linux: `xdg-open`

`Exit`

Closes the TUI.

## How Commands Are Registered

Commands live in `src/commands.ts` as declarative registry entries. Each command has:

- `id`
- `title`
- `description`
- `kind`
- `blocking`
- `confirmationMessage` when needed
- `preview`
- `execute`

The menu loop in `src/server-tui.ts` dispatches selected entries. Command execution stays in `src/runner.ts`, and display/confirmation helpers stay in `src/ui.ts`.

Remote SSH commands are passed as arguments to `spawn`, not shell-concatenated locally:

```ts
spawn("ssh", ["chess@141.37.74.145", remoteCommand], ...)
```

This keeps Windows PowerShell out of the remote command quoting path.

## Add A New Command

Add a read-only remote command near the top of `src/commands.ts`:

```ts
const servicesRemoteCommand = inRemoteRepo(
  `kubectl get services -n ${serverConfig.kubernetesNamespace}`
);
```

Then add a registry item:

```ts
{
  id: "services",
  title: "Check Kubernetes services",
  description: "List services in the SeArChess namespace.",
  kind: "spawn",
  blocking: false,
  execute: () => sshRemote(servicesRemoteCommand),
  preview: () => commandPreview(sshRemote(servicesRemoteCommand))
}
```

Use `blocking: true` only for commands that stay open, such as an SSH shell or tunnel. Add `confirmationMessage` for blocking commands.

For commands that need captured output and custom success/failure handling, add a small dedicated `kind` and handle it in `src/server-tui.ts` while keeping process execution in `src/runner.ts`.

## Safety Boundary

Version 1 is limited to connection helpers and read-only checks.

Allowed: `ssh`, SSH tunnels, `kubectl get`, `kubectl argo rollouts get`, `kubectl exec` for inspection, `git fetch`, `git rev-parse`, `curl`, browser URL opening.

Not allowed in v1: `kubectl apply`, `delete`, `patch`, `promote`, `restart`, `scale`, or other deployment mutation actions.

## Design Notes

- This is a connection/readiness helper, not a deployment mutator.
- The server remains the source of truth.
- SSH handles authentication.
- The tool only wraps commands for repeatable demo operation.
