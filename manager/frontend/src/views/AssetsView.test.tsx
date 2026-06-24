import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Asset, AssetMetricDetail, AssetMetricsOverview } from '../lib/types';
import { AssetsView } from './AssetsView';

describe('AssetsView', () => {
  beforeEach(() => {
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders an asset tree and selected asset detail charts without fleet averages or top5 cards', async () => {
    const fetchMock = mockFetch({
      '/api/metrics/assets?range=1h': { body: overview },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: detail }
    });

    render(<AssetsView role="ADMIN" assets={assets} onCreate={vi.fn()} onUpdate={vi.fn()} onDelete={vi.fn()} />);

    expect(await screen.findByRole('heading', { name: '자산 현황 트리' })).toBeInTheDocument();
    expect(screen.getByText('Seoul HQ')).toBeInTheDocument();
    expect(screen.getAllByText('LINUX_SERVER').length).toBeGreaterThan(0);
    expect(screen.getAllByText('nas').length).toBeGreaterThan(0);
    expect(screen.getByText('위치')).toBeInTheDocument();
    expect(screen.getAllByText('Seoul HQ / Rack A').length).toBeGreaterThan(0);
    expect(screen.getAllByText('CPU Usage').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Memory Usage').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Disk I/O').length).toBeGreaterThan(0);
    expect(screen.queryByText('SNMP')).not.toBeInTheDocument();
    expect(screen.queryByText('평균 CPU')).not.toBeInTheDocument();
    expect(screen.queryByText('Disk I/O Top 5')).not.toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/metrics/assets/nas?range=1h&bucket=auto',
      expect.objectContaining({ credentials: 'include' })
    ));
    expect(screen.queryByRole('tab', { name: 'I/O' })).not.toBeInTheDocument();
    expect(screen.getByText('/')).toBeInTheDocument();
    expect(screen.getByText('/tmp')).toBeInTheDocument();
    expect(screen.queryByText('/data')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Disk 상세 보기' }));
    expect(await screen.findByText('Disk by mount')).toBeInTheDocument();
    expect(await screen.findByText('/data')).toBeInTheDocument();
    expect(await screen.findByText('sdb')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Disk I/O / mount 상세 닫기' }));

    expect(screen.queryByText('Failed password for invalid user')).not.toBeInTheDocument();
    expect(screen.queryByText('sshd')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Signal 상세 보기' }));
    expect(await screen.findByText('Failed password for invalid user')).toBeInTheDocument();
    expect(screen.getByText(/auth\.login\.failure/)).toBeInTheDocument();
    expect(screen.getByText('WARNING')).toBeInTheDocument();
    expect(await screen.findByText('Process netstat')).toBeInTheDocument();
    expect(screen.getAllByText('sshd').length).toBeGreaterThan(0);
    expect(screen.getByText('0.0.0.0:22')).toBeInTheDocument();
    expect(screen.getByText('192.168.50.10:443')).toBeInTheDocument();
  });

  it('filters assets and falls back to registered inventory when metrics API fails', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { status: 500, body: {} }
    });

    render(<AssetsView role="ADMIN" assets={assets} onCreate={vi.fn()} onUpdate={vi.fn()} onDelete={vi.fn()} />);

    expect(await screen.findByText('자산 메트릭 정보를 불러오지 못했습니다. 등록된 자산 기본 정보만 표시합니다.')).toBeInTheDocument();
    expect(screen.getAllByText('edge-router').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('자산 검색'), { target: { value: 'edge' } });
    expect(screen.getAllByText('edge-router').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('상태 필터'), { target: { value: 'healthy' } });
    await waitFor(() => expect(screen.getByText('조건에 맞는 자산이 없습니다.')).toBeInTheDocument());
  });

  it('lets operators edit selected asset name, location, and description', async () => {
    const onUpdate = vi.fn(async () => undefined);
    mockFetch({
      '/api/metrics/assets?range=1h': { body: overview },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: detail }
    });

    render(<AssetsView role="OPERATOR" assets={assets} onCreate={vi.fn()} onUpdate={onUpdate} onDelete={vi.fn()} />);

    await screen.findByRole('heading', { name: '자산 현황 트리' });
    await waitFor(() => expect(screen.getByText(/관리 IP 192\.168\.50\.21/)).toBeInTheDocument());
    fireEvent.click(screen.getByLabelText('자산 정보 수정'));
    fireEvent.change(await screen.findByLabelText('수정 자산명'), { target: { value: 'nas-main' } });
    fireEvent.change(screen.getByLabelText('수정 위치'), { target: { value: 'Seoul HQ / Rack B' } });
    fireEvent.change(screen.getByLabelText('수정 설명'), { target: { value: 'Primary NAS' } });
    fireEvent.click(screen.getByRole('button', { name: '저장' }));

    await waitFor(() => expect(onUpdate).toHaveBeenCalledWith(2, {
      name: 'nas-main',
      location: 'Seoul HQ / Rack B',
      description: 'Primary NAS'
    }));
  });

  it('keeps viewer in read-only mode', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { body: overview },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: detail }
    });

    render(<AssetsView role="VIEWER" assets={assets} onCreate={vi.fn()} onUpdate={vi.fn()} onDelete={vi.fn()} />);

    await waitFor(() => expect(screen.getAllByText('Seoul HQ / Rack A').length).toBeGreaterThan(0));
    expect(screen.queryByLabelText('자산 추가')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('자산 정보 수정')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('자산 삭제')).not.toBeInTheDocument();
  });
});

const assets: Asset[] = [
  {
    id: 1,
    assetUid: 'edge-router',
    name: 'edge-router',
    assetType: 'ROUTER',
    managementIp: '10.0.0.1',
    location: 'Seoul HQ',
    description: 'WAN router',
    status: 'active'
  }
];

