import { Activity, BarChart3, Plus, RefreshCw, Search, Server, ShieldAlert } from 'lucide-react';
import { type FormEvent, type ReactElement, type ReactNode, useEffect, useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis
} from 'recharts';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { Asset, AssetMetricDetail, AssetMetricSummary, AssetMetricsOverview, MetricPoint, Role } from '../lib/types';
import { canMutate, formatBps } from '../lib/uiModel';

type AssetsViewProps = {
  role: Role;
  assets: Asset[];
  onCreate: (payload: { name: string; assetType: string; managementIp?: string; description?: string }) => Promise<void>;
};

type HealthFilter = 'all' | AssetMetricSummary['health'];
type RangeOption = '15m' | '30m' | '1h' | '6h' | '24h';

const rangeOptions: { value: RangeOption; label: string }[] = [
  { value: '15m', label: '15분' },
  { value: '30m', label: '30분' },
  { value: '1h', label: '1시간' },
  { value: '6h', label: '6시간' },
  { value: '24h', label: '24시간' }
];

const healthFilters: { value: HealthFilter; label: string }[] = [
  { value: 'all', label: '전체 상태' },
  { value: 'critical', label: 'Critical' },
  { value: 'warning', label: 'Warning' },
  { value: 'healthy', label: 'Healthy' },
  { value: 'unknown', label: '미수집' }
];

const emptyOverview: AssetMetricsOverview = {
  range: '1h',
  summary: {
    totalAssets: 0,
    observedAssets: 0,
    staleAssets: 0,
    criticalAssets: 0,
    warningAssets: 0
  },
  assets: []
};

