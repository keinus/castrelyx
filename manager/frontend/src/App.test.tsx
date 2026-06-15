import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

describe('App shell', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders the NMS console as the first authenticated surface', async () => {
    mockFetch();

    render(<App bootstrap={{ setupRequired: false, authenticated: true, user: { role: 'ADMIN', username: 'admin' } }} />);

    expect(await screen.findByRole('heading', { name: 'Castrelyx Manager' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'NMS 보안 통합 관제' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Traffic/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CastrelSign' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'LogParser' })).toBeInTheDocument();
  });

  it('opens the LogParser UI in a new tab from the sidebar menu', async () => {
    const fetchMock = mockFetch({
      '/api/integrations/logparser/deep-links': {
        body: [{ label: 'Pipeline', url: 'http://192.168.50.21:8765/' }]
      }
    });
    const popup = { close: vi.fn(), location: { href: '' }, opener: window };
    const openMock = vi.fn(() => popup);
    vi.stubGlobal('open', openMock);

    render(<App bootstrap={{ setupRequired: false, authenticated: true, user: { role: 'ADMIN', username: 'admin' } }} />);

    fireEvent.click(await screen.findByRole('button', { name: 'LogParser' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/integrations/logparser/deep-links',
        expect.objectContaining({ credentials: 'include' })
      );
      expect(openMock).toHaveBeenCalledWith('about:blank', '_blank');
      expect(popup.opener).toBeNull();
      expect(popup.location.href).toBe('http://192.168.50.21:8765/');
    });
    expect(screen.queryByRole('heading', { name: 'LogParser' })).not.toBeInTheDocument();
  });
});

type MockResponse = {
  body?: unknown;
  ok?: boolean;
  status?: number;
  statusText?: string;
};

function mockFetch(overrides: Record<string, MockResponse> = {}) {
  const responses: Record<string, MockResponse> = {
    '/api/dashboards/overview': {
      body: {
        activeAssets: 0,
        criticalAlerts: 0,
        agentHealth: { healthy: 0, stale: 0 },
        snmpPollHealth: { success: 0, failure: 0 },
        trafficTopInterfaces: []
      }
    },
    '/api/dashboards/agent': { body: { heartbeat: { healthy: 0, stale: 0 }, collectors: [], events: [] } },
    '/api/dashboards/snmp': { body: { polls: { success: 0, failure: 0 }, targets: [], interfaces: [] } },
    '/api/traffic/interfaces?range=1h': { body: [] },
    '/api/assets': { body: [] },
    '/api/alerts': { body: [] },
    ...overrides
  };
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    const response = responses[path] ?? { ok: false, status: 404, statusText: 'Not Found', body: { error: path } };
    const status = response.status ?? 200;
    return {
      ok: response.ok ?? status < 400,
      status,
      statusText: response.statusText ?? 'OK',
      json: async () => response.body
    };
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}