const overview: AssetMetricsOverview = {
  range: '1h',
  summary: {
    totalAssets: 2,
    observedAssets: 2,
    staleAssets: 0,
    criticalAssets: 1,
    warningAssets: 0,
    avgCpuUsagePct: 63.1,
    avgMemoryUsagePct: 54.2,
    maxDiskUsagePct: 91.2,
    maxDiskIoUtilizationPct: 87.5,
    totalNetworkInBps: 4621.02,
    totalNetworkOutBps: 11663.97
  },
  assets: [
    {
      id: 2,
      assetUid: 'nas',
      name: 'nas',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.21',
      location: 'Seoul HQ / Rack A',
      description: 'NAS storage node',
      status: 'active',
      lastSeenAt: '2026-06-11T13:34:00Z',
      health: 'critical',
      sources: { registered: true, agent: true, traffic: true, diskIo: true, observed: true },
      metrics: {
        cpuUsagePct: 91.2,
        memoryUsagePct: 67.8,
        diskUsagePct: 72.4,
        normalizedLoadPct: 45,
        networkInBps: 4621.02,
        networkOutBps: 11663.97,
        diskReadBps: 1048576,
        diskWriteBps: 2097152,
        diskReadIops: 128,
        diskWriteIops: 64,
        diskIoUtilizationPct: 87.5
      },
      security: { openPorts: 1, failedServices: 0, firewallDisabled: 0, securityEvents: 1 }
    },
    {
      id: 1,
      assetUid: 'edge-router',
      name: 'edge-router',
      assetType: 'ROUTER',
      managementIp: '10.0.0.1',
      location: 'Seoul HQ',
      description: 'WAN router',
      status: 'active',
      health: 'unknown',
      sources: { registered: true, observed: false },
      metrics: {}
    }
  ]
};

const detail: AssetMetricDetail = {
  range: '1h',
  bucket: 'auto',
  asset: overview.assets[0],
  series: {
    cpu: [{ timestamp: '2026-06-11T13:30:00Z', value: 82 }, { timestamp: '2026-06-11T13:35:00Z', value: 91.2 }],
    memory: [{ timestamp: '2026-06-11T13:30:00Z', value: 60 }, { timestamp: '2026-06-11T13:35:00Z', value: 67.8 }],
    disk: [{ timestamp: '2026-06-11T13:30:00Z', value: 72.4 }],
    diskIo: [{ timestamp: '2026-06-11T13:35:00Z', readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, utilizationPct: 87.5 }],
    network: [{ timestamp: '2026-06-11T13:35:00Z', inBps: 4621.02, outBps: 11663.97 }]
  },
  disks: [
    { mountPoint: '/', filesystem: '/dev/sda1', device: 'sda', usedPct: 65.4, availableBytes: 4096 },
    { mountPoint: '/tmp', filesystem: 'tmpfs', device: 'tmpfs', usedPct: 12.3, availableBytes: 2048 },
    { mountPoint: '/data', filesystem: '/dev/sdb1', device: 'sdb', usedPct: 72.4, availableBytes: 1000, readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, ioUtilizationPct: 87.5 }
  ],
  diskIo: [{ assetUid: 'nas', device: 'sdb', readBps: 1048576, writeBps: 2097152, readIops: 128, writeIops: 64, ioUtilizationPct: 87.5 }],
  interfaces: [{
    assetUid: 'nas',
    interfaceName: 'enp2s0',
    inBps: 4621.02,
    outBps: 11663.97,
    utilizationPct: 0,
    errors: 0,
    discards: 0,
    status: 'up'
  }],
  processes: [{
    assetUid: 'nas',
    pid: 22,
    name: 'sshd',
    user: 'root',
    executablePath: '/usr/sbin/sshd',
    memoryBytes: 2048,
    socketKeys: ['tcp:0.0.0.0:22:0.0.0.0:0:listen', 'tcp:192.168.50.21:52344:192.168.50.10:443:established'],
    listeningSocketCount: 1,
    connectedSocketCount: 1
  }],
  sockets: [
    {
      assetUid: 'nas',
      protocol: 'tcp',
      localAddress: '0.0.0.0',
      localPort: 22,
      remoteAddress: '0.0.0.0',
      remotePort: 0,
      state: 'listen',
      direction: 'listening',
      processName: 'sshd',
      processId: 22,
      stateKey: 'tcp:0.0.0.0:22:0.0.0.0:0:listen',
      observedAt: '2026-06-11T13:35:00Z'
    },
    {
      assetUid: 'nas',
      protocol: 'tcp',
      localAddress: '192.168.50.21',
      localPort: 52344,
      remoteAddress: '192.168.50.10',
      remotePort: 443,
      state: 'established',
      direction: 'connected',
      processName: 'sshd',
      processId: 22,
      stateKey: 'tcp:192.168.50.21:52344:192.168.50.10:443:established',
      observedAt: '2026-06-11T13:35:00Z'
    }
  ],
  security: {
    openPorts: 1,
    failedServices: 0,
    firewallDisabled: 0,
    securityEvents: 1,
    events: [{
      assetUid: 'nas',
      eventType: 'auth.login.failure',
      eventCategory: 'auth',
      severity: 'WARNING',
      message: 'Failed password for invalid user',
      sourceName: 'sshd',
      actor: 'invalid-user',
      action: 'login',
      outcome: 'failure',
      observedAt: '2026-06-11T13:35:00Z'
    }]
  },
  collectors: [{ name: 'metric', sampleCount: 10, lastSeenAt: '2026-06-11T13:34:00Z' }]
};

type MockResponse = {
  body: unknown;
  status?: number;
};

function mockFetch(responses: Record<string, MockResponse>) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input);
    const response = responses[path] ?? { status: 404, body: {} };
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
