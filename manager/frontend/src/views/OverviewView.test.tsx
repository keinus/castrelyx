import { act, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AlertRow, Asset, AssetMetricDetail, AssetMetricsOverview, DashboardSummary } from '../lib/types';
import { OverviewView } from './OverviewView';

describe('OverviewView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders an asset-first operations overview', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { body: assetMetrics },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: nasDetail },
      '/api/agent/logs?range=24h&severity=ALL&limit=20&assetUid=nas': {
        body: [
          {
            assetUid: 'nas',
            eventType: 'filesystem',
            severity: 'ERROR',
            message: 'Root filesystem full',
            observedAt: '2026-07-06T11:58:00Z'
          },
          {
            assetUid: 'nas',
            eventType: 'kernel',
            severity: 'WARNING',
            message: 'Thermal zone warning',
            observedAt: '2026-07-06T11:57:00Z'
          },
          {
            assetUid: 'nas',
            eventType: 'collector',
            severity: 'INFO',
            message: 'Collector heartbeat',
            observedAt: '2026-07-06T11:56:00Z'
          }
        ]
      }
    });

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('heading', { name: '개요' })).toBeInTheDocument();
    expect(screen.getByText('지금 확인해야 할 장비와 그 이유를 먼저 보여줍니다.')).toBeInTheDocument();
    expect(screen.getByText('전체 장비')).toBeInTheDocument();
    expect(screen.getByText('문제 장비')).toBeInTheDocument();
    expect(screen.getAllByText('수집 지연').length).toBeGreaterThan(0);
    expect(screen.getByText('최근 수집')).toBeInTheDocument();

    const board = screen.getByRole('heading', { name: '장비 상태 보드' }).closest('[data-slot="card"]');
    expect(board).not.toBeNull();
    expect(within(board as HTMLElement).getByText('상태')).toBeInTheDocument();
    expect(within(board as HTMLElement).getByText('장비')).toBeInTheDocument();
    expect(within(board as HTMLElement).getByText('마지막 수집')).toBeInTheDocument();
    expect(within(board as HTMLElement).getByText('문제 근거')).toBeInTheDocument();
    expect(within(board as HTMLElement).getByText('CPU/MEM/DISK/TEMP')).toBeInTheDocument();
    expect(within(board as HTMLElement).getByText('RX/TX')).toBeInTheDocument();
    expect(within(board as HTMLElement).queryByText('노출/서비스')).not.toBeInTheDocument();
    expect(screen.getAllByText('nas').length).toBeGreaterThan(0);
    expect(screen.getByText('x86host')).toBeInTheDocument();
    expect(screen.getByText('security')).toBeInTheDocument();
    expect(screen.getAllByText(/CPU 92\.1%/).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/방화벽 비활성/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('TEMP').length).toBeGreaterThan(0);

    expect(await screen.findByText('Root filesystem full')).toBeInTheDocument();
    expect(screen.getByText('Thermal zone warning')).toBeInTheDocument();
    expect(screen.getByText('ERROR')).toBeInTheDocument();
    expect(screen.getByText('WARNING')).toBeInTheDocument();
    expect(screen.queryByText('Collector heartbeat')).not.toBeInTheDocument();
    expect(screen.queryByText('Listening ports')).not.toBeInTheDocument();
    expect(screen.queryByText('sshd')).not.toBeInTheDocument();
    expect(screen.getByText('Failed services')).toBeInTheDocument();
    expect(screen.getByText('nginx.service')).toBeInTheDocument();
    expect(screen.getByText('Firewall')).toBeInTheDocument();
    expect(screen.getByText('ufw')).toBeInTheDocument();
    expect(screen.getByText('Interfaces down')).toBeInTheDocument();
    expect(screen.getByText('eth1')).toBeInTheDocument();

    expect(screen.queryByText('운영 부하')).not.toBeInTheDocument();
    expect(screen.queryByText('서비스 헬스')).not.toBeInTheDocument();
    expect(screen.queryByText('장비별 노출 포트')).not.toBeInTheDocument();
    expect(screen.queryByText('Agent 현황')).not.toBeInTheDocument();
    expect(screen.queryByText('자산 리소스 Top')).not.toBeInTheDocument();
    expect(screen.queryByText('최근 Critical 로그')).not.toBeInTheDocument();
  });

  it('refreshes overview and selected resource series on the collection cadence', async () => {
    let intervalHandler: TimerHandler | undefined;
    const originalSetInterval = window.setInterval.bind(window);
    vi.spyOn(window, 'setInterval').mockImplementation((handler, timeout) => {
      if (timeout === 30_000) {
        intervalHandler = handler;
        return 1 as unknown as NodeJS.Timeout;
      }
      return originalSetInterval(handler, timeout) as unknown as NodeJS.Timeout;
    });

    const refreshedAssetMetrics: AssetMetricsOverview = {
      ...assetMetrics,
      assets: assetMetrics.assets.map((asset) => asset.assetUid === 'nas'
        ? {
            ...asset,
            lastSeenAt: '2026-07-06T12:00:30Z',
            metrics: { ...asset.metrics, cpuUsagePct: 45 }
          }
        : asset)
    };
    const refreshedNasDetail: AssetMetricDetail = {
      ...nasDetail,
      asset: refreshedAssetMetrics.assets[0],
      series: {
        ...nasDetail.series,
        cpu: [...(nasDetail.series.cpu ?? []), { timestamp: '2026-07-06T12:00:30Z', value: 45 }]
      }
    };

    let overviewCalls = 0;
    let detailCalls = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === '/api/metrics/assets?range=1h') {
        overviewCalls += 1;
        return mockJsonResponse(overviewCalls === 1 ? assetMetrics : refreshedAssetMetrics);
      }
      if (path === '/api/metrics/assets/nas?range=1h&bucket=auto') {
        detailCalls += 1;
        return mockJsonResponse(detailCalls === 1 ? nasDetail : refreshedNasDetail);
      }
      if (path === '/api/agent/logs?range=24h&severity=ALL&limit=20&assetUid=nas') {
        return mockJsonResponse([]);
      }
      return mockJsonResponse({}, 404);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('heading', { name: '개요' })).toBeInTheDocument();
    await waitFor(() => expect(detailCalls).toBe(1));
    expect(intervalHandler).toEqual(expect.any(Function));

    await act(async () => {
      if (typeof intervalHandler === 'function') {
        intervalHandler();
      }
    });

    await waitFor(() => expect(overviewCalls).toBe(2));
    await waitFor(() => expect(detailCalls).toBe(2));
    await waitFor(() => expect(screen.getAllByText('45.0%').length).toBeGreaterThan(0));
  });

  it('renders zero-valued CPU samples as a collected series', async () => {
    const zeroCpuDetail: AssetMetricDetail = {
      ...nasDetail,
      asset: {
        ...nasDetail.asset,
        metrics: { ...nasDetail.asset.metrics, cpuUsagePct: 0 }
      },
      series: {
        ...nasDetail.series,
        cpu: [
          { timestamp: '2026-07-06T11:50:00Z', value: 0 },
          { timestamp: '2026-07-06T12:00:00Z', value: 0 }
        ]
      }
    };
    mockFetch({
      '/api/metrics/assets?range=1h': { body: assetMetrics },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: zeroCpuDetail },
      '/api/agent/logs?range=24h&severity=ALL&limit=20&assetUid=nas': { body: [] }
    });

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('img', { name: 'CPU 시계열' })).toBeInTheDocument();
    expect(screen.queryByText('series 대기')).not.toBeInTheDocument();
  });

  it('falls back to registered assets when asset metrics fail', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { status: 500, body: {} },
      '/api/metrics/assets/registered-only?range=1h&bucket=auto': { status: 500, body: {} },
      '/api/agent/logs?range=24h&severity=ALL&limit=20&assetUid=registered-only': { status: 500, body: {} }
    });

    render(<OverviewView summary={summary} alerts={[]} assets={[registeredAsset]} />);

    expect(await screen.findByText('장비 상태 정보를 불러오지 못했습니다. 등록 자산 기준으로 표시합니다.')).toBeInTheDocument();
    expect(screen.getAllByText('registered-only').length).toBeGreaterThan(0);
    expect(screen.getAllByText('수집 지연').length).toBeGreaterThan(0);
    expect(screen.getAllByText('수집 데이터 없음').length).toBeGreaterThan(0);
    expect(screen.getByText('선택 장비의 일부 진단 정보를 불러오지 못했습니다.')).toBeInTheDocument();
  });
});

