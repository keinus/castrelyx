import {
  Activity,
  AlertTriangle,
  Boxes,
  Clock3,
  Cpu,
  DatabaseZap,
  Gauge,
  HardDrive,
  Network,
  RadioTower,
  RefreshCw,
  ShieldAlert,
  Thermometer
} from 'lucide-react';
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  XAxis,
  YAxis
} from 'recharts';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart';
import { cn } from '@/lib/utils';
import { api } from '../lib/api';
import type {
  AgentCollectorSummary,
  AgentDashboard,
  AgentLogEvent,
  AlertRow,
  Asset,
  AssetMetricDetail,
  AssetMetricSummary,
  AssetMetricsOverview,
  DashboardSummary,
  InterfaceTraffic,
  MetricPoint
} from '../lib/types';
import { formatBps } from '../lib/uiModel';

type OverviewViewProps = {
  summary: DashboardSummary;
  alerts: AlertRow[];
  assets?: Asset[];
};

type KpiTone = 'neutral' | 'good' | 'warning' | 'critical';
type SourceTone = 'on' | 'warn' | 'off';
type AssetSignalReason = NonNullable<NonNullable<AssetMetricSummary['signals']>['reasons']>[number];

type DashboardLoadResult<T> = {
  ok: boolean;
  value: T;
};

type FleetSummary = {
  total: number;
  problem: number;
  stale: number;
  observed: number;
  latestSeenAt?: string | null;
};

type ResourceRankRow = {
  id: string;
  assetUid: string;
  label: string;
  metric: string;
  value: number;
  formatted: string;
  tone: KpiTone;
};

type PriorityItem = {
  id: string;
  assetUid?: string;
  title: string;
  detail: string;
  severity: 'CRITICAL' | 'WARNING';
};

const DETAIL_DASHBOARD_TIMEOUT_MS = 5000;
const OVERVIEW_METRICS_REFRESH_MS = 30_000;
const HEALTH_COLORS = {
  healthy: '#16a34a',
  warning: '#f59e0b',
  critical: '#dc2626',
  stale: '#f59e0b',
  unknown: '#94a3b8'
};

