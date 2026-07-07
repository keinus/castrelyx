export type Session = {
  username: string;
  role?: string;
  requiresPasswordChange: boolean;
  expiresAt?: string;
};

export type SecretType =
  | 'API_TOKEN'
  | 'SERVER_LOGIN'
  | 'SSH_KEY'
  | 'WINDOWS_LOGIN'
  | 'SNMP_V2C'
  | 'SNMP_V3'
  | 'DB_PASSWORD'
  | 'CERTIFICATE_KEY'
  | 'GENERIC';

export type Secret = {
  id: string;
  path: string;
  displayName: string;
  type: SecretType;
  tags: string[];
  description?: string;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
  currentVersion?: number;
  payload: { configured: boolean; masked: string };
};

export type SecretVersion = {
  id: number;
  version: number;
  keyId: string;
  payloadContentHash: string;
  createdAt: string;
  creatorPrincipal: string;
  current: boolean;
};

export type VaultStatus = {
  service: string;
  dataDir: string;
  databasePath: string;
  activeMasterKeyId: string;
  configuredMasterKeyIds: string[];
  tls: {
    serverTlsConfigured: boolean;
    trustStoreConfigured: boolean;
    castrelSignCaConfigured: boolean;
  };
  secrets: {
    total: number;
    enabled: number;
    disabled: number;
    deleted: number;
    versions: number;
  };
  audit: {
    total: number;
    denied: number;
    reveals: number;
    resolves: number;
  };
  castrelSign: IntegrationStatus;
  managerMigration: Record<string, unknown>;
};

export type IntegrationStatus = {
  configured: boolean;
  baseUrl?: string;
  state?: string;
  detail?: string;
};

export type AuditEvent = {
  id: number;
  timestamp: string;
  actorType: string;
  actorId?: string;
  secretPath?: string;
  secretVersion?: number;
  action: string;
  result: string;
  reason?: string;
  sourceMetadata: Record<string, unknown>;
};

export type AuditPage = {
  events: AuditEvent[];
  total: number;
  limit: number;
  offset: number;
};

export type ApplicationPrincipal = {
  principalId: string;
  displayName?: string;
  status: string;
  permissions?: string[];
  createdAt?: string;
  updatedAt?: string;
};

export type ApplicationCertificate = {
  principalId: string;
  serialNumber: string;
  subjectDn?: string;
  notBefore?: string;
  notAfter?: string;
  status: string;
  issuedAt?: string;
};

export type ApplicationToken = {
  id: number;
  name?: string;
  principalId: string;
  expiresAt?: string;
  usedAt?: string;
  revokedAt?: string;
  token?: string;
};

export type MigrationStatus = {
  vaultEnabled?: boolean;
  pendingIntegrationSecrets?: number;
  pendingSnmpCredentials?: number;
  migratedIntegrationSecrets?: number;
  migratedSnmpCredentials?: number;
  status?: string;
  configured?: boolean;
  detail?: string;
};

export type MigrationPlan = MigrationStatus & {
  integrations?: PendingSecret[];
  snmpCredentials?: PendingSecret[];
};

export type PendingSecret = {
  source: string;
  name: string;
  vaultPath: string;
  type: string;
};
