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

export type AgentDashboard = {
  heartbeat?: {
    healthy?: number;
    stale?: number;
    lastSeenAt?: string;
  };
  securityPosture?: {
    exposedPorts?: number;
    failedServices?: number;
    firewallDisabled?: number;
    securityEvents?: number;
  };
  agents?: AgentDashboardAgent[];
  collectors?: AgentCollectorSummary[];
  states?: {
    sockets?: AgentSocketState[];
    services?: AgentServiceState[];
    firewalls?: AgentFirewallState[];
    processes?: AgentProcessState[];
    packages?: AgentPackageState[];
  };
  resources?: {
    metrics?: AgentMetricSummary[];
  };
  events?: AgentEventSummary[];
};

export type AgentDashboardAgent = {
  assetUid?: string;
  sourceId?: string;
  lastSeenAt?: string;
};

export type AgentCollectorSummary = {
  name?: string;
  sampleCount?: number;
  lastSeenAt?: string;
};

export type AgentMetricSummary = {
  assetUid?: string;
  metricName?: string;
  value?: number;
  unit?: string;
  observedAt?: string;
};

export type AgentEventSummary = {
  assetUid?: string;
  eventType?: string;
  severity?: string;
  message?: string;
  sourceName?: string;
  outcome?: string;
  observedAt?: string;
};

export type AgentSocketState = {
  assetUid?: string;
  protocol?: string;
  localAddress?: string;
  localPort?: number;
  remoteAddress?: string;
  remotePort?: number;
  direction?: string;
  state?: string;
  processName?: string;
  processId?: number;
  observedAt?: string;
};

export type AgentServiceState = {
  assetUid?: string;
  name?: string;
  displayName?: string;
  status?: string;
  startupType?: string;
  observedAt?: string;
};

export type AgentFirewallState = {
  assetUid?: string;
  backend?: string;
  profile?: string;
  enabled?: boolean;
  ruleCount?: number;
  observedAt?: string;
};

export type AgentProcessState = {
  assetUid?: string;
  pid?: number;
  name?: string;
  user?: string;
  memoryBytes?: number;
  listeningSocketCount?: number;
  connectedSocketCount?: number;
  observedAt?: string;
};

export type AgentPackageState = {
  assetUid?: string;
  name?: string;
  version?: string;
  vendor?: string;
  source?: string;
  observedAt?: string;
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