const emptyAssetMetrics: AssetMetricsOverview = {
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

const emptyAgentDashboard: AgentDashboard = {};

export function OverviewView({ summary, alerts, assets = [] }: OverviewViewProps) {
  const [assetMetrics, setAssetMetrics] = useState<AssetMetricsOverview>(emptyAssetMetrics);
  const [agentDashboard, setAgentDashboard] = useState<AgentDashboard>(emptyAgentDashboard);
  const [trafficRows, setTrafficRows] = useState<InterfaceTraffic[]>(summary.trafficTopInterfaces ?? []);
  const [recentEvents, setRecentEvents] = useState<AgentLogEvent[]>([]);
  const [selectedAssetUid, setSelectedAssetUid] = useState<string | null>(null);
  const [detail, setDetail] = useState<AssetMetricDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [detailError, setDetailError] = useState('');
  const [lastUpdatedAt, setLastUpdatedAt] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [detailRefreshToken, setDetailRefreshToken] = useState(0);
  const assetMetricsRef = useRef(assetMetrics);

  useEffect(() => {
    assetMetricsRef.current = assetMetrics;
  }, [assetMetrics]);

  const loadOverview = useCallback(async (mode: 'initial' | 'refresh' = 'initial') => {
    const backgroundRefresh = mode === 'refresh' && assetMetricsRef.current.assets.length > 0;
    if (backgroundRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError('');

    const [assetResult, agentResult, eventResult, trafficResult] = await Promise.all([
      loadWithTimeout(api.assetMetrics('1h'), emptyAssetMetrics),
      loadWithTimeout(api.agentDashboard(), emptyAgentDashboard),
      loadWithTimeout(api.agentLogs('24h', 'ALL', 200), [] as AgentLogEvent[]),
      loadWithTimeout(api.trafficInterfaces('1h'), summary.trafficTopInterfaces ?? [])
    ]);

    if (assetResult.ok || !backgroundRefresh) {
      setAssetMetrics(assetResult.value);
      setDetailRefreshToken((value) => value + 1);
    }
    if (agentResult.ok) {
      setAgentDashboard(agentResult.value);
    }
    if (eventResult.ok) {
      setRecentEvents(eventResult.value);
    }
    if (trafficResult.ok) {
      setTrafficRows(trafficResult.value);
    }
    if ([assetResult, agentResult, eventResult, trafficResult].some((result) => !result.ok)) {
      setError('일부 관제 정보를 불러오지 못했습니다. 마지막 정상 수집값을 유지합니다.');
    }
    setLastUpdatedAt(new Date().toISOString());
    setLoading(false);
    setRefreshing(false);
  }, [summary.trafficTopInterfaces]);

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    const refreshTimer = window.setInterval(() => {
      void loadOverview('refresh');
    }, OVERVIEW_METRICS_REFRESH_MS);
    return () => window.clearInterval(refreshTimer);
  }, [loadOverview]);

  const managedAssets = useMemo(
    () => buildManagedAssets(assetMetrics.assets, assets),
    [assetMetrics.assets, assets]
  );
  const rankedAssets = useMemo(() => [...managedAssets].sort(sortAssetsForOperations), [managedAssets]);
  const activeAlerts = useMemo(
    () => alerts.filter((alert) => alert.status === 'ACTIVE' && ['CRITICAL', 'WARNING'].includes(alert.severity)),
    [alerts]
  );
  const fleet = useMemo(
    () => summarizeFleet(rankedAssets, assetMetrics.summary, summary.activeAssets, activeAlerts.length),
    [activeAlerts.length, assetMetrics.summary, rankedAssets, summary.activeAssets]
  );
  const selectedAsset = useMemo(
    () => rankedAssets.find((asset) => asset.assetUid === selectedAssetUid) ?? rankedAssets[0] ?? null,
    [rankedAssets, selectedAssetUid]
  );
  const selectedDetail = detail?.asset.assetUid === selectedAsset?.assetUid ? detail : null;
  const importantEvents = useMemo(() => importantOnly(recentEvents), [recentEvents]);
  const priorityItems = useMemo(
    () => buildPriorityItems(rankedAssets, activeAlerts, importantEvents),
    [activeAlerts, importantEvents, rankedAssets]
  );
  const resourceRanks = useMemo(() => buildResourceRanks(rankedAssets), [rankedAssets]);
  const traffic = useMemo(
    () => mergeTrafficRows([trafficRows, summary.trafficTopInterfaces ?? []]).slice(0, 6),
    [summary.trafficTopInterfaces, trafficRows]
  );
  const healthRows = useMemo(() => healthDistribution(rankedAssets), [rankedAssets]);
  const sourceRows = useMemo(() => sourceCoverageRows(rankedAssets.slice(0, 5)), [rankedAssets]);
  const collectors = agentDashboard.collectors ?? [];
  const posture = useMemo(() => summarizeSecurityPosture(rankedAssets, agentDashboard), [agentDashboard, rankedAssets]);

  useEffect(() => {
    if (rankedAssets.length === 0) {
      if (selectedAssetUid !== null) {
        setSelectedAssetUid(null);
      }
      return;
    }
    if (!selectedAssetUid || !rankedAssets.some((asset) => asset.assetUid === selectedAssetUid)) {
      setSelectedAssetUid(rankedAssets[0].assetUid);
    }
  }, [rankedAssets, selectedAssetUid]);

  useEffect(() => {
    if (!selectedAsset?.assetUid) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    setDetailError('');
    api.assetMetricDetail(selectedAsset.assetUid, '1h')
      .then((response) => {
        if (!cancelled) {
          setDetail(response);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDetail((current) => current?.asset.assetUid === selectedAsset.assetUid ? current : null);
          setDetailError('선택 장비의 시계열 일부를 불러오지 못했습니다.');
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
  }, [detailRefreshToken, selectedAsset?.assetUid]);

  return (
    <section className="view-frame overview-nms overview-dashboard-home">
      <header className="overview-header">
        <div>
          <h2>개요</h2>
          <p>장비 상태를 중심으로 부하, 트래픽, 이벤트, 수집 신선도를 한 화면에서 판단합니다.</p>
        </div>
        <div className="overview-header-actions">
          <div className={`overview-live-state ${loading || refreshing ? 'pending' : error ? 'degraded' : 'active'}`}>
            <span aria-hidden="true" />
            <div>
              <strong>{loading ? '갱신 중' : refreshing ? '자동 갱신' : error ? '일부 지연' : '수집 정상'}</strong>
              <small>마지막 갱신 {formatDate(lastUpdatedAt)} · 30초 주기</small>
            </div>
          </div>
          <span className="overview-range-pill">범위 1h</span>
          <Button className="overview-refresh" variant="outline" size="sm" onClick={() => void loadOverview()} type="button">
            <RefreshCw data-icon="inline-start" className={cn((loading || refreshing) && 'asset-refresh-spin')} aria-hidden="true" />
            새로고침
          </Button>
        </div>
      </header>

      {error && <div className="notice overview-notice warning">{error}</div>}

      <section className="overview-kpi-strip" aria-label="Dashboard summary">
        <OverviewKpi icon={<Boxes aria-hidden="true" />} label="전체 장비" value={fleet.total} meta={`${fleet.observed} observed`} />
        <OverviewKpi icon={<AlertTriangle aria-hidden="true" />} label="문제 장비" value={fleet.problem} meta={`${assetMetrics.summary.criticalAssets} critical · ${assetMetrics.summary.warningAssets} warning`} tone={fleet.problem > 0 ? 'critical' : 'good'} />
        <OverviewKpi icon={<Cpu aria-hidden="true" />} label="평균 CPU" value={formatPercent(assetMetrics.summary.avgCpuUsagePct)} meta="0%도 정상 수집값" tone={metricTone(assetMetrics.summary.avgCpuUsagePct)} />
        <OverviewKpi icon={<Network aria-hidden="true" />} label="총 트래픽" value={formatBps(totalTrafficFromSummary(assetMetrics.summary, traffic))} meta={`RX ${formatBps(assetMetrics.summary.totalNetworkInBps ?? traffic.reduce((total, row) => total + row.inBps, 0))}`} />
      </section>

      <div className="overview-dashboard-shell">
        <div className="overview-main-stack">
          <FleetHealthPanel rows={healthRows} total={fleet.total} problem={fleet.problem} />

          <div className="overview-chart-grid">
            <SelectedAssetTrendPanel asset={selectedAsset} detail={selectedDetail} loading={detailLoading} error={detailError} />
            <ResourcePressurePanel rows={resourceRanks} onSelectAsset={setSelectedAssetUid} />
          </div>

          <TrafficPanel rows={traffic} />

          <div className="overview-chart-grid">
            <EventTrendPanel events={importantEvents} />
            <SourceCoveragePanel rows={sourceRows} />
          </div>
        </div>

        <aside className="overview-side-stack">
          <PriorityQueuePanel items={priorityItems} onSelectAsset={setSelectedAssetUid} />
          <CollectorFreshnessPanel collectors={collectors} />
          <SecurityPosturePanel posture={posture} />
        </aside>
      </div>
    </section>
  );
}

function OverviewKpi({
  icon,
  label,
  value,
  meta,
  tone = 'neutral'
}: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  meta: string;
  tone?: KpiTone;
}) {
  return (
    <section className={cn('overview-kpi', tone)}>
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{meta}</small>
    </section>
  );
}

function Panel({
  title,
  description,
  meta,
  className,
  children
}: {
  title: string;
  description: string;
  meta?: ReactNode;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={cn('overview-chart-panel', className)}>
      <div className="overview-chart-panel-heading">
        <div>
          <h3>{title}</h3>
          <p>{description}</p>
        </div>
        {meta && <span>{meta}</span>}
      </div>
      {children}
    </section>
  );
}

function FleetHealthPanel({ rows, total, problem }: { rows: ReturnType<typeof healthDistribution>; total: number; problem: number }) {
  const chartRows = rows.filter((row) => row.count > 0);
  const config = Object.fromEntries(rows.map((row) => [row.key, { label: row.label, color: row.color }])) satisfies ChartConfig;
  return (
    <Panel title="Fleet Health" description="전체 장비의 현재 상태 분포입니다." meta={<><strong>{problem}</strong> need action</>} className="overview-chart-panel-tall">
      <div className="overview-health-visual">
        <ChartContainer config={config} className="overview-donut-chart" role="img" aria-label="Fleet Health chart">
          <PieChart>
            <Pie data={chartRows} dataKey="count" nameKey="label" innerRadius={48} outerRadius={74} paddingAngle={2}>
              {chartRows.map((row) => <Cell key={row.key} fill={row.color} />)}
            </Pie>
            <ChartTooltip content={<ChartTooltipContent formatter={(value) => `${value} assets`} />} />
          </PieChart>
        </ChartContainer>
        <div className="overview-health-center" aria-hidden="true">
          <strong>{problem}</strong>
          <span>need action</span>
        </div>
        <div className="overview-health-legend">
          {rows.map((row) => (
            <MetricBar key={row.key} label={row.label} value={row.count} max={Math.max(total, 1)} display={`${row.count}`} tone={row.tone} />
          ))}
        </div>
      </div>
    </Panel>
  );
}

function SelectedAssetTrendPanel({
  asset,
  detail,
  loading,
  error
}: {
  asset: AssetMetricSummary | null;
  detail: AssetMetricDetail | null;
  loading: boolean;
  error: string;
}) {
  const rows = useMemo(() => buildTrendRows(detail), [detail]);
  const config = {
    cpu: { label: 'CPU', color: 'var(--chart-1)' },
    memory: { label: 'MEM', color: 'var(--chart-2)' },
    disk: { label: 'DISK', color: 'var(--chart-3)' },
    temperature: { label: 'TEMP', color: 'var(--chart-4)' }
  } satisfies ChartConfig;
  const latest = asset ? [
    `CPU ${formatPercent(asset.metrics.cpuUsagePct)}`,
    `MEM ${formatPercent(asset.metrics.memoryUsagePct)}`,
    `DISK ${formatPercent(asset.metrics.diskUsagePct)}`,
    `TEMP ${formatTemperature(asset.metrics.temperatureCelsius)}`
  ].join(' · ') : '-';

  return (
    <Panel title="선택 장비 추세" description={`${asset?.name ?? '선택 장비'}의 CPU/MEM/DISK/TEMP 흐름입니다.`} meta={loading ? 'loading' : latest}>
      {error && <div className="notice overview-notice warning">{error}</div>}
      {rows.length > 1 ? (
        <ChartContainer config={config} className="overview-trend-chart" role="img" aria-label="선택 장비 추세">
          <LineChart data={rows}>
            <CartesianGrid vertical={false} />
            <XAxis dataKey="label" tickLine={false} axisLine={false} />
            <YAxis tickLine={false} axisLine={false} width={34} domain={[0, 100]} />
            <ChartTooltip content={<ChartTooltipContent formatter={(value, name) => formatTrendValue(Number(value), String(name))} />} />
            <Line type="monotone" dataKey="cpu" name="CPU" stroke="var(--color-cpu)" strokeWidth={2.5} dot={false} isAnimationActive />
            <Line type="monotone" dataKey="memory" name="MEM" stroke="var(--color-memory)" strokeWidth={2.5} dot={false} isAnimationActive />
            <Line type="monotone" dataKey="disk" name="DISK" stroke="var(--color-disk)" strokeWidth={2.5} dot={false} isAnimationActive />
            <Line type="monotone" dataKey="temperature" name="TEMP" stroke="var(--color-temperature)" strokeWidth={2.5} dot={false} isAnimationActive />
          </LineChart>
        </ChartContainer>
      ) : (
        <EmptyState title="시계열 대기" detail="선택 장비의 1시간 시계열이 아직 충분하지 않습니다." />
      )}
    </Panel>
  );
}

function ResourcePressurePanel({ rows, onSelectAsset }: { rows: ResourceRankRow[]; onSelectAsset: (assetUid: string) => void }) {
  return (
    <Panel title="리소스 압박 Top" description="임계치에 가까운 장비를 랭킹으로 봅니다." meta={`${rows.length} signals`}>
      <div className="overview-rank-list">
        {rows.length === 0 ? (
          <EmptyState title="압박 신호 없음" detail="최근 수집값에서 높은 리소스 사용률이 없습니다." />
        ) : rows.map((row) => (
          <button key={row.id} className="overview-rank-button" type="button" onClick={() => onSelectAsset(row.assetUid)}>
            <MetricBar label={`${row.assetUid} · ${row.metric}`} value={row.value} max={100} display={row.formatted} tone={row.tone} />
          </button>
        ))}
      </div>
    </Panel>
  );
}

function TrafficPanel({ rows }: { rows: InterfaceTraffic[] }) {
  const chartRows = rows.map((row) => ({
    name: `${row.assetUid}/${row.interfaceName}`,
    inBps: row.inBps,
    outBps: row.outBps
  }));
  const config = {
    inBps: { label: 'RX', color: 'var(--chart-1)' },
    outBps: { label: 'TX', color: 'var(--chart-2)' }
  } satisfies ChartConfig;
  return (
    <Panel title="상위 트래픽 인터페이스" description="트래픽이 몰리는 인터페이스와 방향을 비교합니다." meta={`${rows.length} interfaces`} className="overview-chart-panel-tall">
      {chartRows.length > 0 ? (
        <ChartContainer config={config} className="overview-traffic-chart" role="img" aria-label="상위 트래픽 인터페이스">
          <BarChart data={chartRows} layout="vertical" margin={{ left: 20, right: 20 }}>
            <CartesianGrid horizontal={false} />
            <XAxis type="number" tickLine={false} axisLine={false} tickFormatter={(value) => formatBps(Number(value)).replace(' ', '')} />
            <YAxis type="category" dataKey="name" tickLine={false} axisLine={false} width={112} />
            <ChartTooltip content={<ChartTooltipContent formatter={(value) => formatBps(Number(value))} />} />
            <Bar dataKey="inBps" name="RX" stackId="traffic" fill="var(--color-inBps)" radius={[0, 6, 6, 0]} isAnimationActive />
            <Bar dataKey="outBps" name="TX" stackId="traffic" fill="var(--color-outBps)" radius={[0, 6, 6, 0]} isAnimationActive />
          </BarChart>
        </ChartContainer>
      ) : (
        <EmptyState title="트래픽 신호 대기" detail="수집된 인터페이스 트래픽이 아직 없습니다." />
      )}
    </Panel>
  );
}

function EventTrendPanel({ events }: { events: AgentLogEvent[] }) {
  const rows = useMemo(() => buildEventBuckets(events), [events]);
  const config = {
    warning: { label: 'Warning', color: 'var(--chart-3)' },
    critical: { label: 'Critical', color: 'var(--chart-4)' }
  } satisfies ChartConfig;
  return (
    <Panel title="이벤트 추세" description="최근 24시간 경고/오류 밀도입니다." meta={`${events.length} events`}>
      <ChartContainer config={config} className="overview-event-chart" role="img" aria-label="이벤트 추세">
        <BarChart data={rows}>
          <CartesianGrid vertical={false} />
          <XAxis dataKey="label" tickLine={false} axisLine={false} />
          <YAxis allowDecimals={false} tickLine={false} axisLine={false} width={28} />
          <ChartTooltip content={<ChartTooltipContent />} />
          <Bar dataKey="warning" name="Warning" stackId="events" fill="var(--color-warning)" radius={[4, 4, 0, 0]} isAnimationActive />
          <Bar dataKey="critical" name="Critical" stackId="events" fill="var(--color-critical)" radius={[4, 4, 0, 0]} isAnimationActive />
        </BarChart>
      </ChartContainer>
    </Panel>
  );
}

function SourceCoveragePanel({ rows }: { rows: ReturnType<typeof sourceCoverageRows> }) {
  const heads = ['Agent', 'Traffic', 'Disk', 'Security', 'Events'];
  return (
    <Panel title="Source Coverage" description="장비별 수집 경로의 빈칸을 찾습니다." meta={`${rows.length} assets`}>
      <div className="overview-source-matrix">
        <div className="overview-source-row overview-source-head">
          <strong />
          {heads.map((head) => <strong key={head}>{head}</strong>)}
        </div>
        {rows.length === 0 ? (
          <EmptyState title="수집 경로 대기" detail="표시할 장비 coverage가 아직 없습니다." />
        ) : rows.map((row) => (
          <div className="overview-source-row" key={row.assetUid}>
            <strong>{row.assetUid}</strong>
            {row.cells.map((cell) => <span key={`${row.assetUid}-${cell.label}`} className={cn('overview-source-cell', cell.tone)} title={cell.label} />)}
          </div>
        ))}
      </div>
    </Panel>
  );
}

function PriorityQueuePanel({ items, onSelectAsset }: { items: PriorityItem[]; onSelectAsset: (assetUid: string) => void }) {
  return (
    <Panel title="대응 큐" description="목록 대신 지금 볼 이유만 보여줍니다." meta={`${items.length} active`}>
      <div className="overview-priority-card-list">
        {items.length === 0 ? (
          <EmptyState title="대응 대기 없음" detail="활성 Critical/Warning 알림과 최근 중요 이벤트가 없습니다." />
        ) : items.map((item) => (
          <button key={item.id} className="overview-priority-card" type="button" onClick={() => item.assetUid && onSelectAsset(item.assetUid)}>
            <div>
              <strong>{item.title}</strong>
              <Badge variant={item.severity === 'CRITICAL' ? 'critical' : 'warning'}>{item.severity}</Badge>
            </div>
            <span>{item.detail}</span>
          </button>
        ))}
      </div>
    </Panel>
  );
}

function CollectorFreshnessPanel({ collectors }: { collectors: AgentCollectorSummary[] }) {
  return (
    <Panel title="Collector Freshness" description="수집기가 제때 들어오는지 봅니다." meta={`${collectors.length} collectors`}>
      <div className="overview-rank-list">
        {collectors.length === 0 ? (
          <EmptyState title="Collector 대기" detail="아직 표시할 collector coverage가 없습니다." />
        ) : collectors.slice(0, 6).map((collector) => (
          <MetricBar
            key={`${collector.name}-${collector.lastSeenAt}`}
            label={collector.name ?? '-'}
            value={collector.sampleCount ?? 0}
            max={Math.max(1, ...collectors.map((row) => row.sampleCount ?? 0))}
            display={`${collector.sampleCount ?? 0}`}
            tone={collectorTone(collector)}
            meta={formatDate(collector.lastSeenAt)}
          />
        ))}
      </div>
    </Panel>
  );
}

function SecurityPosturePanel({ posture }: { posture: ReturnType<typeof summarizeSecurityPosture> }) {
  return (
    <Panel title="보안 포스처" description="노출 포트, 실패 서비스, 방화벽 상태입니다." meta="signals">
      <div className="overview-rank-list">
        <MetricBar label="노출 포트" value={posture.openPorts} max={Math.max(24, posture.openPorts)} display={`${posture.openPorts}`} tone={posture.openPorts > 0 ? 'warning' : 'good'} />
        <MetricBar label="실패 서비스" value={posture.failedServices} max={Math.max(5, posture.failedServices)} display={`${posture.failedServices}`} tone={posture.failedServices > 0 ? 'critical' : 'good'} />
        <MetricBar label="방화벽 비활성" value={posture.firewallDisabled} max={Math.max(5, posture.firewallDisabled)} display={`${posture.firewallDisabled}`} tone={posture.firewallDisabled > 0 ? 'warning' : 'good'} />
        <MetricBar label="다운 인터페이스" value={posture.interfacesDown} max={Math.max(5, posture.interfacesDown)} display={`${posture.interfacesDown}`} tone={posture.interfacesDown > 0 ? 'warning' : 'good'} />
      </div>
    </Panel>
  );
}

function MetricBar({
  label,
  value,
  max,
  display,
  tone,
  meta
}: {
  label: string;
  value: number;
  max: number;
  display: string;
  tone: KpiTone;
  meta?: string;
}) {
  const width = Math.max(value > 0 ? 4 : 0, Math.min(100, (value / Math.max(max, 1)) * 100));
  return (
    <div className="overview-metric-bar">
      <div>
        <strong>{label}</strong>
        <span>{meta ?? display}</span>
      </div>
      <div className="overview-bar-track"><i className={cn(tone)} style={{ width: `${width}%` }} /></div>
      <em>{display}</em>
    </div>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="overview-empty-state">
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

function buildManagedAssets(metricAssets: AssetMetricSummary[], registeredAssets: Asset[]): AssetMetricSummary[] {
  const rows = new Map<string, AssetMetricSummary>();
  for (const asset of metricAssets) {
    rows.set(asset.assetUid, asset);
  }
  for (const asset of registeredAssets) {
    if (!rows.has(asset.assetUid)) {
      rows.set(asset.assetUid, {
        assetUid: asset.assetUid,
        name: asset.name,
        assetType: asset.assetType,
        managementIp: asset.managementIp,
        location: asset.location,
        status: asset.status,
        lastSeenAt: asset.lastSeenAt,
        stale: true,
        health: 'unknown',
        sources: { registered: true },
        metrics: {},
        signals: {
          reasons: [{ code: 'not_observed', label: '수집 데이터 없음', severity: 'warning', detail: null }],
          interfacesDown: 0,
          eventCounts: {},
          lastEventAt: null,
          collectorFreshness: { stale: true, lastSeenAt: asset.lastSeenAt ?? null }
        }
      });
    }
  }
  return [...rows.values()];
}

function summarizeFleet(assets: AssetMetricSummary[], summary: AssetMetricsOverview['summary'], fallbackTotal: number, activeAlertCount: number): FleetSummary {
  const total = summary.totalAssets || assets.length || fallbackTotal;
  const problem = (summary.criticalAssets + summary.warningAssets) || assets.filter((asset) => asset.health === 'critical' || asset.health === 'warning').length || activeAlertCount;
  const stale = summary.staleAssets || assets.filter((asset) => asset.stale).length;
  const observed = summary.observedAssets || assets.filter((asset) => asset.sources.observed).length;
  return { total, problem, stale, observed, latestSeenAt: latestSeenAt(assets) };
}

function healthDistribution(assets: AssetMetricSummary[]) {
  const counts = {
    healthy: assets.filter((asset) => asset.health === 'healthy' && !asset.stale).length,
    warning: assets.filter((asset) => asset.health === 'warning' && !asset.stale).length,
    critical: assets.filter((asset) => asset.health === 'critical').length,
    stale: assets.filter((asset) => asset.stale).length,
    unknown: assets.filter((asset) => asset.health === 'unknown' && !asset.stale).length
  };
  return [
    { key: 'healthy', label: 'Healthy', count: counts.healthy, color: HEALTH_COLORS.healthy, tone: 'good' as const },
    { key: 'warning', label: 'Warning', count: counts.warning, color: HEALTH_COLORS.warning, tone: 'warning' as const },
    { key: 'critical', label: 'Critical', count: counts.critical, color: HEALTH_COLORS.critical, tone: 'critical' as const },
    { key: 'stale', label: 'Stale', count: counts.stale, color: HEALTH_COLORS.stale, tone: 'warning' as const },
    { key: 'unknown', label: 'Unknown', count: counts.unknown, color: HEALTH_COLORS.unknown, tone: 'neutral' as const }
  ];
}

function buildResourceRanks(assets: AssetMetricSummary[]): ResourceRankRow[] {
  return assets.flatMap((asset) => {
    const metrics = [
      ['CPU', asset.metrics.cpuUsagePct, formatPercent],
      ['Memory', asset.metrics.memoryUsagePct, formatPercent],
      ['Disk', asset.metrics.diskUsagePct, formatPercent],
      ['Disk I/O', asset.metrics.diskIoUtilizationPct, formatPercent],
      ['Load', asset.metrics.normalizedLoadPct, formatPercent],
      ['Temperature', asset.metrics.temperatureCelsius, formatTemperature]
    ] as const;
    return metrics
      .filter(([, value]) => isFiniteNumber(value))
      .map(([metric, value, formatter]) => ({
        id: `${asset.assetUid}-${metric}`,
        assetUid: asset.assetUid,
        label: asset.name || asset.assetUid,
        metric,
        value: Number(value),
        formatted: formatter(Number(value)),
        tone: metric === 'Temperature' ? temperatureTone(Number(value)) : metricTone(Number(value))
      }));
  })
    .sort((left, right) => right.value - left.value)
    .slice(0, 6);
}

function buildPriorityItems(assets: AssetMetricSummary[], alerts: AlertRow[], events: AgentLogEvent[]): PriorityItem[] {
  const assetItems = assets
    .filter((asset) => asset.health === 'critical' || asset.health === 'warning' || asset.stale || signalReasons(asset).length > 0)
    .slice(0, 5)
    .map((asset) => ({
      id: `asset-${asset.assetUid}`,
      assetUid: asset.assetUid,
      title: asset.name || asset.assetUid,
      detail: signalReasons(asset).slice(0, 2).map((reason) => [reason.label, reason.detail].filter(Boolean).join(' ')).join(' · ')
        || (asset.stale ? `수집 지연 ${formatDate(asset.lastSeenAt)}` : healthLabel(asset.health)),
      severity: asset.health === 'critical' ? 'CRITICAL' as const : 'WARNING' as const
    }));
  const alertItems = alerts.slice(0, 2).map((alert) => ({
    id: `alert-${alert.id}`,
    title: alert.title,
    detail: alert.detail || alert.status,
    severity: alert.severity === 'CRITICAL' ? 'CRITICAL' as const : 'WARNING' as const
  }));
  const eventItems = events.slice(0, 2).map((event, index) => ({
    id: `event-${event.assetUid ?? 'event'}-${event.observedAt ?? index}`,
    assetUid: event.assetUid,
    title: event.assetUid || event.sourceName || event.eventType || 'Agent event',
    detail: event.message || event.eventType || formatDate(event.observedAt ?? event.eventTime),
    severity: (event.severity ?? '').toUpperCase() === 'CRITICAL' || (event.severity ?? '').toUpperCase() === 'ERROR' ? 'CRITICAL' as const : 'WARNING' as const
  }));
  return [...assetItems, ...alertItems, ...eventItems]
    .sort((left, right) => severityRank(right.severity) - severityRank(left.severity))
    .slice(0, 6);
}

function buildTrendRows(detail: AssetMetricDetail | null) {
  if (!detail) {
    return [];
  }
  const rows = new Map<string, Record<string, number | string | null>>();
  mergeSeries(rows, detail.series.cpu, 'cpu');
  mergeSeries(rows, detail.series.memory, 'memory');
  mergeSeries(rows, detail.series.disk, 'disk');
  mergeSeries(rows, detail.series.temperature, 'temperature');
  return [...rows.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .slice(-24)
    .map(([timestamp, row]) => ({ timestamp, label: formatTime(timestamp), ...row }));
}

function mergeSeries(rows: Map<string, Record<string, number | string | null>>, points: MetricPoint[] | undefined, key: string) {
  for (const point of points ?? []) {
    const value = finiteNumber(point.value);
    if (value == null) {
      continue;
    }
    const row = rows.get(point.timestamp) ?? {};
    row[key] = value;
    rows.set(point.timestamp, row);
  }
}

function buildEventBuckets(events: AgentLogEvent[]) {
  const buckets = Array.from({ length: 8 }, (_, index) => ({
    label: `${index + 1}`,
    warning: 0,
    critical: 0
  }));
  const sortedEvents = [...events].sort((left, right) => Date.parse(left.observedAt ?? left.eventTime ?? '') - Date.parse(right.observedAt ?? right.eventTime ?? ''));
  for (const [index, event] of sortedEvents.entries()) {
    const bucket = buckets[Math.min(buckets.length - 1, Math.floor(index / Math.max(1, Math.ceil(sortedEvents.length / buckets.length))))];
    const severity = (event.severity ?? '').toUpperCase();
    if (severity === 'ERROR' || severity === 'CRITICAL') {
      bucket.critical += 1;
    } else {
      bucket.warning += 1;
    }
  }
  return buckets;
}

function sourceCoverageRows(assets: AssetMetricSummary[]) {
  return assets.map((asset) => ({
    assetUid: asset.assetUid,
    cells: [
      { label: 'Agent', tone: sourceTone(asset.sources.agent) },
      { label: 'Traffic', tone: sourceTone(asset.sources.traffic) },
      { label: 'Disk', tone: sourceTone(asset.sources.diskIo) },
      { label: 'Security', tone: sourceTone(asset.sources.security) },
      { label: 'Events', tone: sourceTone(Boolean(asset.signals?.lastEventAt || Object.keys(asset.signals?.eventCounts ?? {}).length > 0)) }
    ]
  }));
}

function sourceTone(enabled?: boolean): SourceTone {
  return enabled ? 'on' : 'off';
}

function summarizeSecurityPosture(assets: AssetMetricSummary[], agentDashboard: AgentDashboard) {
  const security = agentDashboard.securityPosture;
  return {
    openPorts: security?.exposedPorts ?? assets.reduce((total, asset) => total + (asset.security?.openPorts ?? 0), 0),
    failedServices: security?.failedServices ?? assets.reduce((total, asset) => total + (asset.security?.failedServices ?? 0), 0),
    firewallDisabled: security?.firewallDisabled ?? assets.reduce((total, asset) => total + (asset.security?.firewallDisabled ?? 0), 0),
    interfacesDown: assets.reduce((total, asset) => total + (asset.security?.interfacesDown ?? asset.signals?.interfacesDown ?? 0), 0)
  };
}

function mergeTrafficRows(groups: InterfaceTraffic[][]) {
  const rows = new Map<string, InterfaceTraffic>();
  for (const group of groups) {
    for (const row of group) {
      const key = `${row.assetUid}-${row.interfaceName}`;
      const previous = rows.get(key);
      if (!previous) {
        rows.set(key, row);
      } else {
        rows.set(key, {
          ...previous,
          inBps: Math.max(previous.inBps, row.inBps),
          outBps: Math.max(previous.outBps, row.outBps),
          utilizationPct: Math.max(previous.utilizationPct, row.utilizationPct),
          errors: Math.max(previous.errors, row.errors),
          discards: Math.max(previous.discards, row.discards)
        });
      }
    }
  }
  return [...rows.values()].sort((left, right) => totalTraffic(right) - totalTraffic(left));
}

function sortAssetsForOperations(left: AssetMetricSummary, right: AssetMetricSummary) {
  const leftRank = healthRank(left.health) + (left.stale ? 3 : 0) + signalReasons(left).length;
  const rightRank = healthRank(right.health) + (right.stale ? 3 : 0) + signalReasons(right).length;
  if (leftRank !== rightRank) {
    return rightRank - leftRank;
  }
  return assetTraffic(right) - assetTraffic(left);
}

function signalReasons(asset: AssetMetricSummary): AssetSignalReason[] {
  return asset.signals?.reasons ?? [];
}

function importantOnly(events: AgentLogEvent[]) {
  return events.filter((event) => ['ERROR', 'WARNING', 'CRITICAL'].includes((event.severity ?? '').toUpperCase()));
}

function collectorTone(collector: AgentCollectorSummary): KpiTone {
  if (!collector.lastSeenAt) {
    return 'warning';
  }
  const age = Date.now() - Date.parse(collector.lastSeenAt);
  if (!Number.isFinite(age)) {
    return 'neutral';
  }
  if (age > 10 * 60 * 1000) {
    return 'warning';
  }
  return 'good';
}

function healthRank(health?: AssetMetricSummary['health']) {
  if (health === 'critical') {
    return 10;
  }
  if (health === 'warning') {
    return 6;
  }
  if (health === 'unknown') {
    return 3;
  }
  return 1;
}

function healthLabel(health?: AssetMetricSummary['health']) {
  if (health === 'critical') {
    return '위험';
  }
  if (health === 'warning') {
    return '주의';
  }
  if (health === 'healthy') {
    return '정상';
  }
  return '미확인';
}

function severityRank(severity: string) {
  return severity === 'CRITICAL' ? 2 : 1;
}

function totalTraffic(row: InterfaceTraffic) {
  return row.inBps + row.outBps;
}

function assetTraffic(asset: Pick<AssetMetricSummary, 'metrics'>) {
  return safeNumber(asset.metrics.networkInBps) + safeNumber(asset.metrics.networkOutBps);
}

function totalTrafficFromSummary(summary: AssetMetricsOverview['summary'], rows: InterfaceTraffic[]) {
  return safeNumber(summary.totalNetworkInBps) + safeNumber(summary.totalNetworkOutBps)
    || rows.reduce((total, row) => total + totalTraffic(row), 0);
}

function latestSeenAt(assets: AssetMetricSummary[]) {
  return assets
    .map((asset) => asset.lastSeenAt)
    .filter((value): value is string => Boolean(value))
    .sort()
    .at(-1) ?? null;
}

function safeNumber(value?: number | null) {
  return Number.isFinite(value) ? Number(value) : 0;
}

function finiteNumber(value?: number | null) {
  return value == null || !Number.isFinite(value) ? null : Number(value);
}

function isFiniteNumber(value?: number | null): value is number {
  return value != null && Number.isFinite(value);
}

function metricTone(value?: number | null): KpiTone {
  if (value == null || !Number.isFinite(value)) {
    return 'neutral';
  }
  if (value >= 90) {
    return 'critical';
  }
  if (value >= 80) {
    return 'warning';
  }
  return 'good';
}

function temperatureTone(value?: number | null): KpiTone {
  if (value == null || !Number.isFinite(value)) {
    return 'neutral';
  }
  if (value >= 90) {
    return 'critical';
  }
  if (value >= 80) {
    return 'warning';
  }
  return 'good';
}

function formatPercent(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  return `${value.toFixed(1)}%`;
}

function formatTemperature(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  return `${value.toFixed(1)}C`;
}

function formatTrendValue(value: number, name: string) {
  if (name === 'TEMP') {
    return formatTemperature(value);
  }
  return formatPercent(value);
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

function loadWithTimeout<T>(request: Promise<T>, fallback: T): Promise<DashboardLoadResult<T>> {
  return Promise.race([
    request
      .then((value) => ({ ok: true, value }))
      .catch(() => ({ ok: false, value: fallback })),
    new Promise<DashboardLoadResult<T>>((resolve) => {
      window.setTimeout(() => resolve({ ok: false, value: fallback }), DETAIL_DASHBOARD_TIMEOUT_MS);
    })
  ]);
}
