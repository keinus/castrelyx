import type {
  ApplicationCertificate,
  ApplicationPrincipal,
  ApplicationToken,
  AuditPage,
  MigrationPlan,
  MigrationStatus,
  Secret,
  SecretType,
  SecretVersion,
  Session,
  VaultStatus
} from './types';

type RequestOptions = RequestInit & { json?: unknown };

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  const method = options.method ?? 'GET';
  if (options.json !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (isUnsafe(method)) {
    const csrf = cookie('CASTRELVAULT_CSRF');
    if (csrf) {
      headers.set('X-CSRF-Token', csrf);
    }
  }
  const response = await fetch(path, {
    ...options,
    method,
    credentials: 'include',
    headers,
    body: options.json === undefined ? options.body : JSON.stringify(options.json)
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body?.error) {
        message = String(body.error);
      }
    } catch {
      // Keep the HTTP status message.
    }
    throw new Error(message);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const api = {
  session: () => request<Session>('/api/admin/session'),
  login: (username: string, password: string) =>
    request<Session>('/api/admin/login', { method: 'POST', json: { username, password } }),
  changePassword: (currentPassword: string, newPassword: string) =>
    request<{ ok: boolean }>('/api/admin/change-password', { method: 'POST', json: { currentPassword, newPassword } }),
  logout: () => request<{ ok: boolean }>('/api/admin/logout', { method: 'POST', json: {} }),
  status: () => request<VaultStatus>('/api/admin/status'),
  secrets: () => request<Secret[]>('/api/secrets'),
  secret: (id: string) => request<Secret>(`/api/secrets/${encodeURIComponent(id)}`),
  secretVersions: (id: string) => request<SecretVersion[]>(`/api/secrets/${encodeURIComponent(id)}/versions`),
  createSecret: (payload: SecretWritePayload) => request<Secret>('/api/secrets', { method: 'POST', json: payload }),
  updateSecret: (id: string, payload: { displayName?: string; tags?: string[]; description?: string }) =>
    request<Secret>(`/api/secrets/${encodeURIComponent(id)}`, { method: 'PUT', json: payload }),
  rotateSecret: (id: string, payload: Record<string, unknown>) =>
    request<Secret>(`/api/secrets/${encodeURIComponent(id)}/rotate`, { method: 'POST', json: { payload } }),
  setSecretEnabled: (id: string, enabled: boolean) =>
    request<Secret>(`/api/secrets/${encodeURIComponent(id)}/${enabled ? 'enable' : 'disable'}`, { method: 'POST', json: {} }),
  deleteSecret: (id: string) => request<void>(`/api/secrets/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  revealSecret: (id: string, currentPassword: string, reason: string) =>
    request<{ id: string; path: string; version: number; payload: Record<string, unknown> }>(
      `/api/secrets/${encodeURIComponent(id)}/reveal`,
      { method: 'POST', json: { currentPassword, reason } }
    ),
  audit: (params: Record<string, string>) => {
    const query = new URLSearchParams(params);
    return request<AuditPage>(`/api/audit-events/search?${query.toString()}`);
  },
  appStatus: () => request('/api/applications/status'),
  applications: () => request<ApplicationPrincipal[]>('/api/applications'),
  createApplication: (payload: { principal_id: string; display_name?: string }) =>
    request<ApplicationPrincipal>('/api/applications', { method: 'POST', json: payload }),
  grantPermission: (principalId: string, permission: string) =>
    request<ApplicationPrincipal>(`/api/applications/${encodeURIComponent(principalId)}/permissions`, {
      method: 'POST',
      json: { permission }
    }),
  setApplicationStatus: (principalId: string, status: 'ACTIVE' | 'BLOCKED') =>
    request<void>(`/api/applications/${encodeURIComponent(principalId)}/${status === 'ACTIVE' ? 'reactivate' : 'block'}`, {
      method: 'POST',
      json: {}
    }),
  applicationCertificates: () => request<ApplicationCertificate[]>('/api/applications/certificates'),
  applicationTokens: () => request<ApplicationToken[]>('/api/applications/tokens'),
  createApplicationToken: (payload: { name?: string; principal_id: string; ttl_seconds?: number }) =>
    request<ApplicationToken>('/api/applications/tokens', { method: 'POST', json: payload }),
  revokeApplicationToken: (id: number) => request<void>(`/api/applications/tokens/${id}/revoke`, { method: 'POST', json: {} }),
  migrationStatus: () => request<MigrationStatus>('/api/manager-migration/status'),
  migrationDryRun: () => request<MigrationPlan>('/api/manager-migration/dry-run', { method: 'POST', json: {} }),
  migrationRun: () => request<MigrationStatus>('/api/manager-migration/run', { method: 'POST', json: {} })
};

export type SecretWritePayload = {
  path: string;
  displayName: string;
  type: SecretType;
  tags: string[];
  description?: string;
  payload: Record<string, unknown>;
};

function isUnsafe(method: string): boolean {
  return !['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase());
}

function cookie(name: string): string {
  if (typeof document === 'undefined') {
    return '';
  }
  return document.cookie
    .split(';')
    .map((item) => item.trim())
    .find((item) => item.startsWith(`${name}=`))
    ?.slice(name.length + 1) ?? '';
}
