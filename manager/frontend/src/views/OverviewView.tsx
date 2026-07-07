import {
  Activity,
  AlertTriangle,
  Boxes,
  Clock3,
  Cpu,
  Gauge,
  HardDrive,
  ListChecks,
  Network,
  RefreshCw,
  Server,
  ShieldAlert
} from 'lucide-react';
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Area, AreaChart, XAxis, YAxis } from 'recharts';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { ChartContainer, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/utils';
import { api } from '../lib/api';
import type {
  AgentLogEvent,
  AgentProcessState,
  AgentServiceState,
  AlertRow,
  Asset,
  AssetMetricDetail,
  AssetMetricSummary,
  AssetMetricsOverview,
  DashboardSummary,
  MetricPoint
} from '../lib/types';
import { formatBps } from '../lib/uiModel';

type OverviewViewProps = {
  summary: DashboardSummary;
  alerts: AlertRow[];
  assets?: Asset[];
};

type KpiTone = 'neutral' | 'good' | 'warning' | 'critical';
type AssetSignalReason = NonNullable<NonNullable<AssetMetricSummary['signals']>['reasons']>[number];

type FleetSummary = {
  total: number;
  problem: number;
  stale: number;
  observed: number;
  latestSeenAt?: string | null;
};

type DashboardLoadResult<T> = {
  ok: boolean;
  value: T;
};

const DETAIL_DASHBOARD_TIMEOUT_MS = 5000;
const OVERVIEW_METRICS_REFRESH_MS = 30_000;

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

export function OverviewView({ summary, alerts, assets = [] }: OverviewViewProps) {
  const [assetMetrics, setAssetMetrics] = useState<AssetMetricsOverview>(emptyAssetMetrics);
  const [selectedAssetUid, setSelectedAssetUid] = useState<string | null>(null);
  const [detail, setDetail] = useState<AssetMetricDetail | null>(null);
  const [importantEvents, setImportantEvents] = useState<AgentLogEvent[]>([]);
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
    const result = await loadWithTimeout(api.assetMetrics('1h'), emptyAssetMetrics);
    if (result.ok || !backgroundRefresh) {
      setAssetMetrics(result.value);
      setDetailRefreshToken((value) => value + 1);
    }
    if (!result.ok) {
      setError('장비 상태 정보를 불러오지 못했습니다. 등록 자산 기준으로 표시합니다.');
    }
    setLastUpdatedAt(new Date().toISOString());
    setLoading(false);
    setRefreshing(false);
  }, []);

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
  const sortedAssets = useMemo(
    () => [...managedAssets].sort(sortAssetsForOperations),
    [managedAssets]
  );
  const activeAlertCount = useMemo(
    () => alerts.filter((alert) => alert.status === 'ACTIVE' && ['CRITICAL', 'WARNING'].includes(alert.severity)).length,
    [alerts]
  );
  const fleet = useMemo(
    () => summarizeFleet(sortedAssets, assetMetrics.summary, summary.activeAssets, activeAlertCount),
    [activeAlertCount, assetMetrics.summary, sortedAssets, summary.activeAssets]
  );
  const selectedAsset = useMemo(
    () => sortedAssets.find((asset) => asset.assetUid === selectedAssetUid) ?? sortedAssets[0] ?? null,
    [selectedAssetUid, sortedAssets]
  );

  useEffect(() => {
    if (sortedAssets.length === 0) {
      if (selectedAssetUid !== null) {
        setSelectedAssetUid(null);
      }
      return;
    }
    if (!selectedAssetUid || !sortedAssets.some((asset) => asset.assetUid === selectedAssetUid)) {
      setSelectedAssetUid(sortedAssets[0].assetUid);
    }
  }, [selectedAssetUid, sortedAssets]);

  useEffect(() => {
    if (!selectedAsset?.assetUid) {
      setDetail(null);
      setImportantEvents([]);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    setDetailError('');
    Promise.allSettled([
      api.assetMetricDetail(selectedAsset.assetUid, '1h'),
      api.agentLogs('24h', 'ALL', 20, selectedAsset.assetUid)
    ]).then(([detailResult, eventsResult]) => {
      if (cancelled) {
        return;
      }
      setDetail((current) => detailResult.status === 'fulfilled'
        ? detailResult.value
        : current?.asset.assetUid === selectedAsset.assetUid
          ? current
          : null);
      if (eventsResult.status === 'fulfilled') {
        setImportantEvents(importantOnly(eventsResult.value));
      }
      if (detailResult.status === 'rejected' || eventsResult.status === 'rejected') {
        setDetailError('선택 장비의 일부 진단 정보를 불러오지 못했습니다.');
      }
      setDetailLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [detailRefreshToken, selectedAsset?.assetUid]);

  return (
    <section className="view-frame overview-nms overview-device-home">
      <header className="overview-header">
        <div>
          <h2>개요</h2>
          <p>지금 확인해야 할 장비와 그 이유를 먼저 보여줍니다.</p>
        </div>
        <div className="overview-header-actions">
          <div className={`overview-live-state ${loading || refreshing ? 'pending' : error ? 'degraded' : 'active'}`}>
            <span aria-hidden="true" />
            <div>
              <strong>{loading ? '갱신 중' : refreshing ? '자동 갱신' : error ? '일부 지연' : '수집 정상'}</strong>
              <small>마지막 갱신 {formatDate(lastUpdatedAt)}</small>
            </div>
          </div>
          <Button className="overview-refresh" variant="outline" size="sm" onClick={() => void loadOverview()} type="button">
            <RefreshCw data-icon="inline-start" className={cn((loading || refreshing) && 'asset-refresh-spin')} aria-hidden="true" />
            새로고침
          </Button>
        </div>
      </header>

      {error && <div className="notice overview-notice warning">{error}</div>}

      <section className="overview-fleet-strip" aria-label="Fleet summary">
        <FleetKpi icon={<Boxes aria-hidden="true" />} label="전체 장비" value={fleet.total} meta={`${fleet.observed} observed`} />
        <FleetKpi
          icon={<AlertTriangle aria-hidden="true" />}
          label="문제 장비"
          value={fleet.problem}
          meta={`${assetMetrics.summary.criticalAssets} critical`}
          tone={fleet.problem > 0 ? 'critical' : 'good'}
        />
        <FleetKpi icon={<Clock3 aria-hidden="true" />} label="수집 지연" value={fleet.stale} meta="10분 초과" tone={fleet.stale > 0 ? 'warning' : 'good'} />
        <FleetKpi icon={<Activity aria-hidden="true" />} label="최근 수집" value={formatShortDate(fleet.latestSeenAt)} meta={formatDate(fleet.latestSeenAt)} />
      </section>

      <div className="overview-device-layout">
        <Card className="overview-device-board">
          <CardHeader className="overview-device-board-header">
            <div>
              <CardTitle>장비 상태 보드</CardTitle>
              <CardDescription>상태가 나쁜 장비와 수집 지연 장비를 먼저 정렬합니다.</CardDescription>
            </div>
            <Badge variant={fleet.problem > 0 ? 'critical' : 'secondary'}>{sortedAssets.length} 표시</Badge>
          </CardHeader>
          <CardContent className="overview-device-board-content">
            <DeviceStatusTable
              assets={sortedAssets}
              selectedAssetUid={selectedAsset?.assetUid}
              onSelect={setSelectedAssetUid}
            />
          </CardContent>
        </Card>

        <SelectedAssetPanel
          asset={selectedAsset}
          detail={detail?.asset.assetUid === selectedAsset?.assetUid ? detail : null}
          events={importantEvents}
          loading={detailLoading}
          error={detailError}
        />
      </div>
    </section>
  );
}

function FleetKpi({
  icon,
  label,
  value,
  meta,
  tone = 'neutral'
}: {
  icon: ReactNode;
  label: string;
  value: React.ReactNode;
  meta: string;
  tone?: KpiTone;
}) {
  return (
    <section className={cn('overview-fleet-kpi', tone)}>
      <div aria-hidden="true">{icon}</div>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{meta}</small>
    </section>
  );
}

function DeviceStatusTable({
  assets,
  selectedAssetUid,
  onSelect
}: {
  assets: AssetMetricSummary[];
  selectedAssetUid?: string | null;
  onSelect: (assetUid: string) => void;
}) {
  if (assets.length === 0) {
    return <EmptyState title="장비 상태 대기" detail="등록되거나 관측된 장비가 아직 없습니다." />;
  }
  return (
    <div className="overview-device-table-wrap">
      <Table className="overview-device-table">
        <TableHeader>
          <TableRow>
            <TableHead>상태</TableHead>
            <TableHead>장비</TableHead>
            <TableHead>마지막 수집</TableHead>
            <TableHead>문제 근거</TableHead>
            <TableHead>CPU/MEM/DISK/TEMP</TableHead>
            <TableHead>RX/TX</TableHead>
            <TableHead>상세</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {assets.map((asset) => (
            <TableRow
              key={asset.assetUid}
              className={cn('overview-device-row', asset.health, selectedAssetUid === asset.assetUid && 'selected')}
            >
              <TableCell><HealthBadge health={asset.health} stale={asset.stale} /></TableCell>
              <TableCell>
                <button className="overview-device-name" type="button" onClick={() => onSelect(asset.assetUid)}>
                  <strong>{asset.name || asset.assetUid}</strong>
                  <span>{asset.assetUid} · {asset.assetType || 'UNKNOWN'}</span>
                </button>
                <div className="overview-device-meta">
                  <Badge variant="secondary">{asset.managementIp || '-'}</Badge>
                  <span>{asset.location || asset.status || '위치 미지정'}</span>
                </div>
              </TableCell>
              <TableCell>
                <div className="overview-device-stack">
                  <strong>{asset.stale ? 'Stale' : asset.sources.observed ? 'Observed' : 'Registered'}</strong>
                  <span>{formatDate(asset.lastSeenAt)}</span>
                </div>
              </TableCell>
              <TableCell><ReasonBadges asset={asset} /></TableCell>
              <TableCell><MetricStrip asset={asset} /></TableCell>
              <TableCell><NetworkCell asset={asset} /></TableCell>
              <TableCell>
                <Button type="button" variant={selectedAssetUid === asset.assetUid ? 'default' : 'outline'} size="sm" onClick={() => onSelect(asset.assetUid)}>
                  상세
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function SelectedAssetPanel({
  asset,
  detail,
  events,
  loading,
  error
}: {
  asset: AssetMetricSummary | null;
  detail: AssetMetricDetail | null;
  events: AgentLogEvent[];
  loading: boolean;
  error: string;
}) {
  if (!asset) {
    return (
      <Card className="overview-selected-panel">
        <CardContent>
          <EmptyState title="선택 장비 없음" detail="장비가 수집되면 이곳에 진단 정보가 표시됩니다." />
        </CardContent>
      </Card>
    );
  }
  const effectiveAsset = detail?.asset ?? asset;
  const failedServices = (detail?.services ?? []).filter(isProblemService).slice(0, 8);
  const firewalls = detail?.firewalls ?? [];
  const downInterfaces = (detail?.interfaceStates ?? []).filter(isInterfaceDown).slice(0, 8);
  const processes = (detail?.processes ?? []).slice(0, 6);
  return (
    <Card className="overview-selected-panel">
      <CardHeader className="overview-selected-header">
        <div>
          <CardTitle>{effectiveAsset.name || effectiveAsset.assetUid}</CardTitle>
          <CardDescription>{[effectiveAsset.assetUid, effectiveAsset.managementIp].filter(Boolean).join(' · ')}</CardDescription>
        </div>
        <HealthBadge health={effectiveAsset.health} stale={effectiveAsset.stale} />
      </CardHeader>
      <CardContent className="overview-selected-content">
        {error && <div className="notice overview-notice warning">{error}</div>}
        {loading && <div className="notice overview-notice">선택 장비 진단 정보를 갱신하는 중입니다.</div>}

        <section className="overview-selected-section">
          <div className="overview-section-title">
            <ListChecks aria-hidden="true" />
            <strong>문제 근거</strong>
          </div>
          <ReasonList asset={effectiveAsset} />
        </section>

        <section className="overview-resource-snapshot">
          <ResourceSeries icon={<Cpu aria-hidden="true" />} title="CPU" value={formatPercent(effectiveAsset.metrics.cpuUsagePct)} points={detail?.series.cpu} field="value" />
          <ResourceSeries icon={<Gauge aria-hidden="true" />} title="MEM" value={formatPercent(effectiveAsset.metrics.memoryUsagePct)} points={detail?.series.memory} field="value" />
          <ResourceSeries icon={<HardDrive aria-hidden="true" />} title="DISK" value={formatPercent(effectiveAsset.metrics.diskUsagePct)} points={detail?.series.disk} field="value" />
          <ResourceSeries icon={<Network aria-hidden="true" />} title="NET" value={formatBps(assetTraffic(effectiveAsset))} points={detail?.series.network} field="inBps" />
        </section>

        <section className="overview-selected-section">
          <div className="overview-section-title">
            <ShieldAlert aria-hidden="true" />
            <strong>최근 중요 이벤트</strong>
          </div>
          <EventList events={events} />
        </section>

        <div className="overview-diagnostic-grid">
          <DiagnosticList title="Failed services" empty="서비스 이상 없음" items={failedServices} render={renderService} />
          <DiagnosticList title="Firewall" empty="방화벽 상태 없음" items={firewalls} render={renderFirewall} />
          <DiagnosticList title="Interfaces down" empty="다운 인터페이스 없음" items={downInterfaces} render={renderInterface} />
        </div>

        <section className="overview-selected-section">
          <div className="overview-section-title">
            <Server aria-hidden="true" />
            <strong>주요 프로세스</strong>
          </div>
          <ProcessList processes={processes} />
        </section>
      </CardContent>
    </Card>
  );
}

function MetricStrip({ asset }: { asset: AssetMetricSummary }) {
  return (
    <div className="overview-metric-strip">
      <MetricPill label="CPU" value={formatPercent(asset.metrics.cpuUsagePct)} tone={metricTone(asset.metrics.cpuUsagePct)} />
      <MetricPill label="MEM" value={formatPercent(asset.metrics.memoryUsagePct)} tone={metricTone(asset.metrics.memoryUsagePct)} />
      <MetricPill label="DISK" value={formatPercent(asset.metrics.diskUsagePct)} tone={metricTone(asset.metrics.diskUsagePct)} />
      <MetricPill label="TEMP" value={formatTemperature(asset.metrics.temperatureCelsius)} tone={temperatureTone(asset.metrics.temperatureCelsius)} />
    </div>
  );
}

function MetricPill({ label, value, tone }: { label: string; value: string; tone: KpiTone }) {
  return (
    <span className={cn('overview-metric-pill', tone)}>
      <em>{label}</em>
      <strong>{value}</strong>
    </span>
  );
}

function NetworkCell({ asset }: { asset: AssetMetricSummary }) {
  return (
    <div className="overview-device-stack">
      <strong>{formatBps(asset.metrics.networkInBps ?? 0)} RX</strong>
      <span>{formatBps(asset.metrics.networkOutBps ?? 0)} TX</span>
    </div>
  );
}

function ReasonBadges({ asset }: { asset: AssetMetricSummary }) {
  const reasons = signalReasons(asset).slice(0, 3);
  if (reasons.length === 0) {
    return <Badge variant="success">정상</Badge>;
  }
  return (
    <div className="overview-reason-badges">
      {reasons.map((reason) => (
        <Badge key={`${reason.code}-${reason.label}-${reason.detail}`} variant={reason.severity === 'critical' ? 'critical' : 'warning'}>
          {reason.label}{reason.detail ? ` ${reason.detail}` : ''}
        </Badge>
      ))}
    </div>
  );
}

function ReasonList({ asset }: { asset: AssetMetricSummary }) {
  const reasons = signalReasons(asset);
  if (reasons.length === 0) {
    return <EmptyState title="문제 근거 없음" detail="최근 수집 신호에서 임계치 초과나 상태 이상이 없습니다." />;
  }
  return (
    <ul className="overview-reason-list">
      {reasons.map((reason) => (
        <li className={reason.severity === 'critical' ? 'critical' : 'warning'} key={`${reason.code}-${reason.label}-${reason.detail}`}>
          <span aria-hidden="true" />
          <div>
            <strong>{reason.label}</strong>
            <small>{reason.detail || reason.code || '-'}</small>
          </div>
        </li>
      ))}
    </ul>
  );
}

function ResourceSeries({
  icon,
  title,
  value,
  points,
  field
}: {
  icon: ReactNode;
  title: string;
  value: string;
  points?: MetricPoint[];
  field: keyof MetricPoint;
}) {
  const chartRows = (points ?? [])
    .map((point) => ({ timestamp: point.timestamp, value: finiteNumber(point[field] as number | null | undefined) }))
    .filter((point): point is { timestamp: string; value: number } => point.value != null)
    .slice(-24);
  const yDomain: [number, number | 'auto'] = title === 'NET' ? [0, 'auto'] : [0, 100];
  const config = {
    value: { label: title, color: 'var(--chart-1)' }
  } satisfies ChartConfig;
  return (
    <section className="overview-resource-card">
      <div>
        {icon}
        <span>{title}</span>
        <strong>{value}</strong>
      </div>
      {chartRows.length > 1 ? (
        <ChartContainer config={config} className="overview-mini-chart" role="img" aria-label={`${title} 시계열`}>
          <AreaChart data={chartRows}>
            <XAxis dataKey="timestamp" hide />
            <YAxis hide domain={yDomain} />
            <ChartTooltip content={<ChartTooltipContent formatter={(tooltipValue) => formatTooltipValue(Number(tooltipValue), title)} />} />
            <Area type="monotone" dataKey="value" stroke="var(--color-value)" fill="var(--color-value)" fillOpacity={0.12} strokeWidth={2} isAnimationActive />
          </AreaChart>
        </ChartContainer>
      ) : (
        <span className="overview-mini-chart-empty">series 대기</span>
      )}
    </section>
  );
}

function DiagnosticList<T>({
  title,
  empty,
  items,
  render
}: {
  title: string;
  empty: string;
  items: T[];
  render: (item: T, index: number) => ReactNode;
}) {
  return (
    <section className="overview-diagnostic-list">
      <strong>{title}</strong>
      {items.length === 0 ? (
        <span>{empty}</span>
      ) : (
        <ul>{items.map((item, index) => <li key={index}>{render(item, index)}</li>)}</ul>
      )}
    </section>
  );
}

function EventList({ events }: { events: AgentLogEvent[] }) {
  if (events.length === 0) {
    return <EmptyState title="최근 중요 이벤트 없음" detail="최근 24시간 내 ERROR/WARNING 이벤트가 없습니다." />;
  }
  return (
    <ul className="overview-event-list">
      {events.slice(0, 8).map((event, index) => (
        <li key={`${event.assetUid}-${event.eventType}-${event.observedAt}-${index}`}>
          <Badge variant={eventTone(event.severity)}>{event.severity ?? 'INFO'}</Badge>
          <div>
            <strong>{event.message ?? event.sourceName ?? event.outcome ?? event.eventType ?? 'Agent event'}</strong>
            <span>{[event.eventType, formatDate(event.observedAt ?? event.eventTime)].filter(Boolean).join(' · ')}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

function ProcessList({ processes }: { processes: AgentProcessState[] }) {
  if (processes.length === 0) {
    return <EmptyState title="프로세스 상태 없음" detail="최근 프로세스 스냅샷이 없습니다." />;
  }
  return (
    <ul className="overview-process-list">
      {processes.map((process) => (
        <li key={`${process.pid}-${process.name}-${process.memoryBytes}`}>
          <div>
            <strong>{process.name || process.executablePath || 'process'}</strong>
            <span>pid {process.pid ?? '-'} · sockets {(process.listeningSocketCount ?? 0) + (process.connectedSocketCount ?? 0)}</span>
          </div>
          <em>{formatBytes(process.memoryBytes)}</em>
        </li>
      ))}
    </ul>
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

function HealthBadge({ health, stale }: { health: AssetMetricSummary['health']; stale?: boolean }) {
  const tone = stale ? 'warning' : health === 'critical' ? 'critical' : health === 'warning' ? 'warning' : health === 'healthy' ? 'success' : 'muted';
  return <Badge variant={tone}>{stale ? '수집 지연' : healthLabel(health)}</Badge>;
}

function renderService(service: AgentServiceState) {
  return (
    <>
      <strong>{service.name ?? service.displayName ?? 'service'}</strong>
      <span>{service.status ?? '-'}</span>
    </>
  );
}

function renderFirewall(firewall: NonNullable<AssetMetricDetail['firewalls']>[number]) {
  return (
    <>
      <strong>{firewall.backend ?? firewall.profile ?? 'firewall'}</strong>
      <span>{firewall.enabled === false ? 'disabled' : 'enabled'} · {firewall.ruleCount ?? 0} rules</span>
    </>
  );
}

function renderInterface(row: NonNullable<AssetMetricDetail['interfaceStates']>[number]) {
  return (
    <>
      <strong>{row.name ?? row.macAddress ?? 'interface'}</strong>
      <span>{row.operStatus ?? row.status ?? row.flags ?? '-'}</span>
    </>
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

function isProblemService(service: AgentServiceState) {
  const status = (service.status ?? '').toLowerCase();
  return status === 'failed' || status === 'error';
}

function isInterfaceDown(row: NonNullable<AssetMetricDetail['interfaceStates']>[number]) {
  const status = (row.operStatus ?? row.status ?? '').toLowerCase();
  return Boolean(status) && status !== 'up' && status !== 'unknown';
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

function eventTone(severity?: string): 'critical' | 'warning' | 'secondary' {
  const value = (severity ?? '').toUpperCase();
  if (value === 'ERROR' || value === 'CRITICAL') {
    return 'critical';
  }
  if (value === 'WARNING') {
    return 'warning';
  }
  return 'secondary';
}

function assetTraffic(asset: Pick<AssetMetricSummary, 'metrics'>) {
  return safeNumber(asset.metrics.networkInBps) + safeNumber(asset.metrics.networkOutBps);
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

function formatTooltipValue(value: number, title: string) {
  if (title === 'NET') {
    return formatBps(value);
  }
  return `${value.toFixed(1)}${title === 'TEMP' ? 'C' : '%'}`;
}

function formatBytes(value?: number | null) {
  if (value == null || !Number.isFinite(value) || value <= 0) {
    return '-';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let current = value;
  let unit = 0;
  while (current >= 1024 && unit < units.length - 1) {
    current /= 1024;
    unit += 1;
  }
  return `${current.toFixed(current >= 10 ? 0 : 1)} ${units[unit]}`;
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

function formatShortDate(value?: string | null) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '-';
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
