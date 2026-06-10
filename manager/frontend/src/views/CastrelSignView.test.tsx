import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { CastrelSignView } from './CastrelSignView';

type MockResponse = {
  body?: unknown;
  ok?: boolean;
  status?: number;
  statusText?: string;
};

describe('CastrelSignView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders an agent lifecycle workbench for admins', async () => {
    mockFetch();

    render(<CastrelSignView role="ADMIN" />);

    expect(await screen.findByRole('heading', { name: 'Agent lifecycle' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '새 agent 패키지' })).toBeInTheDocument();
    expect(screen.getAllByText('edge-01').length).toBeGreaterThan(0);
    expect(screen.getAllByText('BLOCKED').length).toBeGreaterThan(0);
    expect(screen.getByText('인증서 만료 임박')).toBeInTheDocument();
    expect(screen.getByText('AGENT_BLOCKED')).toBeInTheDocument();
    expect(screen.queryByText('agent_id')).not.toBeInTheDocument();
  });

  it('creates an enrollment package with an agent-bound token request', async () => {
    const fetchMock = mockFetch({
      '/api/integrations/castrelsign/enrollment-packages': {
        body: new Blob(['zip'], { type: 'application/zip' })
      }
    });
    vi.stubGlobal('URL', {
      createObjectURL: vi.fn(() => 'blob:package'),
      revokeObjectURL: vi.fn()
    });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);

    render(<CastrelSignView role="ADMIN" />);

    fireEvent.click(await screen.findByRole('button', { name: '새 agent 패키지' }));
    fireEvent.change(screen.getByLabelText('Agent ID'), { target: { value: 'edge-02' } });
    fireEvent.change(screen.getByLabelText('Tenant ID'), { target: { value: 'default' } });
    fireEvent.change(screen.getByLabelText('TLS server name'), { target: { value: 'castrelsign' } });
    fireEvent.click(screen.getByRole('button', { name: '패키지 생성' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/integrations/castrelsign/enrollment-packages',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({
            agentId: 'edge-02',
            tenantId: 'default',
            ttlSeconds: 3600,
            maxUses: 1,
            tlsServerName: 'castrelsign'
          })
        })
      );
    });
  });

  it('keeps viewer users read-only', async () => {
    mockFetch();

    render(<CastrelSignView role="VIEWER" />);

    expect(await screen.findByRole('heading', { name: 'Agent lifecycle' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '새 agent 패키지' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Agent 차단' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '재활성 + 새 패키지' })).not.toBeInTheDocument();
  });
});

function mockFetch(overrides: Record<string, MockResponse> = {}) {
  const responses: Record<string, MockResponse> = {
    '/api/integrations/castrelsign': {
      body: {
        serviceName: 'castrelsign',
        baseUrl: 'https://castrelsign:8443',
        secret: { configured: true, masked: '********' },
        enabled: true
      }
    },
    '/api/integrations/castrelsign/tokens': {
      body: [
        {
          id: 1,
          name: 'edge-01 initial',
          agentId: 'edge-01',
          maxUses: 1,
          usedCount: 0,
          expiresAt: '2026-06-09T11:00:00Z',
          revokedAt: '2026-06-09T10:20:00Z'
        }
      ]
    },
    '/api/integrations/castrelsign/agents': {
      body: [
        {
          agentId: 'edge-01',
          hostname: 'edge-host',
          version: '0.1.0',
          status: 'BLOCKED',
          firstSeenAt: '2026-06-09T10:00:00Z',
          lastSeenAt: '2026-06-09T10:05:00Z'
        },
        {
          agentId: 'edge-02',
          hostname: 'edge-next',
          status: 'ACTIVE',
          firstSeenAt: '2026-06-09T10:00:00Z',
          lastSeenAt: '2026-06-09T10:05:00Z'
        }
      ]
    },
    '/api/integrations/castrelsign/certificates': {
      body: [
        {
          id: 7,
          agentId: 'edge-02',
          serialNumber: 'abc123',
          subjectDn: 'CN=edge-02',
          notAfter: soonIso(),
          status: 'ACTIVE',
          issuedAt: '2026-06-09T10:00:00Z'
        }
      ]
    },
    '/api/integrations/castrelsign/audit-events': {
      body: [
        {
          id: 12,
          eventType: 'AGENT_BLOCKED',
          agentId: 'edge-01',
          message: 'blocked by operator',
          createdAt: '2026-06-09T10:20:00Z'
        }
      ]
    },
    ...overrides
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    const response = responses[path] ?? { ok: false, status: 404, statusText: 'Not Found', body: { error: path } };
    const status = response.status ?? 200;
    return {
      ok: response.ok ?? status < 400,
      status,
      statusText: response.statusText ?? 'OK',
      json: async () => response.body,
      blob: async () => response.body
    };
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function soonIso(): string {
  return new Date(Date.now() + 7 * 86_400_000).toISOString();
}
