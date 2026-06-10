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
  id: number;
  name?: string;
  token?: string;
  agentId?: string;
  maxUses?: number;
  usedCount?: number;
  expiresAt?: string;
  revokedAt?: string;
  createdAt?: string;
  lastUsedAt?: string;
  lastUsedAgentId?: string;
};

export type CastrelSignAgent = {
  agentId: string;
  hostname?: string;
  version?: string;
  status?: string;
  firstSeenAt?: string;
  lastSeenAt?: string;
};

export type CastrelSignCertificate = {
  id?: number;
  agentId?: string;
  serialNumber?: string;
  subjectDn?: string;
  notBefore?: string;
  notAfter?: string;
  status?: string;
  issuedAt?: string;
};

export type CastrelSignAuditEvent = {
  id?: number;
  eventType?: string;
  agentId?: string;
  message?: string;
  createdAt?: string;
};

export type LogparserStatus = Record<string, unknown>;

export type DeepLink = {
  label: string;
  url: string;
};
