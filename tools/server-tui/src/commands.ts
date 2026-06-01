import { serverConfig, inRemoteRepo, SSH_TARGET } from "./config";
import type { SpawnCommand } from "./runner";

type BaseCommand = {
  id: string;
  title: string;
  description: string;
  blocking: boolean;
  preview: () => string;
};

export type CommandRegistryItem =
  | (BaseCommand & {
      kind: "spawn";
      execute: () => SpawnCommand;
      beforeRun?: string[];
      confirmationMessage?: string;
    })
  | (BaseCommand & {
      kind: "passwordless-ssh-check";
      execute: () => SpawnCommand;
    })
  | (BaseCommand & {
      kind: "browser";
    })
  | (BaseCommand & {
      kind: "exit";
    });

const argoStatusRemoteCommand = inRemoteRepo(
  [
    `kubectl get applications -n ${serverConfig.argoCdNamespace}`,
    `kubectl get application ${serverConfig.argoCdApplicationName} -n ${serverConfig.argoCdNamespace} -o jsonpath='{.status.sync.revision}{"\\n"}'`
  ].join(" &&\n")
);

const podsRemoteCommand = inRemoteRepo(`kubectl get pods -n ${serverConfig.kubernetesNamespace}`);

const unhealthyPodsRemoteCommand = inRemoteRepo(
  `kubectl get pods -n ${serverConfig.kubernetesNamespace} | grep -E '0/1|Pending|CrashLoop|Error|BackOff|Unknown' || true`
);

const pythonAiRolloutRemoteCommand = inRemoteRepo(
  `kubectl argo rollouts get rollout ${serverConfig.pythonAiRolloutName} -n ${serverConfig.kubernetesNamespace}`
);

const readinessRemoteCommand = inRemoteRepo(
  [
    "git fetch origin main",
    'echo "LOCAL=$(git rev-parse HEAD)"',
    'echo "REMOTE=$(git rev-parse origin/main)"',
    `echo "ARGO=$(kubectl get application ${serverConfig.argoCdApplicationName} -n ${serverConfig.argoCdNamespace} -o jsonpath='{.status.sync.revision}')"`,
    `kubectl get applications -n ${serverConfig.argoCdNamespace}`,
    `kubectl argo rollouts get rollout ${serverConfig.pythonAiRolloutName} -n ${serverConfig.kubernetesNamespace}`,
    `kubectl get pods -n ${serverConfig.kubernetesNamespace} | grep -E '0/1|Pending|CrashLoop|Error|BackOff|Unknown' || true`,
    "curl -s http://localhost:10000/health || true",
    "curl -s http://localhost:10000/api/health || true",
    "curl -s http://localhost:33001/api/health || true"
  ].join(" &&\n")
);

const f4OpenTelemetryRemoteCommand = inRemoteRepo(
  [
    `PY_POD=$(kubectl get pod -n ${serverConfig.kubernetesNamespace} -l app=${serverConfig.pythonAiRolloutName} -o jsonpath='{.items[0].metadata.name}')`,
    'echo "Python AI pod: $PY_POD"',
    `kubectl exec -n ${serverConfig.kubernetesNamespace} "$PY_POD" -- printenv | grep -E 'OTEL_SERVICE_NAME|OTEL_TRACES_EXPORTER|OTEL_METRICS_EXPORTER|OTEL_LOGS_EXPORTER|OTEL_EXPORTER_OTLP'`,
    `kubectl exec -n ${serverConfig.kubernetesNamespace} "$PY_POD" -- python - <<'PY'
import time
import urllib.parse
import urllib.request

end = int(time.time())
start = end - 1200

params = urllib.parse.urlencode({
    "q": "{ resource.service.name = \\"searchess-python-ai-service\\" }",
    "start": str(start),
    "end": str(end),
    "limit": "10",
})

url = "http://tempo:3200/api/search?" + params
print(urllib.request.urlopen(url, timeout=20).read().decode())
PY`
  ].join(" &&\n")
);

function sshRemote(remoteCommand: string): SpawnCommand {
  return {
    command: "ssh",
    args: [SSH_TARGET, remoteCommand]
  };
}

const sshShellCommand = (): SpawnCommand => ({
  command: "ssh",
  args: [SSH_TARGET]
});

const sshTunnelCommand = (): SpawnCommand => ({
  command: "ssh",
  args: ["-L", "10000:127.0.0.1:10000", "-L", "33001:127.0.0.1:33001", SSH_TARGET]
});

