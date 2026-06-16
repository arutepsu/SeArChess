export const serverConfig = {
  appName: "SeArChess Server TUI",
  sshUser: "chess",
  sshHost: "141.37.123.125",
  remoteRepoPath: "~/searchess-k3d-deploy",
  kubernetesNamespace: "searchess",
  argoCdNamespace: "argocd",
  argoCdApplicationName: "searchess",
  pythonAiRolloutName: "python-ai-service",
  apiLocalUrl: "http://127.0.0.1:10000/health",
  grafanaLocalUrl: "http://127.0.0.1:33001"
} as const;

export const SSH_TARGET = "chess@141.37.123.125";
export const sshTarget = SSH_TARGET;

export function inRemoteRepo(command: string): string {
  return `cd ${serverConfig.remoteRepoPath} &&\n${command}`;
}
