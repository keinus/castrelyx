import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AlertRow, Asset, AssetMetricDetail, AssetMetricsOverview, DashboardSummary, InterfaceTraffic } from '../lib/types';
import { OverviewView } from './OverviewView';

describe('OverviewView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders a chart-first command dashboard instead of an asset table', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { body: assetMetrics },
      '/api/dashboards/agent': { body: agentDashboard },
      '/api/agent/logs?range=24h&severity=ALL&limit=200': { body: agentLogs },
      '/api/traffic/interfaces?range=1h': { body: trafficRows },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: nasDetail }
    });

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('heading', { name: '개요' })).toBeInTheDocument();
    expect(screen.getByText('장비 상태를 중심으로 부하, 트래픽, 이벤트, 수집 신선도를 한 화면에서 판단합니다.')).toBeInTheDocument();
    expect(screen.getByText('전체 장비')).toBeInTheDocument();
    expect(screen.getByText('문제 장비')).toBeInTheDocument();
    expect(screen.getByText('평균 CPU')).toBeInTheDocument();
    expect(screen.getByText('총 트래픽')).toBeInTheDocument();

    expect(screen.getByRole('heading', { name: 'Fleet Health' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '선택 장비 추세' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '리소스 압박 Top' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '상위 트래픽 인터페이스' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '이벤트 추세' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Source Coverage' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '대응 큐' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Collector Freshness' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '보안 포스처' })).toBeInTheDocument();

    expect(screen.getByText('nas · CPU')).toBeInTheDocument();
    expect(screen.getByText('x86host · Temperature')).toBeInTheDocument();
    expect(screen.getByText('metric')).toBeInTheDocument();
    expect(screen.getByText('process')).toBeInTheDocument();
    expect(screen.getAllByText('nas').length).toBeGreaterThan(0);
    expect(screen.getByText(/Root filesystem full/)).toBeInTheDocument();

    expect(screen.queryByRole('heading', { name: '장비 상태 보드' })).not.toBeInTheDocument();
    expect(screen.queryByText('마지막 수집')).not.toBeInTheDocument();
    expect(screen.queryByText('CPU/MEM/DISK/TEMP')).not.toBeInTheDocument();
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
      if (path === '/api/dashboards/agent') {
        return mockJsonResponse(agentDashboard);
      }
      if (path === '/api/agent/logs?range=24h&severity=ALL&limit=200') {
        return mockJsonResponse(agentLogs);
      }
      if (path === '/api/traffic/interfaces?range=1h') {
        return mockJsonResponse(trafficRows);
      }
      return mockJsonResponse({}, 404);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('heading', { name: 'Fleet Health' })).toBeInTheDocument();
    await waitFor(() => expect(detailCalls).toBe(1));
    expect(intervalHandler).toEqual(expect.any(Function));

    await act(async () => {
      if (typeof intervalHandler === 'function') {
        intervalHandler();
      }
    });

    await waitFor(() => expect(overviewCalls).toBe(2));
    await waitFor(() => expect(detailCalls).toBe(2));
    await waitFor(() => expect(screen.getByText(/CPU 45\.0%/)).toBeInTheDocument());
  });

  it('renders zero-valued CPU samples as collected trend data', async () => {
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
      '/api/dashboards/agent': { body: agentDashboard },
      '/api/agent/logs?range=24h&severity=ALL&limit=200': { body: [] },
      '/api/traffic/interfaces?range=1h': { body: trafficRows },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: zeroCpuDetail }
    });

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('img', { name: '선택 장비 추세' })).toBeInTheDocument();
    expect(screen.queryByText('시계열 대기')).not.toBeInTheDocument();
  });

  it('falls back to registered assets when dashboard APIs fail', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { status: 500, body: {} },
      '/api/dashboards/agent': { status: 500, body: {} },
      '/api/agent/logs?range=24h&severity=ALL&limit=200': { status: 500, body: {} },
      '/api/traffic/interfaces?range=1h': { status: 500, body: {} },
      '/api/metrics/assets/registered-only?range=1h&bucket=auto': { status: 500, body: {} }
    });

    render(<OverviewView summary={summary} alerts={[]} assets={[registeredAsset]} />);

    expect(await screen.findByText('일부 관제 정보를 불러오지 못했습니다. 마지막 정상 수집값을 유지합니다.')).toBeInTheDocument();
    expect(screen.getAllByText('registered-only').length).toBeGreaterThan(0);
    expect(screen.getByText('수집 데이터 없음')).toBeInTheDocument();
    expect(screen.getByText('선택 장비의 시계열 일부를 불러오지 못했습니다.')).toBeInTheDocument();
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

const trafficRows: InterfaceTraffic[] = [
  { assetUid: 'nas', interfaceName: 'enp2s0', inBps: 12000, outBps: 9000, utilizationPct: 12, errors: 0, discards: 0, status: 'up' },
  { assetUid: 'x86host', interfaceName: 'eno1', inBps: 10000, outBps: 5000, utilizationPct: 8, errors: 1, discards: 0, status: 'up' },
  { assetUid: 'security', interfaceName: 'eth0', inBps: 1500, outBps: 1200, utilizationPct: 4, errors: 0, discards: 0, status: 'up' }
];

const agentDashboard = {
  heartbeat: { healthy: 2, stale: 1, lastSeenAt: '2026-07-06T12:01:00Z' },
  securityPosture: { exposedPorts: 31, failedServices: 1, firewallDisabled: 1 },
  collectors: [
    { name: 'metric', sampleCount: 120, lastSeenAt: '2026-07-06T12:01:00Z' },
    { name: 'process', sampleCount: 60, lastSeenAt: '2026-07-06T12:00:40Z' }
  ],
  events: []
};

const agentLogs = [
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
];

const assetMetrics: AssetMetricsOverview = {
  range: '1h',
  summary: {
    totalAssets: 3,
    observedAssets: 3,
    staleAssets: 1,
    criticalAssets: 1,
    warningAssets: 1,
    avgCpuUsagePct: 36.3,
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
        temperatureCelsius: 83.0,
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
    temperature: [
      { timestamp: '2026-07-06T11:50:00Z', value: 80.1 },
      { timestamp: '2026-07-06T12:00:00Z', value: 82.4 }
    ],
    network: [
      { timestamp: '2026-07-06T11:50:00Z', inBps: 9000, outBps: 7000 },
      { timestamp: '2026-07-06T12:00:00Z', inBps: 12000, outBps: 9000 }
    ]
  }
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
