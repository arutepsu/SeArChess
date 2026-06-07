import type { ExternalAccountLinkDto, SetManualLichessLinkRequest, UserProfileResponse } from "./userServiceTypes";
import keycloak from "../auth/keycloak";

function authHeaders(): Record<string, string> {
  return keycloak.token ? { Authorization: `Bearer ${keycloak.token}` } : {};
}

async function fetchUserJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(options?.headers as Record<string, string> | undefined),
    },
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed: ${response.status}`);
  }

  return (await response.json()) as T;
}

export async function getMyProfile(): Promise<UserProfileResponse> {
  return fetchUserJson<UserProfileResponse>("/api/users/me");
}

export async function setManualLichessLink(
  request: SetManualLichessLinkRequest
): Promise<ExternalAccountLinkDto> {
  return fetchUserJson<ExternalAccountLinkDto>("/api/users/me/links/lichess/manual", {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export async function deleteLichessLink(): Promise<void> {
  const response = await fetch("/api/users/me/links/lichess", {
    method: "DELETE",
    headers: authHeaders(),
  });
  if (!response.ok && response.status !== 404) {
    throw new Error(`Delete failed: ${response.status}`);
  }
}
