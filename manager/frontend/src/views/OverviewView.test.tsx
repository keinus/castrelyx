import { act, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AgentDashboard, AlertRow, DashboardSummary, SnmpDashboard } from '../lib/types';
import { OverviewView } from './OverviewView';

describe('OverviewView', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders the integrated NMS and security operations home', async () => {
    mockFetch({
      '/api/dashboards/agent': {
        body: {
          heartbeat: { healthy: 2, stale: 1, lastSeenAt: '2026-06-11T13:34:00Z' },
          securityPosture: { exposedPorts: 1, failedServices: 1, firewallDisabled: 1, securityEvents: 1 },
          collectors: [{ name: 'socket', sampleCount: 12, lastSeenAt: '2026-06-11T13:34:00Z' }],
          states: {
            sockets: [{
              assetUid: 'nas',
              localAddress: '0.0.0.0',
              localPort: 22,
              direction: 'listening',
              processName: 'sshd'
            }],
            services: [{ assetUid: 'nas', name: 'ssh.service', status: 'failed' }],
            firewalls: [{ assetUid: 'nas', backend: 'ufw', enabled: false }]
          },
          events: [{
            assetUid: 'nas',
            eventType: 'auth',
            severity: 'WARNING',
            message: 'SSH login failed for alice',
            observedAt: '2026-06-11T13:33:00Z'
          }]
        } satisfies AgentDashboard
      },
      '/api/dashboards/snmp': {
        body: {
          polls: { success: 4, failure: 1 },
          targets: ['edge-router'],
          interfaces: [{
            assetUid: 'edge-router',
            interfaceName: 'wan0',
            inBps: 2400000,
            outBps: 1800000,
            utilizationPct: 42.5,
            errors: 2,
            discards: 1,
            status: 'up'
          }]
        } satisfies SnmpDashboard
      },
      '/api/traffic/interfaces?range=1h': {
        body: [{
          assetUid: 'nas',
          interfaceName: 'enp2s0',
          inBps: 4621.02,
          outBps: 11663.97,
          utilizationPct: 0,
          errors: 0,
          discards: 0,
          status: 'up'
        }]
      }
    });

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('heading', { name: 'NMS 보안 통합 관제' })).toBeInTheDocument();
    expect(screen.getByText('Network Health')).toBeInTheDocument();
    expect(screen.getByText('Security Posture')).toBeInTheDocument();
    expect(screen.getByText('Response Queue')).toBeInTheDocument();
    expect(screen.getByText('enp2s0')).toBeInTheDocument();
    expect(screen.getByText('11.66 Kbps')).toBeInTheDocument();
    expect(screen.getByText('0.0.0.0:22')).toBeInTheDocument();
    expect(screen.getByText('ssh.service')).toBeInTheDocument();
    expect(screen.getAllByText('SSH login failed for alice').length).toBeGreaterThan(0);
    expect(screen.getByText('CPU threshold exceeded')).toBeInTheDocument();

    const criticalCard = screen.getByText('Critical').closest('section');
    expect(criticalCard).not.toBeNull();
    expect(within(criticalCard as HTMLElement).getByText('1')).toBeInTheDocument();
  });

  it('shows available traffic when a detail dashboard request stalls', async () => {
    vi.useFakeTimers();
    mockFetch({
      '/api/dashboards/agent': { pending: true },
      '/api/dashboards/snmp': {
        body: {
          polls: { success: 4, failure: 0 },
          targets: ['edge-router'],
          interfaces: []
        } satisfies SnmpDashboard
      },
      '/api/traffic/interfaces?range=1h': {
        body: [{
          assetUid: 'nas',
          interfaceName: 'enp2s0',
          inBps: 1000,
          outBps: 2000,
          utilizationPct: 0,
          errors: 0,
          discards: 0,
          status: 'up'
        }]
      }
    });

    render(<OverviewView summary={{ ...summary, trafficTopInterfaces: [] }} alerts={[]} />);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100);
    });

    expect(screen.getByText('enp2s0')).toBeInTheDocument();
    expect(screen.getByText('일부 관제 정보를 불러오지 못했습니다. 수집된 기본 신호로 상황판을 표시합니다.')).toBeInTheDocument();
    expect(screen.queryByText('관제 상세 정보를 갱신하는 중입니다.')).not.toBeInTheDocument();
  });

  it('keeps the overview usable when detail dashboards fail', async () => {
    mockFetch({
      '/api/dashboards/agent': { status: 500, body: {} },
      '/api/dashboards/snmp': { status: 500, body: {} },
      '/api/traffic/interfaces?range=1h': { status: 500, body: {} }
    });

    render(<OverviewView summary={{ ...summary, trafficTopInterfaces: [] }} alerts={[]} />);

    expect(await screen.findByText('일부 관제 정보를 불러오지 못했습니다. 수집된 기본 신호로 상황판을 표시합니다.')).toBeInTheDocument();
    expect(screen.getByText('전체 자산')).toBeInTheDocument();
    expect(screen.getByText('트래픽 신호 대기')).toBeInTheDocument();
    expect(screen.getByText('대응 대기 없음')).toBeInTheDocument();
  });
});

const summary: DashboardSummary = {
  activeAssets: 7,
  criticalAlerts: 3,
  agentHealth: { healthy: 1, stale: 1 },
  snmpPollHealth: { success: 2, failure: 1 },
  trafficTopInterfaces: []
};

const alerts: AlertRow[] = [
  { id: 1, severity: 'CRITICAL', status: 'ACTIVE', title: 'CPU threshold exceeded' },
  { id: 2, severity: 'WARNING', status: 'ACTIVE', title: 'Interface error spike' },
  { id: 3, severity: 'CRITICAL', status: 'RESOLVED', title: 'Resolved critical' }
];

type MockResponse = {
  body?: unknown;
  pending?: boolean;
  status?: number;
};

function mockFetch(responses: Record<string, MockResponse>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    const response = responses[path] ?? { status: 404, body: {} };
    if (response.pending) {
      return new Promise(() => undefined);
    }
    const status = response.status ?? 200;
    return {
      ok: status < 400,
      status,
      statusText: status < 400 ? 'OK' : 'Error',
      json: async () => response.body
    };
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