const passwordlessSshCheckCommand = (): SpawnCommand => ({
  command: "ssh",
  args: ["-o", "BatchMode=yes", "-o", "ConnectTimeout=5", SSH_TARGET, "echo SSH_OK"]
});

function commandPreview(spawnCommand: SpawnCommand): string {
  return [spawnCommand.command, ...spawnCommand.args.map(previewArg)].join(" ");
}

function previewArg(arg: string): string {
  if (arg.includes("\n")) {
    return `"\n${arg}\n"`;
  }

  if (/^[A-Za-z0-9_./:@=~-]+$/.test(arg)) {
    return arg;
  }

  return JSON.stringify(arg);
}

export const commandRegistry: CommandRegistryItem[] = [
  {
    id: "passwordless-ssh-check",
    title: "Check passwordless SSH setup",
    description: "Verify SSH key authentication without prompting for a password.",
    kind: "passwordless-ssh-check",
    blocking: false,
    execute: passwordlessSshCheckCommand,
    preview: () => commandPreview(passwordlessSshCheckCommand())
  },
  {
    id: "ssh-shell",
    title: "Connect SSH shell",
    description: "Open an interactive shell on the deployment server.",
    kind: "spawn",
    blocking: true,
    confirmationMessage: "This opens an interactive SSH shell and stays open until you exit. Continue?",
    execute: sshShellCommand,
    preview: () => commandPreview(sshShellCommand())
  },
  {
    id: "ssh-tunnel",
    title: "Open API + Grafana tunnel",
    description: "Forward local API and Grafana ports until Ctrl+C.",
    kind: "spawn",
    blocking: true,
    beforeRun: [
      "This tunnel stays open until Ctrl+C.",
      `API local URL: ${serverConfig.apiLocalUrl}`,
      `Grafana local URL: ${serverConfig.grafanaLocalUrl}`
    ],
    execute: sshTunnelCommand,
    preview: () => commandPreview(sshTunnelCommand())
  },
  {
    id: "argo-status",
    title: "Check Argo CD status",
    description: "Show Argo CD applications and deployed revision.",
    kind: "spawn",
    blocking: false,
    execute: () => sshRemote(argoStatusRemoteCommand),
    preview: () => commandPreview(sshRemote(argoStatusRemoteCommand))
  },
  {
    id: "pods",
    title: "Check Kubernetes pods",
    description: "List pods in the SeArChess namespace.",
    kind: "spawn",
    blocking: false,
    execute: () => sshRemote(podsRemoteCommand),
    preview: () => commandPreview(sshRemote(podsRemoteCommand))
  },
  {
    id: "unhealthy-pods",
    title: "Check unhealthy pods only",
    description: "Filter pod output for common unhealthy states.",
    kind: "spawn",
    blocking: false,
    execute: () => sshRemote(unhealthyPodsRemoteCommand),
    preview: () => commandPreview(sshRemote(unhealthyPodsRemoteCommand))
  },
  {
    id: "python-ai-rollout",
    title: "Check Python AI rollout",
    description: "Read the Argo Rollouts status for the Python AI service.",
    kind: "spawn",
    blocking: false,
    execute: () => sshRemote(pythonAiRolloutRemoteCommand),
    preview: () => commandPreview(sshRemote(pythonAiRolloutRemoteCommand))
  },
  {
    id: "readiness",
    title: "Run full demo readiness check",
    description: "Compare revisions, Argo CD, rollout, pods, and health endpoints.",
    kind: "spawn",
    blocking: false,
    execute: () => sshRemote(readinessRemoteCommand),
    preview: () => commandPreview(sshRemote(readinessRemoteCommand))
  },
  {
    id: "f4-otel",
    title: "Check F4 OpenTelemetry evidence",
    description: "Inspect OTEL env vars and recent Tempo trace search results.",
    kind: "spawn",
    blocking: false,
    execute: () => sshRemote(f4OpenTelemetryRemoteCommand),
    preview: () => commandPreview(sshRemote(f4OpenTelemetryRemoteCommand))
  },
  {
    id: "browser-urls",
    title: "Open local browser URLs",
    description: "Open API health and Grafana after the tunnel is running.",
    kind: "browser",
    blocking: false,
    preview: () => `${serverConfig.apiLocalUrl}\n${serverConfig.grafanaLocalUrl}`
  },
  {
    id: "exit",
    title: "Exit",
    description: "Close the TUI.",
    kind: "exit",
    blocking: false,
    preview: () => "Exit"
  }
];
