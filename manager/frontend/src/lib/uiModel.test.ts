import { describe, expect, it } from 'vitest';
import {
  canMutate,
  filterAlerts,
  formatBps,
  menuItemsForRole,
  nextSurface,
  summarizeDashboard
} from './uiModel';
import type { AlertRow } from './types';

describe('manager UI model', () => {
  it('chooses setup, login, or console surface from bootstrap state', () => {
    expect(nextSurface({ setupRequired: true, authenticated: false })).toBe('setup');
    expect(nextSurface({ setupRequired: false, authenticated: false })).toBe('login');
    expect(nextSurface({ setupRequired: false, authenticated: true })).toBe('console');
  });

  it('keeps viewer read-only while operator and admin can mutate operations', () => {
    expect(menuItemsForRole('ADMIN').map((item) => item.id)).toEqual([
      'overview',
      'assets',
      'traffic',
      'agent',
      'snmp',
      'alerts',
      'castrelsign',
      'logparser',
      'settings'
    ]);
    expect(menuItemsForRole('VIEWER').map((item) => item.id)).toContain('assets');
    expect(menuItemsForRole('VIEWER').map((item) => item.id)).not.toContain('integrations');
    expect(canMutate('VIEWER', 'asset:create')).toBe(false);
    expect(canMutate('OPERATOR', 'asset:create')).toBe(true);
    expect(canMutate('OPERATOR', 'integration:update-secret')).toBe(false);
    expect(canMutate('ADMIN', 'integration:update-secret')).toBe(true);
  });

  it('formats traffic rates and dashboard summaries for compact cards', () => {
    expect(formatBps(1200000)).toBe('1.20 Mbps');
    expect(formatBps(900)).toBe('900 bps');
    expect(summarizeDashboard({
      activeAssets: 12,
      criticalAlerts: 2,
      agentHealth: { healthy: 8, stale: 1 },
      snmpPollHealth: { success: 5, failure: 1 }
    })).toEqual([
      ['자산', '12'],
      ['Critical', '2'],
      ['Agent', '8 / 1'],
      ['SNMP', '5 / 1']
    ]);
  });

  it('filters alert rows by severity and status', () => {
    const alerts: AlertRow[] = [
      { id: 1, severity: 'CRITICAL', status: 'ACTIVE', title: 'cpu' },
      { id: 2, severity: 'WARNING', status: 'ACKNOWLEDGED', title: 'snmp' }
    ];

    expect(filterAlerts(alerts, { severity: 'CRITICAL', status: 'ACTIVE' })).toHaveLength(1);
    expect(filterAlerts(alerts, { severity: 'ALL', status: 'ACKNOWLEDGED' })[0].title).toBe('snmp');
  });
});
