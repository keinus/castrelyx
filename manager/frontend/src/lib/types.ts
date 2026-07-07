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
  location?: string;
  description?: string;
  status: string;
  firstSeenAt?: string;
  lastSeenAt?: string;
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
  trafficTopInterfaces?: InterfaceTraffic[];
};

export type InterfaceTraffic = {
  assetUid: string;
  interfaceName: string;
  inBps: number;
  outBps: number;
  utilizationPct: number;
  errors: number;
  discards: number;
  status: string;
};

export type MetricPoint = {
  timestamp: string;
  value?: number | null;
  inBps?: number | null;
  outBps?: number | null;
  readBps?: number | null;
  writeBps?: number | null;
  readIops?: number | null;
  writeIops?: number | null;
  utilizationPct?: number | null;
};

export type AssetMetricSummary = {
  id?: number | null;
  assetUid: string;
  name: string;
  assetType: string;
  managementIp?: string | null;
  location?: string | null;
  description?: string | null;
  status?: string | null;
  lastSeenAt?: string | null;
  stale?: boolean;
  health: 'healthy' | 'warning' | 'critical' | 'unknown';
  sources: {
    registered?: boolean;
    agent?: boolean;
    snmp?: boolean;
    traffic?: boolean;
    diskIo?: boolean;
    security?: boolean;
    observed?: boolean;
  };
  metrics: {
    cpuUsagePct?: number | null;
    memoryUsagePct?: number | null;
    memoryTotalBytes?: number | null;
    memoryAvailableBytes?: number | null;
    diskUsagePct?: number | null;
    load1?: number | null;
    load5?: number | null;
    load15?: number | null;
    normalizedLoadPct?: number | null;
    cpuCount?: number | null;
    networkInBps?: number | null;
    networkOutBps?: number | null;
    interfaceErrorCount?: number | null;
    diskReadBps?: number | null;
    diskWriteBps?: number | null;
    diskReadIops?: number | null;
    diskWriteIops?: number | null;
    diskIoUtilizationPct?: number | null;
    temperatureCelsius?: number | null;
  };
  security?: {
    openPorts?: number;
    failedServices?: number;
    firewallDisabled?: number;
    interfacesDown?: number;
    securityEvents?: number;
    events?: AgentEventSummary[];
  };
  signals?: {
    reasons?: Array<{
      code?: string;
      label?: string;
      severity?: 'warning' | 'critical' | string;
      detail?: string | null;
    }>;
    interfacesDown?: number;
    eventCounts?: Record<string, number>;
    lastEventAt?: string | null;
    collectorFreshness?: {
      stale?: boolean;
      lastSeenAt?: string | null;
      ageSeconds?: number;
    };
  };
};

export type AssetMetricsOverview = {
  range: string;
  summary: {
    totalAssets: number;
    observedAssets: number;
    staleAssets: number;
    criticalAssets: number;
    warningAssets: number;
    avgCpuUsagePct?: number | null;
    avgMemoryUsagePct?: number | null;
    maxDiskUsagePct?: number | null;
    maxDiskIoUtilizationPct?: number | null;
    totalNetworkInBps?: number | null;
    totalNetworkOutBps?: number | null;
  };
  assets: AssetMetricSummary[];
};

export type AssetMetricDetail = {
  range: string;
  bucket: string;
  asset: AssetMetricSummary;
  series: {
    cpu?: MetricPoint[];
    memory?: MetricPoint[];
    disk?: MetricPoint[];
    diskIo?: MetricPoint[];
    load?: MetricPoint[];
    network?: MetricPoint[];
  };
  disks?: Array<{
    mountPoint?: string;
    filesystem?: string;
    totalBytes?: number;
    usedBytes?: number;
    availableBytes?: number;
    usedPct?: number;
    device?: string;
    readBps?: number;
    writeBps?: number;
    readIops?: number;
    writeIops?: number;
    ioUtilizationPct?: number;
  }>;
  diskIo?: Array<{
    assetUid?: string;
    device?: string;
    readBps?: number;
    writeBps?: number;
    readIops?: number;
    writeIops?: number;
    ioUtilizationPct?: number;
  }>;
  interfaces?: InterfaceTraffic[];
  processes?: AgentProcessState[];
  sockets?: AgentSocketState[];
  services?: AgentServiceState[];
  firewalls?: AgentFirewallState[];
  interfaceStates?: AgentInterfaceState[];
  security?: AssetMetricSummary['security'];
  collectors?: AgentCollectorSummary[];
};