const summary: DashboardSummary = {
  activeAssets: 3,
  criticalAlerts: 1,
  agentHealth: { healthy: 2, stale: 1 },
  snmpPollHealth: { success: 0, failure: 0 },
  trafficTopInterfaces: []
};

const alerts: AlertRow[] = [
  { id: 1, severity: 'CRITICAL', status: 'ACTIVE', title: 'CPU threshold exceeded' },
  { id: 2, severity: 'WARNING', status: 'ACTIVE', title: 'Interface down' }
];

const assetMetrics: AssetMetricsOverview = {
  range: '1h',
  summary: {
    totalAssets: 3,
    observedAssets: 3,
    staleAssets: 1,
    criticalAssets: 1,
    warningAssets: 1,
    totalNetworkInBps: 23500,
    totalNetworkOutBps: 15200
  },
  assets: [
    {
      assetUid: 'nas',
      name: 'nas',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.21',
      location: 'rack-a',
      status: 'active',
      lastSeenAt: '2026-07-06T12:00:00Z',
      stale: false,
      health: 'critical',
      sources: { registered: true, agent: true, traffic: true, diskIo: true, security: true, observed: true },
      metrics: {
        cpuUsagePct: 92.1,
        memoryUsagePct: 71.4,
        diskUsagePct: 86.2,
        diskIoUtilizationPct: 88.8,
        temperatureCelsius: 82.4,
        networkInBps: 12000,
        networkOutBps: 9000
      },
      security: {
        openPorts: 21,
        failedServices: 1,
        firewallDisabled: 1,
        interfacesDown: 1,
        securityEvents: 3
      },
      signals: {
        reasons: [
          { code: 'cpu_usage', label: 'CPU', severity: 'critical', detail: '92.1%' },
          { code: 'firewall_disabled', label: '방화벽 비활성', severity: 'warning', detail: '1 disabled' },
          { code: 'event_error', label: 'ERROR 이벤트', severity: 'critical', detail: '2 events' }
        ],
        interfacesDown: 1,
        eventCounts: { ERROR: 2, WARNING: 1 },
        lastEventAt: '2026-07-06T11:58:00Z',
        collectorFreshness: { stale: false, lastSeenAt: '2026-07-06T12:00:00Z', ageSeconds: 12 }
      }
    },
    {
      assetUid: 'x86host',
      name: 'x86host',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.22',
      location: 'rack-b',
      status: 'active',
      lastSeenAt: '2026-07-06T11:42:00Z',
      stale: true,
      health: 'warning',
      sources: { registered: true, agent: true, traffic: true, observed: true },
      metrics: {
        cpuUsagePct: 14.2,
        memoryUsagePct: 33.1,
        diskUsagePct: 52.0,
        temperatureCelsius: 63.0,
        networkInBps: 10000,
        networkOutBps: 5000
      },
      security: { openPorts: 20, failedServices: 0, firewallDisabled: 0, interfacesDown: 2 },
      signals: {
        reasons: [{ code: 'stale', label: '수집 지연', severity: 'warning', detail: '18m' }],
        interfacesDown: 2,
        eventCounts: { WARNING: 1 },
        lastEventAt: '2026-07-06T11:30:00Z',
        collectorFreshness: { stale: true, lastSeenAt: '2026-07-06T11:42:00Z', ageSeconds: 1080 }
      }
    },
    {
      assetUid: 'security',
      name: 'security',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.25',
      location: 'rack-c',
      status: 'active',
      lastSeenAt: '2026-07-06T12:01:00Z',
      stale: false,
      health: 'healthy',
      sources: { registered: true, agent: true, traffic: true, observed: true },
      metrics: {
        cpuUsagePct: 2.5,
        memoryUsagePct: 5.3,
        diskUsagePct: 45,
        temperatureCelsius: 46.9,
        networkInBps: 1500,
        networkOutBps: 1200
      },
      security: { openPorts: 10, failedServices: 0, firewallDisabled: 0, interfacesDown: 0 },
      signals: {
        reasons: [],
        interfacesDown: 0,
        eventCounts: {},
        lastEventAt: null,
        collectorFreshness: { stale: false, lastSeenAt: '2026-07-06T12:01:00Z', ageSeconds: 8 }
      }
    }
  ]
};

