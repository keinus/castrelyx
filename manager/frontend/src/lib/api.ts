import type { AlertRow, Asset, BootstrapState, DashboardSummary } from './types';

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
  castrelSign: () => request('/api/integrations/castrelsign'),
  logparserLinks: () => request<{ label: string; url: string }[]>('/api/integrations/logparser/deep-links')
};
