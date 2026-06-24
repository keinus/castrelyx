import type { AlertRow, BootstrapState, DashboardSummary, Role, Surface } from './types';

export type MenuItem = {
  id: string;
  label: string;
};

export function nextSurface(state: Pick<BootstrapState, 'setupRequired' | 'authenticated'>): Surface {
  if (state.setupRequired) {
    return 'setup';
  }
  return state.authenticated ? 'console' : 'login';
}

export function menuItemsForRole(role: Role): MenuItem[] {
  const items = [
    ['overview', '개요'],
    ['assets', '자산'],
    ['traffic', 'Traffic'],
    ['agent', 'Agent'],
    ['agentLogs', 'Agent Logs'],
    ['snmp', 'SNMP'],
    ['alerts', '알림'],
    ['castrelsign', 'CastrelSign'],
    ['logparser', 'LogParser'],
    ['settings', '설정']
  ] as const;
  if (role === 'VIEWER') {
    return items.filter(([id]) => id !== 'settings').map(([id, label]) => ({ id, label }));
  }
  return items.map(([id, label]) => ({ id, label }));
}

export function canMutate(role: Role, action: string): boolean {
  if (role === 'ADMIN') {
    return true;
  }
  if (role === 'VIEWER') {
    return false;
  }
  return !action.startsWith('integration:') && !action.startsWith('user:') && !action.startsWith('settings:');
}

export function formatBps(value: number): string {
  if (value >= 1_000_000_000) {
    return `${(value / 1_000_000_000).toFixed(2)} Gbps`;
  }
  if (value >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(2)} Mbps`;
  }
  if (value >= 1_000) {
    return `${(value / 1_000).toFixed(2)} Kbps`;
  }
  return `${Number.isInteger(value) ? value : value.toFixed(2)} bps`;
}

export function summarizeDashboard(summary: DashboardSummary): [string, string][] {
  return [
    ['자산', String(summary.activeAssets)],
    ['Critical', String(summary.criticalAlerts)],
    ['Agent', `${summary.agentHealth.healthy} / ${summary.agentHealth.stale}`],
    ['SNMP', `${summary.snmpPollHealth.success} / ${summary.snmpPollHealth.failure}`]
  ];
}

export function filterAlerts(
  alerts: AlertRow[],
  filter: { severity: AlertRow['severity'] | 'ALL'; status: AlertRow['status'] | 'ALL' }
): AlertRow[] {
  return alerts.filter((alert) => {
    const severityMatches = filter.severity === 'ALL' || alert.severity === filter.severity;
    const statusMatches = filter.status === 'ALL' || alert.status === filter.status;
    return severityMatches && statusMatches;
  });
}
