import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AgentDashboardView } from './AgentDashboardView';

describe('AgentDashboardView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders live agent telemetry from the manager API', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => ({
        heartbeat: { healthy: 1, stale: 0, lastSeenAt: '2026-06-11T13:34:00Z' },
        securityPosture: {
          exposedPorts: 1,
          failedServices: 1,
          firewallDisabled: 1
        },
        agents: [
          { assetUid: 'nas', sourceId: 'nas', lastSeenAt: '2026-06-11T13:34:00Z' }
        ],
        collectors: [
          { name: 'package', sampleCount: 15477, lastSeenAt: '2026-06-11T13:34:00Z' }
        ],
        states: {
          sockets: [
            {
              assetUid: 'nas',
              protocol: 'tcp',
              localAddress: '0.0.0.0',
              localPort: 22,
              direction: 'listening',
              processName: 'sshd',
              observedAt: '2026-06-11T13:34:00Z'
            }
          ],
          services: [
            {
              assetUid: 'nas',
              name: 'ssh.service',
              status: 'failed',
              observedAt: '2026-06-11T13:34:00Z'
            }
          ],
          firewalls: [
            {
              assetUid: 'nas',
              backend: 'ufw',
              enabled: false,
              observedAt: '2026-06-11T13:34:00Z'
            }
          ]
        },
        resources: {
          metrics: [
            { assetUid: 'nas', metricName: 'disk.usage', value: 72.4, unit: 'percent', observedAt: '2026-06-11T13:34:00Z' }
          ]
        }
      })
    }));
    vi.stubGlobal('fetch', fetchMock);

    render(<AgentDashboardView />);

    expect(await screen.findByText('보안 관제')).toBeInTheDocument();
    expect((await screen.findAllByText('nas')).length).toBeGreaterThan(0);
    expect(screen.getByText('package')).toBeInTheDocument();
    expect(screen.getByText('disk.usage')).toBeInTheDocument();
    expect(screen.getByText('공격면')).toBeInTheDocument();
    expect(screen.getByText('0.0.0.0:22')).toBeInTheDocument();
    expect(screen.getByText('sshd')).toBeInTheDocument();
    expect(screen.getByText('ssh.service')).toBeInTheDocument();
    expect(screen.getByText('방화벽 비활성')).toBeInTheDocument();
    expect(screen.queryByText('SSH login failed for alice')).not.toBeInTheDocument();
    expect(screen.queryByText('Recent security events')).not.toBeInTheDocument();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/dashboards/agent',
        expect.objectContaining({ credentials: 'include' })
      );
    });
  });
});