export function AssetsView({ role, assets, onCreate }: AssetsViewProps) {
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [assetType, setAssetType] = useState('LINUX_SERVER');
  const [managementIp, setManagementIp] = useState('');
  const [description, setDescription] = useState('');
  const [range, setRange] = useState<RangeOption>('1h');
  const [query, setQuery] = useState('');
  const [healthFilter, setHealthFilter] = useState<HealthFilter>('all');
  const [overview, setOverview] = useState<AssetMetricsOverview>(emptyOverview);
  const [detail, setDetail] = useState<AssetMetricDetail | null>(null);
  const [selectedAssetUid, setSelectedAssetUid] = useState('');
  const [detailRefreshToken, setDetailRefreshToken] = useState(0);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');

  async function loadMetrics(nextRange = range) {
    setLoading(true);
    setError('');
    try {
      const response = await api.assetMetrics(nextRange);
      setOverview(response);
      const nextSelected = response.assets.find((asset) => asset.assetUid === selectedAssetUid)?.assetUid
        ?? response.assets[0]?.assetUid
        ?? '';
      setSelectedAssetUid(nextSelected);
      setDetailRefreshToken((value) => value + 1);
    } catch {
      setOverview(fallbackOverview(assets, nextRange));
      setDetailRefreshToken((value) => value + 1);
      setError('자산 메트릭 정보를 불러오지 못했습니다. 등록된 자산 기본 정보만 표시합니다.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadMetrics(range);
  }, [range]);

  const filteredAssets = useMemo(() => {
    const text = query.trim().toLowerCase();
    return overview.assets.filter((asset) => {
      if (healthFilter !== 'all' && asset.health !== healthFilter) {
        return false;
      }
      if (!text) {
        return true;
      }
      return [asset.name, asset.assetUid, asset.assetType, asset.managementIp, asset.status]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
        .includes(text);
    });
  }, [healthFilter, overview.assets, query]);

  const selectedAsset = useMemo(
    () => filteredAssets.find((asset) => asset.assetUid === selectedAssetUid)
      ?? overview.assets.find((asset) => asset.assetUid === selectedAssetUid)
      ?? filteredAssets[0]
      ?? overview.assets[0],
    [filteredAssets, overview.assets, selectedAssetUid]
  );

  useEffect(() => {
    if (!selectedAsset?.assetUid) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    api.assetMetricDetail(selectedAsset.assetUid, range)
      .then((response) => {
        if (!cancelled) {
          setDetail(response);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDetail(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setDetailLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [detailRefreshToken, range, selectedAsset?.assetUid]);

  function selectAsset(assetUid: string) {
    if (assetUid === selectedAssetUid) {
      setDetailRefreshToken((value) => value + 1);
      return;
    }
    setSelectedAssetUid(assetUid);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    await onCreate({ name, assetType, managementIp: managementIp || undefined, description: description || undefined });
    setName('');
    setManagementIp('');
    setDescription('');
    setAssetType('LINUX_SERVER');
    setCreating(false);
    await loadMetrics(range);
  }

  const summary = overview.summary;

  return (
    <ViewFrame
      title="자산 관리"
      actions={(
        <>
          <button className="icon-button" aria-label="자산 메트릭 새로고침" onClick={() => void loadMetrics(range)} type="button">
            <RefreshCw size={18} />
          </button>
          {canMutate(role, 'asset:create') && (
            <button className="icon-button" aria-label="자산 추가" onClick={() => setCreating((value) => !value)} type="button">
              <Plus size={18} />
            </button>
          )}
        </>
      )}
    >
      {error && <div className="notice asset-notice">{error}</div>}
      {loading && <div className="notice">자산 메트릭 정보를 갱신하는 중입니다.</div>}

      {creating && (
        <form className="inline-form" onSubmit={submit}>
          <label>
            <span>자산 이름</span>
            <input aria-label="자산 이름" value={name} onChange={(event) => setName(event.target.value)} required />
          </label>
          <label>
            <span>자산 유형</span>
            <select aria-label="자산 유형" value={assetType} onChange={(event) => setAssetType(event.target.value)}>
              <option value="LINUX_SERVER">Linux server</option>
              <option value="WINDOWS_SERVER">Windows server</option>
              <option value="ROUTER">Router</option>
              <option value="FIREWALL">Firewall</option>
              <option value="NETWORK_DEVICE">Network device</option>
              <option value="UNKNOWN">Unknown</option>
            </select>
          </label>
          <label>
            <span>관리 IP</span>
            <input aria-label="관리 IP" value={managementIp} onChange={(event) => setManagementIp(event.target.value)} />
          </label>
          <label>
            <span>설명</span>
            <input aria-label="설명" value={description} onChange={(event) => setDescription(event.target.value)} />
          </label>
          <button type="submit">저장</button>
        </form>
      )}

      <div className="asset-metrics-shell">
        <section className="asset-metric-kpis">
          <MetricKpi icon={<Server size={18} />} label="전체 자산" value={String(summary.totalAssets)} meta={`${summary.observedAssets} 수집 확인`} />
          <MetricKpi icon={<Activity size={18} />} label="평균 CPU" value={formatPercent(summary.avgCpuUsagePct)} meta="fleet average" tone={metricTone(summary.avgCpuUsagePct)} />
          <MetricKpi icon={<BarChart3 size={18} />} label="평균 메모리" value={formatPercent(summary.avgMemoryUsagePct)} meta="fleet average" tone={metricTone(summary.avgMemoryUsagePct)} />
          <MetricKpi icon={<ShieldAlert size={18} />} label="위험 자산" value={String(summary.criticalAssets)} meta={`${summary.warningAssets} warning`} tone={summary.criticalAssets > 0 ? 'critical' : 'neutral'} />
          <MetricKpi icon={<BarChart3 size={18} />} label="최대 디스크" value={formatPercent(summary.maxDiskUsagePct)} meta={`${summary.staleAssets} stale`} tone={metricTone(summary.maxDiskUsagePct)} />
        </section>

        <section className="asset-query-panel asset-metric-toolbar">
          <label className="asset-search-field">
            <Search size={16} aria-hidden="true" />
            <input
              aria-label="자산 검색"
              placeholder="자산명, UID, IP, 유형 검색"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
            />
          </label>
          <select aria-label="조회 범위" value={range} onChange={(event) => setRange(event.target.value as RangeOption)}>
            {rangeOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
          <select aria-label="상태 필터" value={healthFilter} onChange={(event) => setHealthFilter(event.target.value as HealthFilter)}>
            {healthFilters.map((filter) => <option key={filter.value} value={filter.value}>{filter.label}</option>)}
          </select>
        </section>

        <div className="asset-metrics-layout">
          <section className="data-panel asset-fleet-panel">
            <div className="panel-heading">
              <h3>자산 메트릭 현황</h3>
              <span>{filteredAssets.length} assets</span>
            </div>
            <AssetMetricTable
              assets={filteredAssets}
              selectedAssetUid={selectedAsset?.assetUid ?? ''}
              onSelect={selectAsset}
            />
          </section>

          <AssetMetricDetailPanel
            asset={selectedAsset}
            detail={detail?.asset.assetUid === selectedAsset?.assetUid ? detail : null}
            loading={detailLoading}
          />
        </div>
      </div>
    </ViewFrame>
  );
}

function MetricKpi({
  icon,
  label,
  value,
  meta,
  tone = 'neutral'
}: {
  icon: ReactNode;
  label: string;
  value: string;
  meta: string;
  tone?: 'neutral' | 'warning' | 'critical';
}) {
  return (
    <section className={`asset-metric-kpi ${tone}`}>
      <div>{icon}<span>{label}</span></div>
      <strong>{value}</strong>
      <small>{meta}</small>
    </section>
  );
}

function AssetMetricTable({
  assets,
  selectedAssetUid,
  onSelect
}: {
  assets: AssetMetricSummary[];
  selectedAssetUid: string;
  onSelect: (assetUid: string) => void;
}) {
  return (
    <div className="table-scroll">
      <table className="asset-metric-table">
        <thead>
          <tr>
            <th>자산</th>
            <th>상태</th>
            <th>CPU</th>
            <th>Memory</th>
            <th>Disk</th>
            <th>Load</th>
            <th>마지막 수집</th>
          </tr>
        </thead>
        <tbody>
          {assets.map((asset) => (
            <tr className={selectedAssetUid === asset.assetUid ? 'selected' : ''} key={asset.assetUid}>
              <td>
                <button className="asset-name-button" type="button" onClick={() => onSelect(asset.assetUid)}>
                  <strong>{asset.name}</strong>
                  <span>{[asset.assetUid, asset.managementIp].filter(Boolean).join(' · ')}</span>
                </button>
              </td>
              <td><span className={`status-pill ${healthStatusClass(asset.health)}`}>{healthLabel(asset.health)}</span></td>
              <td><HeatCell value={asset.metrics.cpuUsagePct} /></td>
              <td><HeatCell value={asset.metrics.memoryUsagePct} /></td>
              <td><HeatCell value={asset.metrics.diskUsagePct} /></td>
              <td><HeatCell value={asset.metrics.normalizedLoadPct} suffix="%" /></td>
              <td>{formatDate(asset.lastSeenAt)}</td>
            </tr>
          ))}
          {assets.length === 0 && <tr><td colSpan={7}>조건에 맞는 자산이 없습니다.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function HeatCell({ value, suffix = '%' }: { value?: number | null; suffix?: string }) {
  const tone = metricTone(value);
  return (
    <div className={`asset-heat-cell ${tone}`}>
      <span>{formatPercent(value, suffix)}</span>
      <i style={{ width: `${clampPercent(value)}%` }} />
    </div>
  );
}

function AssetMetricDetailPanel({
  asset,
  detail,
  loading
}: {
  asset?: AssetMetricSummary;
  detail: AssetMetricDetail | null;
  loading: boolean;
}) {
  if (!asset) {
    return (
      <section className="data-panel asset-metric-detail-panel">
        <div className="asset-empty-state">
          <strong>자산 없음</strong>
          <span>등록되었거나 수집된 자산이 아직 없습니다.</span>
        </div>
      </section>
    );
  }

  const metrics = detail?.asset.metrics ?? asset.metrics;
  const security = detail?.security ?? asset.security;
  const interfaces = detail?.interfaces ?? [];
  const disks = detail?.disks ?? [];
  const processes = detail?.processes ?? [];

  return (
    <section className="data-panel asset-metric-detail-panel">
      <div className="asset-detail-header">
        <div>
          <span>선택 자산</span>
          <h3>{asset.name}</h3>
          <p>{[asset.assetUid, asset.assetType, asset.managementIp].filter(Boolean).join(' · ')}</p>
        </div>
        <span className={`status-pill ${healthStatusClass(asset.health)}`}>{healthLabel(asset.health)}</span>
      </div>

      {loading && <div className="notice">선택 자산 메트릭을 불러오는 중입니다.</div>}

      <section className="asset-detail-metric-grid">
        <MiniMetric label="CPU Usage" value={formatPercent(metrics.cpuUsagePct)} tone={metricTone(metrics.cpuUsagePct)} />
        <MiniMetric label="Memory Usage" value={formatPercent(metrics.memoryUsagePct)} tone={metricTone(metrics.memoryUsagePct)} />
        <MiniMetric label="Disk Usage" value={formatPercent(metrics.diskUsagePct)} tone={metricTone(metrics.diskUsagePct)} />
        <MiniMetric label="Normalized Load" value={formatPercent(metrics.normalizedLoadPct)} tone={loadTone(metrics.normalizedLoadPct)} />
        <MiniMetric label="Network RX" value={formatBps(metrics.networkInBps ?? 0)} />
        <MiniMetric label="Network TX" value={formatBps(metrics.networkOutBps ?? 0)} />
      </section>

      <div className="asset-chart-grid">
        <ChartBlock title="CPU Usage" empty="CPU 사용률 시계열이 없습니다. agent cpu.usage 수집 후 표시됩니다.">
          <PercentAreaChart data={detail?.series.cpu ?? []} color="#38bdf8" />
        </ChartBlock>
        <ChartBlock title="Memory Usage" empty="메모리 사용률 시계열이 없습니다.">
          <PercentAreaChart data={detail?.series.memory ?? []} color="#a78bfa" />
        </ChartBlock>
        <ChartBlock title="Disk Usage" empty="디스크 사용률 시계열이 없습니다.">
          <PercentAreaChart data={detail?.series.disk ?? []} color="#f59e0b" />
        </ChartBlock>
        <ChartBlock title="Network RX/TX" empty="인터페이스 RX/TX 시계열이 없습니다.">
          <NetworkLineChart data={detail?.series.network ?? []} />
        </ChartBlock>
      </div>

      <div className="asset-detail-lower-grid">
        <DetailList title="Disk by mount" meta={`${disks.length} mounts`} empty="수집된 디스크 mount 정보가 없습니다.">
          {disks.map((disk) => (
            <li key={disk.mountPoint ?? 'unknown'}>
              <div>
                <strong>{disk.mountPoint ?? 'unknown'}</strong>
                <span>{disk.filesystem ?? 'filesystem unknown'}</span>
              </div>
              <em>{formatPercent(disk.usedPct)}</em>
            </li>
          ))}
        </DetailList>
        <DetailList title="Interfaces" meta={`${interfaces.length} interfaces`} empty="수집된 인터페이스 정보가 없습니다.">
          {interfaces.slice(0, 6).map((row) => (
            <li key={`${row.assetUid}-${row.interfaceName}`}>
              <div>
                <strong>{row.interfaceName}</strong>
                <span>errors/drops {row.errors + row.discards}</span>
              </div>
              <em>{formatBps(row.inBps)} / {formatBps(row.outBps)}</em>
            </li>
          ))}
        </DetailList>
        <DetailList title="Top Processes" meta={`${processes.length} rows`} empty="수집된 process 정보가 없습니다.">
          {processes.slice(0, 6).map((process) => (
            <li key={`${process.pid}-${process.name}`}>
              <div>
                <strong>{process.name ?? `pid ${process.pid}`}</strong>
                <span>{process.user ?? 'user unknown'} · sockets {(process.listeningSocketCount ?? 0) + (process.connectedSocketCount ?? 0)}</span>
              </div>
              <em>{formatBytes(process.memoryBytes)}</em>
            </li>
          ))}
        </DetailList>
        <DetailList title="Security Signals" meta={`${security?.securityEvents ?? 0} events`} empty="보안 노출 신호가 없습니다.">
          <li>
            <div><strong>Open ports</strong><span>listening sockets</span></div>
            <em>{security?.openPorts ?? 0}</em>
          </li>
          <li>
            <div><strong>Failed services</strong><span>service state</span></div>
            <em>{security?.failedServices ?? 0}</em>
          </li>
          <li>
            <div><strong>Firewall disabled</strong><span>host firewall</span></div>
            <em>{security?.firewallDisabled ?? 0}</em>
          </li>
        </DetailList>
      </div>
    </section>
  );
}

function MiniMetric({ label, value, tone = 'neutral' }: { label: string; value: string; tone?: 'neutral' | 'warning' | 'critical' }) {
  return (
    <div className={`asset-mini-metric ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function ChartBlock({ title, empty, children }: { title: string; empty: string; children: ReactElement<{ data: MetricPoint[] }> }) {
  const data = children.props.data ?? [];
  return (
    <section className="asset-chart-panel">
      <div className="panel-heading">
        <h4>{title}</h4>
        <span>{data.length} points</span>
      </div>
      {data.length === 0 ? <p className="asset-empty-detail">{empty}</p> : children}
    </section>
  );
}

function PercentAreaChart({ data, color }: { data: MetricPoint[]; color: string }) {
  return (
    <div className="asset-chart-box">
      <ResponsiveContainer width="100%" height="100%" minWidth={1} minHeight={220}>
        <AreaChart data={chartData(data)}>
          <CartesianGrid stroke="rgba(148, 163, 184, 0.18)" vertical={false} />
          <XAxis dataKey="label" tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} />
          <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} domain={[0, 100]} width={34} />
          <Tooltip contentStyle={{ background: '#0f172a', border: '1px solid rgba(148, 163, 184, 0.28)', color: '#e2e8f0' }} />
          <Area type="monotone" dataKey="value" stroke={color} fill={color} fillOpacity={0.18} strokeWidth={2} isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

function NetworkLineChart({ data }: { data: MetricPoint[] }) {
  return (
    <div className="asset-chart-box">
      <ResponsiveContainer width="100%" height="100%" minWidth={1} minHeight={220}>
        <LineChart data={chartData(data)}>
          <CartesianGrid stroke="rgba(148, 163, 184, 0.18)" vertical={false} />
          <XAxis dataKey="label" tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} />
          <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} width={42} tickFormatter={(value) => formatBps(Number(value)).replace(' ', '')} />
          <Tooltip
            contentStyle={{ background: '#0f172a', border: '1px solid rgba(148, 163, 184, 0.28)', color: '#e2e8f0' }}
            formatter={(value) => formatBps(Number(value))}
          />
          <Legend />
          <Line type="monotone" dataKey="inBps" name="RX" stroke="#22c55e" strokeWidth={2} dot={false} isAnimationActive={false} />
          <Line type="monotone" dataKey="outBps" name="TX" stroke="#38bdf8" strokeWidth={2} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

function DetailList({ title, meta, empty, children }: { title: string; meta: string; empty: string; children: ReactNode }) {
  const items = Array.isArray(children) ? children.filter(Boolean) : children ? [children] : [];
  return (
    <section className="asset-detail-list-panel">
      <div className="panel-heading">
        <h4>{title}</h4>
        <span>{meta}</span>
      </div>
      {items.length === 0 ? <p className="asset-empty-detail">{empty}</p> : <ul>{children}</ul>}
    </section>
  );
}

function chartData(data: MetricPoint[]) {
  return data.map((point) => ({
    ...point,
    label: formatTime(point.timestamp),
    value: point.value == null ? null : Number(point.value.toFixed(2)),
    inBps: point.inBps == null ? null : Number(point.inBps.toFixed(2)),
    outBps: point.outBps == null ? null : Number(point.outBps.toFixed(2))
  }));
}

function fallbackOverview(assets: Asset[], range: string): AssetMetricsOverview {
  return {
    range,
    summary: {
      totalAssets: assets.length,
      observedAssets: 0,
      staleAssets: 0,
      criticalAssets: 0,
      warningAssets: 0
    },
    assets: assets.map((asset) => ({
      assetUid: asset.assetUid,
      name: asset.name,
      assetType: asset.assetType,
      managementIp: asset.managementIp,
      status: asset.status,
      lastSeenAt: asset.lastSeenAt,
      stale: false,
      health: 'unknown',
      sources: { registered: true, observed: false },
      metrics: {}
    }))
  };
}

function metricTone(value?: number | null): 'neutral' | 'warning' | 'critical' {
  if (value == null || !Number.isFinite(value)) {
    return 'neutral';
  }
  if (value >= 90) {
    return 'critical';
  }
  if (value >= 80) {
    return 'warning';
  }
  return 'neutral';
}

function loadTone(value?: number | null): 'neutral' | 'warning' | 'critical' {
  if (value == null || !Number.isFinite(value)) {
    return 'neutral';
  }
  if (value >= 150) {
    return 'critical';
  }
  if (value >= 100) {
    return 'warning';
  }
  return 'neutral';
}

function healthStatusClass(health: AssetMetricSummary['health']) {
  if (health === 'critical') {
    return 'blocked';
  }
  if (health === 'warning') {
    return 'pending';
  }
  if (health === 'healthy') {
    return 'active';
  }
  return 'unknown';
}

function healthLabel(health: AssetMetricSummary['health']) {
  if (health === 'critical') {
    return 'CRITICAL';
  }
  if (health === 'warning') {
    return 'WARNING';
  }
  if (health === 'healthy') {
    return 'HEALTHY';
  }
  return '미수집';
}

function clampPercent(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return 0;
  }
  return Math.max(4, Math.min(100, value));
}

function formatPercent(value?: number | null, suffix = '%') {
  if (value == null || !Number.isFinite(value)) {
    return '미수집';
  }
  return `${value.toFixed(1)}${suffix}`;
}

function formatBytes(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let next = value;
  let index = 0;
  while (next >= 1024 && index < units.length - 1) {
    next /= 1024;
    index++;
  }
  return `${next.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

function formatDate(value?: string | null) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('ko-KR');
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}
