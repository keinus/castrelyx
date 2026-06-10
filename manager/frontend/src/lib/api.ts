import type {
  AlertRow,
  Asset,
  BootstrapState,
  CastrelSignAuditEvent,
  CastrelSignAgent,
  CastrelSignCertificate,
  CastrelSignToken,
  DashboardSummary,
  DeepLink,
  IntegrationConfig,
  LogparserStatus
} from './types';

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function requestBlob(path: string, init?: RequestInit): Promise<Blob> {
  const response = await fetch(path, {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
    ...init
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return response.blob();
}

export async function bootstrap(): Promise<BootstrapState> {
  const setup = await request<{ required: boolean }>('/api/setup/status');
  if (setup.required) {
    return { setupRequired: true, authenticated: false };
  }
  const session = await request<{ authenticated: boolean; user?: BootstrapState['user'] }>('/api/auth/session');
  return { setupRequired: false, authenticated: session.authenticated, user: session.user };
}

export const api = {
  createAdmin: (payload: { username: string; password: string; displayName?: string }) =>
    request('/api/setup/admin', { method: 'POST', body: JSON.stringify(payload) }),
  login: (payload: { username: string; password: string }) =>
    request<{ authenticated: boolean; user: BootstrapState['user'] }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  logout: () => request('/api/auth/logout', { method: 'POST' }),
  overview: () => request<DashboardSummary>('/api/dashboards/overview'),
  assets: () => request<Asset[]>('/api/assets'),
  createAsset: (payload: { name: string; assetType: string; managementIp?: string; description?: string }) =>
    request<Asset>('/api/assets', { method: 'POST', body: JSON.stringify(payload) }),
  alerts: () => request<AlertRow[]>('/api/alerts'),
  acknowledgeAlert: (id: number) => request<AlertRow>(`/api/alerts/${id}/acknowledge`, { method: 'POST' }),
  resolveAlert: (id: number) => request<AlertRow>(`/api/alerts/${id}/resolve`, { method: 'POST' }),
  castrelSign: () => request<IntegrationConfig>('/api/integrations/castrelsign'),
  castrelSignTokens: () => request<CastrelSignToken[]>('/api/integrations/castrelsign/tokens'),
  castrelSignAgents: () => request<CastrelSignAgent[]>('/api/integrations/castrelsign/agents'),
  castrelSignCertificates: () => request<CastrelSignCertificate[]>('/api/integrations/castrelsign/certificates'),
  castrelSignAuditEvents: () => request<CastrelSignAuditEvent[]>('/api/integrations/castrelsign/audit-events'),
  createCastrelSignToken: (payload: { name?: string; agentId?: string; ttlSeconds?: number; maxUses?: number } = {}) =>
    request<CastrelSignToken>('/api/integrations/castrelsign/tokens', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  revokeCastrelSignToken: (id: number) =>
    request(`/api/integrations/castrelsign/tokens/${id}/revoke`, { method: 'POST' }),
  blockCastrelSignAgent: (agentId: string) =>
    request(`/api/integrations/castrelsign/agents/${encodeURIComponent(agentId)}/block`, { method: 'POST' }),
  reactivateCastrelSignAgent: (agentId: string) =>
    request(`/api/integrations/castrelsign/agents/${encodeURIComponent(agentId)}/reactivate`, { method: 'POST' }),
  createCastrelSignEnrollmentPackage: (payload: {
    agentId: string;
    tenantId: string;
    ttlSeconds: number;
    maxUses: number;
    tlsServerName: string;
  }) =>
    requestBlob('/api/integrations/castrelsign/enrollment-packages', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  logparser: () => request<IntegrationConfig>('/api/integrations/logparser'),
  logparserStatus: () => request<LogparserStatus>('/api/integrations/logparser/status'),
  logparserLinks: () => request<DeepLink[]>('/api/integrations/logparser/deep-links')
};
