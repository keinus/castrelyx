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

  it('renders fleet metrics, heat cells, and selected asset detail charts', async () => {
    const fetchMock = mockFetch({
      '/api/metrics/assets?range=1h': { body: overview },
      '/api/metrics/assets/nas?range=1h&bucket=auto': { body: detail }
    });

    render(<AssetsView role="ADMIN" assets={assets} onCreate={vi.fn()} />);

    expect(await screen.findByRole('heading', { name: '자산 메트릭 현황' })).toBeInTheDocument();
    expect(screen.getByText('전체 자산')).toBeInTheDocument();
    expect(screen.getAllByText('91.2%').length).toBeGreaterThan(0);
    expect(screen.getAllByText('nas').length).toBeGreaterThan(0);
    expect(screen.getAllByText('CPU Usage').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Memory Usage').length).toBeGreaterThan(0);
    expect(screen.getByText('Disk by mount')).toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      '/api/metrics/assets/nas?range=1h&bucket=auto',
      expect.objectContaining({ credentials: 'include' })
    ));
    expect(await screen.findByText('/data')).toBeInTheDocument();
    expect(await screen.findByText('sshd')).toBeInTheDocument();
  });

  it('filters assets and falls back to registered inventory when metrics API fails', async () => {
    mockFetch({
      '/api/metrics/assets?range=1h': { status: 500, body: {} }
    });

    render(<AssetsView role="ADMIN" assets={assets} onCreate={vi.fn()} />);

    expect(await screen.findByText('자산 메트릭 정보를 불러오지 못했습니다. 등록된 자산 기본 정보만 표시합니다.')).toBeInTheDocument();
    expect(screen.getAllByText('edge-router').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('자산 검색'), { target: { value: 'edge' } });
    expect(screen.getAllByText('edge-router').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('상태 필터'), { target: { value: 'healthy' } });
    await waitFor(() => expect(screen.getByText('조건에 맞는 자산이 없습니다.')).toBeInTheDocument());
  });
});

const assets: Asset[] = [
  {
    id: 1,
    assetUid: 'edge-router',
    name: 'edge-router',
    assetType: 'ROUTER',
    managementIp: '10.0.0.1',
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
    maxDiskUsagePct: 91.2
  },
  assets: [
    {
      assetUid: 'nas',
      name: 'nas',
      assetType: 'LINUX_SERVER',
      managementIp: '192.168.50.21',
      status: 'active',
      lastSeenAt: '2026-06-11T13:34:00Z',
      health: 'critical',
      sources: { registered: true, agent: true, traffic: true, observed: true },
      metrics: {
        cpuUsagePct: 91.2,
        memoryUsagePct: 67.8,
        diskUsagePct: 72.4,
        normalizedLoadPct: 45,
        networkInBps: 4621.02,
        networkOutBps: 11663.97
      },
      security: { openPorts: 1, failedServices: 0, firewallDisabled: 0, securityEvents: 1 }
    },
    {
      assetUid: 'edge-router',
      name: 'edge-router',
      assetType: 'ROUTER',
      managementIp: '10.0.0.1',
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
    network: [{ timestamp: '2026-06-11T13:35:00Z', inBps: 4621.02, outBps: 11663.97 }]
  },
  disks: [{ mountPoint: '/data', filesystem: '/dev/sdb1', usedPct: 72.4, availableBytes: 1000 }],
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
  processes: [{ assetUid: 'nas', pid: 22, name: 'sshd', user: 'root', memoryBytes: 2048, listeningSocketCount: 1 }],
  security: { openPorts: 1, failedServices: 0, firewallDisabled: 0, securityEvents: 1 },
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