const nasDetail: AssetMetricDetail = {
  range: '1h',
  bucket: 'auto',
  asset: assetMetrics.assets[0],
  series: {
    cpu: [
      { timestamp: '2026-07-06T11:50:00Z', value: 81 },
      { timestamp: '2026-07-06T12:00:00Z', value: 92.1 }
    ],
    memory: [
      { timestamp: '2026-07-06T11:50:00Z', value: 66 },
      { timestamp: '2026-07-06T12:00:00Z', value: 71.4 }
    ],
    disk: [
      { timestamp: '2026-07-06T11:50:00Z', value: 84 },
      { timestamp: '2026-07-06T12:00:00Z', value: 86.2 }
    ],
    network: [
      { timestamp: '2026-07-06T11:50:00Z', inBps: 9000, outBps: 7000 },
      { timestamp: '2026-07-06T12:00:00Z', inBps: 12000, outBps: 9000 }
    ]
  },
  sockets: [
    { assetUid: 'nas', protocol: 'tcp', localAddress: '0.0.0.0', localPort: 22, direction: 'listening', processName: 'sshd' }
  ],
  services: [{ assetUid: 'nas', name: 'nginx.service', status: 'failed' }],
  firewalls: [{ assetUid: 'nas', backend: 'ufw', enabled: false, ruleCount: 0 }],
  interfaceStates: [{ assetUid: 'nas', name: 'eth1', operStatus: 'down' }],
  processes: [{ assetUid: 'nas', pid: 991, name: 'clickhouse', memoryBytes: 536870912, listeningSocketCount: 1, connectedSocketCount: 2 }]
};

const registeredAsset: Asset = {
  id: 99,
  assetUid: 'registered-only',
  name: 'registered-only',
  assetType: 'LINUX_SERVER',
  managementIp: '192.168.50.99',
  location: 'lab',
  status: 'active'
};

type MockResponse = {
  body?: unknown;
  status?: number;
};

function mockFetch(responses: Record<string, MockResponse>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    const response = responses[path] ?? { status: 404, body: {} };
    const status = response.status ?? 200;
    return mockJsonResponse(response.body, status);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function mockJsonResponse(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    statusText: status < 400 ? 'OK' : 'Error',
    json: async () => body
  };
}
