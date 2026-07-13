import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AlertRow, Asset, AssetMetricDetail, AssetMetricsOverview, DashboardSummary, InterfaceTraffic } from '../lib/types';
import { OverviewView } from './OverviewView';

describe('OverviewView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders every managed device with CPU, RAM, Disk I/O, and Network I/O', async () => {
    mockFetch({
      '/api/metrics/assets?range=15m': { body: assetMetrics },
      '/api/dashboards/agent': { body: agentDashboard },
      '/api/agent/logs?range=15m&severity=ALL&limit=200': { body: agentLogs },
      '/api/traffic/interfaces?range=15m': { body: trafficRows },
      '/api/metrics/assets/nas?range=15m&bucket=auto': { body: nasDetail },
      '/api/metrics/assets/x86host?range=15m&bucket=auto': { body: x86hostDetail }
    });

    render(<OverviewView summary={summary} alerts={alerts} assets={[registeredAsset]} />);

    expect(await screen.findByRole('heading', { name: 'Action Rail Command Center' })).toBeInTheDocument();
    expect(screen.getAllByText('SOC + NMS').length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: '전체 장비 리소스 상태' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '전체 장비 리소스 매트릭스' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '자산 인스펙터' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '이벤트 스트림' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /수집 커버리지/ })).toBeInTheDocument();
    expect(screen.getByText('AGENT')).toBeInTheDocument();

    const table = screen.getByRole('table');
    for (const label of ['CPU', 'RAM', 'Disk I/O', 'Network I/O']) {
      expect(within(table).getByRole('button', { name: `${label} 기준 내림차순 정렬` })).toBeInTheDocument();
    }
    const nasRow = within(table).getByRole('row', { name: 'nas 리소스 상태' });
    expect(within(nasRow).getByLabelText('nas CPU 92.1 퍼센트')).toBeInTheDocument();
    expect(within(nasRow).getByLabelText('nas RAM 71.4 퍼센트')).toBeInTheDocument();
    expect(within(nasRow).getByLabelText('nas Disk I/O 읽기 1.00 MB/s, 쓰기 2.00 MB/s')).toBeInTheDocument();
    expect(within(nasRow).getByLabelText('nas Network I/O 수신 12.00 Kbps, 송신 9.00 Kbps')).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: 'x86host 리소스 상태' })).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: 'security 리소스 상태' })).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: 'registered-only 리소스 상태' })).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('검색 (자산, IP, 신호)'), { target: { value: 'x86host' } });
    await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(2));
    expect(within(table).getByRole('row', { name: 'x86host 리소스 상태' })).toBeInTheDocument();
    expect(within(table).queryByRole('row', { name: 'nas 리소스 상태' })).not.toBeInTheDocument();
    expect(screen.getByLabelText('x86host 자산 인스펙터')).toBeInTheDocument();
  });

  it('refreshes on the 30-second cadence and retains the last good matrix when refresh fails', async () => {
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
      if (path === '/api/metrics/assets?range=15m') {
        overviewCalls += 1;
        if (overviewCalls === 1) {
          return mockJsonResponse(assetMetrics);
        }
        if (overviewCalls === 2) {
          return mockJsonResponse(refreshedAssetMetrics);
        }
        return mockJsonResponse({}, 500);
      }
      if (path === '/api/metrics/assets/nas?range=15m&bucket=auto') {
        detailCalls += 1;
        return mockJsonResponse(detailCalls === 1 ? nasDetail : refreshedNasDetail);
      }
      if (path === '/api/dashboards/agent') {
        return mockJsonResponse(agentDashboard);
      }
      if (path === '/api/agent/logs?range=15m&severity=ALL&limit=200') {
        return mockJsonResponse(agentLogs);
      }
      if (path === '/api/traffic/interfaces?range=15m') {
        return mockJsonResponse(trafficRows);
      }
      return mockJsonResponse({}, 404);
    });
    vi.stubGlobal('fetch', fetchMock);

    render(<OverviewView summary={summary} alerts={alerts} />);

    expect(await screen.findByRole('heading', { name: '전체 장비 리소스 매트릭스' })).toBeInTheDocument();
    await waitFor(() => expect(detailCalls).toBe(1));
    expect(intervalHandler).toEqual(expect.any(Function));
    const table = screen.getByRole('table');
    let nasRow = within(table).getByRole('row', { name: 'nas 리소스 상태' });
    expect(within(nasRow).getByLabelText('nas CPU 92.1 퍼센트')).toBeInTheDocument();

    await act(async () => {
      if (typeof intervalHandler === 'function') {
        intervalHandler();
      }
    });

    await waitFor(() => expect(overviewCalls).toBe(2));
    await waitFor(() => expect(detailCalls).toBe(2));
    await waitFor(() => {
      nasRow = within(table).getByRole('row', { name: 'nas 리소스 상태' });
      expect(within(nasRow).getByLabelText('nas CPU 45.0 퍼센트')).toBeInTheDocument();
    });

    await act(async () => {
      if (typeof intervalHandler === 'function') {
        intervalHandler();
      }
    });

    await waitFor(() => expect(overviewCalls).toBe(3));
    expect(await screen.findByText('일부 관제 정보가 지연되고 있습니다. 마지막 정상 수집값을 유지합니다.')).toBeInTheDocument();
    nasRow = within(table).getByRole('row', { name: 'nas 리소스 상태' });
    expect(within(nasRow).getByLabelText('nas CPU 45.0 퍼센트')).toBeInTheDocument();
    expect(within(nasRow).queryByLabelText('nas CPU 92.1 퍼센트')).not.toBeInTheDocument();
    expect(detailCalls).toBe(2);
  });

  it('distinguishes collected zero values, missing metrics, and stale devices', async () => {
    mockFetch({
      '/api/metrics/assets?range=15m': { body: assetMetrics },
      '/api/dashboards/agent': { body: agentDashboard },
      '/api/agent/logs?range=15m&severity=ALL&limit=200': { body: [] },
      '/api/traffic/interfaces?range=15m': { body: trafficRows },
      '/api/metrics/assets/nas?range=15m&bucket=auto': { body: nasDetail }
    });

    render(<OverviewView summary={summary} alerts={alerts} assets={[registeredAsset]} />);

    const table = await screen.findByRole('table');
    const zeroRow = within(table).getByRole('row', { name: 'security 리소스 상태' });
    expect(within(zeroRow).getByLabelText('security CPU 0.0 퍼센트')).toBeInTheDocument();
    expect(within(zeroRow).getByLabelText('security RAM 0.0 퍼센트')).toBeInTheDocument();
    expect(within(zeroRow).getByLabelText('security Disk I/O 읽기 0 B/s, 쓰기 0 B/s')).toBeInTheDocument();
    expect(within(zeroRow).getByLabelText('security Network I/O 수신 0 bps, 송신 0 bps')).toBeInTheDocument();
    expect(within(zeroRow).queryByText('미수집')).not.toBeInTheDocument();

    const missingRow = within(table).getByRole('row', { name: 'registered-only 리소스 상태' });
    expect(within(missingRow).getByLabelText('registered-only CPU 미수집')).toBeInTheDocument();
    expect(within(missingRow).getByLabelText('registered-only RAM 미수집')).toBeInTheDocument();
    expect(within(missingRow).getByLabelText('registered-only Disk I/O 미수집')).toBeInTheDocument();
    expect(within(missingRow).getByLabelText('registered-only Network I/O 미수집')).toBeInTheDocument();

    const staleRow = within(table).getByRole('row', { name: 'x86host 리소스 상태' });
    expect(within(staleRow).getByText(/수집 지연/)).toBeInTheDocument();
    expect(within(staleRow).getByLabelText('x86host Disk I/O 읽기 256 KB/s, 쓰기 128 KB/s')).toBeInTheDocument();
  });

  it('keeps every device represented as the fleet grows', async () => {
    const fleetSize = 50;
    const largeFleet: AssetMetricsOverview = {
      range: '15m',
      summary: {
        totalAssets: fleetSize,
        observedAssets: fleetSize,
        staleAssets: 0,
        criticalAssets: fleetSize,
        warningAssets: 0
      },
      assets: Array.from({ length: fleetSize }, (_, index) => ({
        ...assetMetrics.assets[0],
        assetUid: `asset-${index}`,
        name: `asset-${index}`,
        managementIp: `10.0.${Math.floor(index / 255)}.${index % 255}`
      }))
    };
    const firstDetail: AssetMetricDetail = { ...nasDetail, asset: largeFleet.assets[0] };
    mockFetch({
      '/api/metrics/assets?range=15m': { body: largeFleet },
      '/api/dashboards/agent': { body: agentDashboard },
      '/api/agent/logs?range=15m&severity=ALL&limit=200': { body: [] },
      '/api/traffic/interfaces?range=15m': { body: [] },
      '/api/metrics/assets/asset-0?range=15m&bucket=auto': { body: firstDetail }
    });

    render(<OverviewView summary={summary} alerts={[]} />);

    const table = await screen.findByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(fleetSize + 1);
    expect(within(table).getByRole('row', { name: 'asset-0 리소스 상태' })).toBeInTheDocument();
    expect(within(table).getByRole('row', { name: `asset-${fleetSize - 1} 리소스 상태` })).toBeInTheDocument();
    expect(screen.getByLabelText(`수집 장비 ${fleetSize}대, 전체 ${fleetSize}대`)).toBeInTheDocument();
  });

  it('falls back to registered assets when dashboard APIs fail', async () => {
    mockFetch({
      '/api/metrics/assets?range=15m': { status: 500, body: {} },
      '/api/dashboards/agent': { status: 500, body: {} },
      '/api/agent/logs?range=15m&severity=ALL&limit=200': { status: 500, body: {} },
      '/api/traffic/interfaces?range=15m': { status: 500, body: {} },
      '/api/metrics/assets/registered-only?range=15m&bucket=auto': { status: 500, body: {} }
    });

    render(<OverviewView summary={summary} alerts={[]} assets={[registeredAsset]} />);

    expect(await screen.findByText('일부 관제 정보가 지연되고 있습니다. 마지막 정상 수집값을 유지합니다.')).toBeInTheDocument();
    expect(screen.getByLabelText('수집 장비 0대, 전체 1대')).toBeInTheDocument();
    const table = screen.getByRole('table');
    const row = within(table).getByRole('row', { name: 'registered-only 리소스 상태' });
    expect(within(row).getByLabelText('registered-only CPU 미수집')).toBeInTheDocument();
    expect(within(row).getByLabelText('registered-only RAM 미수집')).toBeInTheDocument();
    expect(within(row).getByLabelText('registered-only Disk I/O 미수집')).toBeInTheDocument();
    expect(within(row).getByLabelText('registered-only Network I/O 미수집')).toBeInTheDocument();
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
    avgCpuUsagePct: 35.4,
    avgMemoryUsagePct: 34.8,
    maxDiskIoUtilizationPct: 88.8,
    totalNetworkInBps: 22000,
    totalNetworkOutBps: 14000
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
        diskReadBps: 1048576,
        diskWriteBps: 2097152,
        diskReadIops: 128,
        diskWriteIops: 64,
        diskIoUtilizationPct: 88.8,
        temperatureCelsius: 82.4,
        networkInBps: 12000,
        networkOutBps: 9000,
        interfaceErrorCount: 0
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
      sources: { registered: true, agent: true, traffic: true, diskIo: true, observed: true },
      metrics: {
        cpuUsagePct: 14.2,
        memoryUsagePct: 33.1,
        diskUsagePct: 52.0,
        diskReadBps: 262144,
        diskWriteBps: 131072,
        diskReadIops: 16,
        diskWriteIops: 8,
        diskIoUtilizationPct: 35.5,
        temperatureCelsius: 83.0,
        networkInBps: 10000,
        networkOutBps: 5000,
        interfaceErrorCount: 1
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
      sources: { registered: true, agent: true, traffic: true, diskIo: true, observed: true },
      metrics: {
        cpuUsagePct: 0,
        memoryUsagePct: 0,
        diskUsagePct: 45,
        diskReadBps: 0,
        diskWriteBps: 0,
        diskReadIops: 0,
        diskWriteIops: 0,
        diskIoUtilizationPct: 0,
        temperatureCelsius: 46.9,
        networkInBps: 0,
        networkOutBps: 0,
        interfaceErrorCount: 0
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
    diskIo: [
      { timestamp: '2026-07-06T11:50:00Z', readBps: 786432, writeBps: 1572864, readIops: 96, writeIops: 48, utilizationPct: 70 },
      { timestamp: '2026-07-06T12:00:00Z', readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, utilizationPct: 88.8 }
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

const x86hostDetail: AssetMetricDetail = {
  ...nasDetail,
  asset: assetMetrics.assets[1],
  series: {
    cpu: [{ timestamp: '2026-07-06T11:42:00Z', value: 14.2 }],
    memory: [{ timestamp: '2026-07-06T11:42:00Z', value: 33.1 }],
    disk: [{ timestamp: '2026-07-06T11:42:00Z', value: 52 }],
    diskIo: [{ timestamp: '2026-07-06T11:42:00Z', readBps: 262144, writeBps: 131072, readIops: 16, writeIops: 8, utilizationPct: 35.5 }],
    network: [{ timestamp: '2026-07-06T11:42:00Z', inBps: 10000, outBps: 5000 }]
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
