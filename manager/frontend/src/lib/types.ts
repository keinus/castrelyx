export type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER';

export type User = {
  id?: number;
  username: string;
  displayName?: string;
  role: Role;
};

export type BootstrapState = {
  setupRequired: boolean;
  authenticated: boolean;
  user?: User;
};

export type Asset = {
  id: number;
  assetUid: string;
  name: string;
  assetType: string;
  managementIp?: string;
  status: string;
};

export type AlertRow = {
  id: number;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  status: 'ACTIVE' | 'ACKNOWLEDGED' | 'RESOLVED';
  title: string;
  detail?: string;
};

export type DashboardSummary = {
  activeAssets: number;
  criticalAlerts: number;
  agentHealth: { healthy: number; stale: number };
  snmpPollHealth: { success: number; failure: number };
};

export type Surface = 'setup' | 'login' | 'console';

export type SecretMask = {
  configured: boolean;
  masked: string;
};

export type IntegrationConfig = {
  serviceName: string;
  baseUrl: string;
  secret: SecretMask;
  enabled: boolean;
};

export type CastrelSignToken = {
  id?: number;
  token?: string;
  description?: string;
  revoked?: boolean;
  expiresAt?: string;
  createdAt?: string;
  [key: string]: unknown;
};

export type CastrelSignAgent = {
  agentId?: string;
  hostname?: string;
  status?: string;
  [key: string]: unknown;
};

export type CastrelSignCertificate = {
  serialNumber?: string;
  subject?: string;
  status?: string;
  [key: string]: unknown;
};

export type LogparserStatus = Record<string, unknown>;

export type DeepLink = {
  label: string;
  url: string;
};
