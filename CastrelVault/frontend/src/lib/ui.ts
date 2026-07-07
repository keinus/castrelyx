import type { SecretType } from './types';

export const secretTypes: SecretType[] = [
  'API_TOKEN',
  'SERVER_LOGIN',
  'SSH_KEY',
  'WINDOWS_LOGIN',
  'SNMP_V2C',
  'SNMP_V3',
  'DB_PASSWORD',
  'CERTIFICATE_KEY',
  'GENERIC'
];

export function formatDate(value?: string): string {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
}

export function statusTone(value?: string): 'good' | 'warning' | 'danger' | 'neutral' {
  const normalized = (value ?? '').toUpperCase();
  if (['ACTIVE', 'AVAILABLE', 'ALLOWED', 'ENABLED', 'READY', 'MIGRATED'].includes(normalized)) {
    return 'good';
  }
  if (['BLOCKED', 'DENIED', 'DISABLED', 'UNAVAILABLE', 'CONFLICT'].includes(normalized)) {
    return 'danger';
  }
  if (['UNCONFIGURED', 'PENDING', 'DRY-RUN'].includes(normalized)) {
    return 'warning';
  }
  return 'neutral';
}

export function splitTags(value: string): string[] {
  return value.split(',').map((tag) => tag.trim()).filter(Boolean);
}
