import type {
  AgentLogEvent,
  AlertRow,
  AgentRelease,
  AgentUpdateAttempt,
  AgentUpdatePolicy,
  AgentDashboard,
  AssetMetricDetail,
  AssetMetricsOverview,
  Asset,
  BootstrapState,
  CastrelSignAuditEvent,
  CastrelSignAgent,
  CastrelSignCertificate,
  CastrelSignToken,
  DashboardSummary,
  DeepLink,
  IntegrationConfig,
  InterfaceTraffic,
  LogparserStatus,
  SnmpDashboard
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

async function requestForm<T>(path: string, body: FormData): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'include',
    body
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
  return (await response.json()) as T;
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
  agentDashboard: () => request<AgentDashboard>('/api/dashboards/agent'),
  agentLogs: (range = '1h', severity = 'ALL', limit = 300, assetUid?: string) => {
    const params = new URLSearchParams({ range, severity, limit: String(limit) });
    if (assetUid && assetUid !== 'ALL') {
      params.set('assetUid', assetUid);
    }
    return request<AgentLogEvent[]>(`/api/agent/logs?${params.toString()}`);
  },
  snmpDashboard: () => request<SnmpDashboard>('/api/dashboards/snmp'),
  trafficInterfaces: (range = '1h') =>
    request<InterfaceTraffic[]>(`/api/traffic/interfaces?range=${encodeURIComponent(range)}`),
  assetTrafficInterfaces: (assetId: number, range = '1h') =>
    request<InterfaceTraffic[]>(`/api/traffic/assets/${assetId}/interfaces?range=${encodeURIComponent(range)}`),
  assetMetrics: (range = '1h') =>
    request<AssetMetricsOverview>(`/api/metrics/assets?range=${encodeURIComponent(range)}`),
  assetMetricDetail: (assetUid: string, range = '1h', bucket = 'auto') =>
    request<AssetMetricDetail>(
      `/api/metrics/assets/${encodeURIComponent(assetUid)}?range=${encodeURIComponent(range)}&bucket=${encodeURIComponent(bucket)}`
    ),
  assets: () => request<Asset[]>('/api/assets'),
  createAsset: (payload: { name: string; assetType: string; managementIp?: string; location?: string; description?: string }) =>
    request<Asset>('/api/assets', { method: 'POST', body: JSON.stringify(payload) }),
  updateAsset: (id: number, payload: { name: string; location?: string; description?: string }) =>
    request<Asset>(`/api/assets/${id}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteAsset: (id: number) => request<void>(`/api/assets/${id}`, { method: 'DELETE' }),
  alerts: () => request<AlertRow[]>('/api/alerts'),
  acknowledgeAlert: (id: number) => request<AlertRow>(`/api/alerts/${id}/acknowledge`, { method: 'POST' }),
  resolveAlert: (id: number) => request<AlertRow>(`/api/alerts/${id}/resolve`, { method: 'POST' }),
  castrelSign: () => request<IntegrationConfig>('/api/integrations/castrelsign'),
  castrelSignTokens: () => request<CastrelSignToken[]>('/api/integrations/castrelsign/tokens'),
  castrelSignAgents: () => request<CastrelSignAgent[]>('/api/integrations/castrelsign/agents'),
  castrelSignCertificates: () => request<CastrelSignCertificate[]>('/api/integrations/castrelsign/certificates'),
  castrelSignAuditEvents: () => request<CastrelSignAuditEvent[]>('/api/integrations/castrelsign/audit-events'),
  agentReleases: () => request<AgentRelease[]>('/api/integrations/castrelsign/agent-releases'),
  agentUpdatePolicies: () => request<AgentUpdatePolicy[]>('/api/integrations/castrelsign/agent-update-policies'),
  agentUpdateAttempts: () => request<AgentUpdateAttempt[]>('/api/integrations/castrelsign/agent-update-attempts'),
  createAgentRelease: (payload: { version: string; os: string; arch: string; channel: string; artifact: File }) => {
    const body = new FormData();
    body.set('version', payload.version);
    body.set('os', payload.os);
    body.set('arch', payload.arch);
    body.set('channel', payload.channel);
    body.set('artifact', payload.artifact);
    return requestForm<AgentRelease>('/api/integrations/castrelsign/agent-releases', body);
  },
  activateAgentRelease: (id: number) =>
    request<AgentRelease>(`/api/integrations/castrelsign/agent-releases/${id}/activate`, { method: 'POST' }),
  revokeAgentRelease: (id: number) =>
    request<AgentRelease>(`/api/integrations/castrelsign/agent-releases/${id}/revoke`, { method: 'POST' }),
  updateAgentPolicy: (payload: { agentId?: string; enabled: boolean; channel: string; targetVersion?: string }) =>
    request<AgentUpdatePolicy>('/api/integrations/castrelsign/agent-update-policy', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
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
    agentId?: string;
    tenantId: string;
    ttlSeconds: number;
  }) =>
    requestBlob('/api/integrations/castrelsign/enrollment-packages', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  logparser: () => request<IntegrationConfig>('/api/integrations/logparser'),
  logparserStatus: () => request<LogparserStatus>('/api/integrations/logparser/status'),
  logparserLinks: () => request<DeepLink[]>('/api/integrations/logparser/deep-links')
};