export type SnmpDashboard = {
  polls?: { success?: number; failure?: number };
  targets?: unknown[];
  interfaces?: InterfaceTraffic[];
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
  };
  agents?: AgentDashboardAgent[];
  collectors?: AgentCollectorSummary[];
  states?: {
    sockets?: AgentSocketState[];
    services?: AgentServiceState[];
    firewalls?: AgentFirewallState[];
    interfaces?: AgentInterfaceState[];
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
  sourceId?: string;
  eventType?: string;
  eventCategory?: string;
  severity?: string;
  message?: string;
  platform?: string;
  sourceName?: string;
  channel?: string;
  program?: string;
  provider?: string;
  pid?: string;
  eventId?: number;
  recordId?: number;
  eventTime?: string;
  actor?: string;
  action?: string;
  outcome?: string;
  dedupKey?: string;
  observedAt?: string;
};

export type AgentLogEvent = AgentEventSummary;

export type AssetFileRoot = {
  name: string;
  path: string;
};

export type AssetFileEntry = {
  name: string;
  path: string;
  type: 'directory' | 'file' | 'symlink' | string;
  directory: boolean;
  sizeBytes: number;
  modifiedAt?: string;
  readOnly: boolean;
  hidden: boolean;
  extension?: string;
};

export type AssetFileListResponse = {
  path: string;
  roots: AssetFileRoot[];
  entries: AssetFileEntry[];
};

export type AssetFileCommand = {
  commandId: string;
  agentId: string;
  operation: string;
  requestJson?: string;
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string;
  responseJson?: string;
  errorMessage?: string;
  createdAt?: string;
  updatedAt?: string;
  expiresAt?: string;
  claimedAt?: string;
  completedAt?: string;
};

export type RemoteAccessSession = {
  id: string;
  assetId?: number | null;
  assetUid?: string | null;
  agentId: string;
  sshUser: string;
  targetHost: string;
  targetPort: number;
  status: string;
  publicKeyFingerprint: string;
  authorizationTaskId?: string | null;
  revokeTaskId?: string | null;
  createdAt: string;
  expiresAt: string;
  connectedAt?: string | null;
  closedAt?: string | null;
  closeReason?: string | null;
  lastError?: string | null;
};

export type AgentSocketState = {
  assetUid?: string;
  sourceId?: string;
  stateKey?: string;
  protocol?: string;
  localAddress?: string;
  localPort?: number;
  remoteAddress?: string;
  remotePort?: number;
  direction?: string;
  state?: string;
  processName?: string;
  processId?: number;
  socketInode?: string;
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

export type AgentInterfaceState = {
  assetUid?: string;
  name?: string;
  operStatus?: string;
  status?: string;
  flags?: string;
  macAddress?: string;
  mtu?: number;
  addresses?: string[];
  observedAt?: string;
};

export type AgentProcessState = {
  assetUid?: string;
  sourceId?: string;
  stateKey?: string;
  pid?: number;
  parentPid?: number;
  name?: string;
  executablePath?: string;
  commandLine?: string;
  user?: string;
  cpuPercent?: number;
  memoryBytes?: number;
  startedAt?: string;
  socketKeys?: string[];
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

export type AgentRelease = {
  id: number;
  version: string;
  os: string;
  arch: string;
  channel: string;
  status: string;
  sha256: string;
  sizeBytes: number;
  createdAt?: string;
  activatedAt?: string;
  revokedAt?: string;
};

export type AgentUpdatePolicy = {
  policyKey?: string;
  agentId?: string;
  enabled: boolean;
  channel: string;
  targetVersion?: string;
  updatedAt?: string;
};

export type AgentUpdateAttempt = {
  id?: number;
  deploymentId: string;
  agentId: string;
  releaseId: number;
  fromVersion?: string;
  status: string;
  message?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type LogparserStatus = Record<string, unknown>;

export type DeepLink = {
  label: string;
  url: string;
};
