import { RefreshCw } from 'lucide-react';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import type {
  AgentDashboard,
  AgentEventSummary,
  AgentFirewallState,
  AgentServiceState,
  AgentSocketState,
  AlertRow,
  AssetMetricSummary,
  AssetMetricsOverview,
  DashboardSummary,
  InterfaceTraffic,
  SnmpDashboard
} from '../lib/types';
import { formatBps } from '../lib/uiModel';

type OverviewViewProps = {
  summary: DashboardSummary;
  alerts: AlertRow[];
};

type PriorityItem = {
  id: string;
  severity: string;
  title: string;
  meta: string;
};

type KpiTone = 'neutral' | 'good' | 'warning' | 'critical';

type KpiItem = {
  label: string;
  value: string;
  meta: string;
  tone: KpiTone;
};

type RankedAsset = {
  asset: AssetMetricSummary;
  value: number;
};

type DashboardLoadResult<T> = {
  ok: boolean;
  value: T;
};

const DETAIL_DASHBOARD_TIMEOUT_MS = 5000;

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

export function OverviewView({ summary, alerts }: OverviewViewProps) {
  const [agentDashboard, setAgentDashboard] = useState<AgentDashboard>({});
  const [snmpDashboard, setSnmpDashboard] = useState<SnmpDashboard>({});
  const [trafficDashboardRows, setTrafficDashboardRows] = useState<InterfaceTraffic[]>([]);
  const [assetMetrics, setAssetMetrics] = useState<AssetMetricsOverview>(emptyAssetMetrics);
  const [lastUpdatedAt, setLastUpdatedAt] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function loadContext() {
    setLoading(true);
    setError('');

    const [agentResult, snmpResult, trafficResult, assetResult] = await Promise.all([
      loadWithTimeout(api.agentDashboard(), {}),
      loadWithTimeout(api.snmpDashboard(), {}),
      loadWithTimeout(api.trafficInterfaces('1h'), []),
      loadWithTimeout(api.assetMetrics('1h'), emptyAssetMetrics)
    ]);

    setAgentDashboard(agentResult.value);
    setSnmpDashboard(snmpResult.value);
    setTrafficDashboardRows(trafficResult.value);
    setAssetMetrics(assetResult.value);

    if ([agentResult, snmpResult, trafficResult, assetResult].some((result) => !result.ok)) {
      setError('일부 관제 정보를 불러오지 못했습니다. 수집된 기본 신호로 상황판을 표시합니다.');
    }
    setLastUpdatedAt(new Date().toISOString());
    setLoading(false);
  }

  useEffect(() => {
    void loadContext();
  }, []);

  const activeAlerts = useMemo(
    () => alerts.filter((alert) => alert.status === 'ACTIVE' && ['CRITICAL', 'WARNING'].includes(alert.severity)),
    [alerts]
  );
  const activeCriticalCount = alerts.length > 0
    ? activeAlerts.filter((alert) => alert.severity === 'CRITICAL').length
    : summary.criticalAlerts;
  const activeWarningCount = activeAlerts.filter((alert) => alert.severity === 'WARNING').length;
  const heartbeat = {
    healthy: agentDashboard.heartbeat?.healthy ?? summary.agentHealth.healthy,
    stale: agentDashboard.heartbeat?.stale ?? summary.agentHealth.stale,
    lastSeenAt: agentDashboard.heartbeat?.lastSeenAt
  };
  const snmpPolls = snmpDashboard.polls ?? summary.snmpPollHealth;
  const trafficRows = useMemo(
    () => mergeTrafficRows([
      trafficDashboardRows,
      snmpDashboard.interfaces ?? [],
      summary.trafficTopInterfaces ?? []
    ]),
    [snmpDashboard.interfaces, summary.trafficTopInterfaces, trafficDashboardRows]
  );
  const topTraffic = useMemo(
    () => [...trafficRows].sort((left, right) => totalTraffic(right) - totalTraffic(left)).slice(0, 5),
    [trafficRows]
  );
  const maxTraffic = useMemo(() => Math.max(1, ...topTraffic.map(totalTraffic)), [topTraffic]);
  const noisyInterfaces = useMemo(
    () => trafficRows.filter((row) => row.errors + row.discards > 0).slice(0, 4),
    [trafficRows]
  );
  const rankedAssets = useMemo(() => rankAssets(assetMetrics.assets), [assetMetrics.assets]);

  const collectors = agentDashboard.collectors ?? [];
  const sockets = agentDashboard.states?.sockets ?? [];
  const services = agentDashboard.states?.services ?? [];
  const firewalls = agentDashboard.states?.firewalls ?? [];
  const events = agentDashboard.events ?? [];
  const posture = agentDashboard.securityPosture ?? {};
  const exposedSockets = useMemo(() => sockets.filter(isListeningSocket).slice(0, 5), [sockets]);
  const failedServices = useMemo(() => services.filter(isProblemService).slice(0, 5), [services]);
  const firewallFindings = useMemo(
    () => firewalls.filter((firewall) => firewall.enabled === false).slice(0, 5),
    [firewalls]
  );
  const openPortCount = posture.exposedPorts ?? exposedSockets.length;
  const failedServiceCount = posture.failedServices ?? failedServices.length;
  const firewallDisabledCount = posture.firewallDisabled ?? firewallFindings.length;
  const securityEventCount = posture.securityEvents ?? events.length;
  const priorityItems = useMemo(
    () => buildPriorityItems(activeAlerts, events),
    [activeAlerts, events]
  );
  const kpis: KpiItem[] = [
    { label: '전체 자산', value: String(summary.activeAssets), meta: 'managed assets', tone: 'neutral' },
    {
      label: 'Critical',
      value: String(activeCriticalCount),
      meta: `${activeWarningCount} warning`,
      tone: activeCriticalCount > 0 ? 'critical' : 'good'
    },
    {
      label: 'Agent 상태',
      value: `${heartbeat.healthy ?? 0}/${heartbeat.stale ?? 0}`,
      meta: 'healthy / stale',
      tone: (heartbeat.stale ?? 0) > 0 ? 'warning' : 'good'
    },
    {
      label: 'SNMP 실패',
      value: String(snmpPolls.failure ?? 0),
      meta: `${snmpPolls.success ?? 0} success`,
      tone: (snmpPolls.failure ?? 0) > 0 ? 'warning' : 'good'
    },
    {
      label: 'Open Ports',
      value: String(openPortCount),
      meta: `${securityEventCount} events`,
      tone: openPortCount > 0 ? 'warning' : 'good'
    }
  ];

  return (
    <section className="view-frame overview-command-center">
      <header className="command-header">
        <div className="command-title-block">
          <span className="command-eyebrow">Operations Home</span>
          <h2>NMS 보안 통합 관제</h2>
          <p>Network, security, and collection signals in one operator view.</p>
        </div>
        <div className="command-header-actions">
          <div className={`command-status ${loading ? 'pending' : 'active'}`}>
            <span className="command-status-dot" aria-hidden="true" />
            <div>
              <strong>{loading ? '갱신 중' : '수집 정상'}</strong>
              <span>마지막 갱신 {formatDate(lastUpdatedAt)}</span>
            </div>
          </div>
          <button className="icon-button command-refresh" aria-label="새로고침" onClick={() => void loadContext()} type="button">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      {error && <div className="notice command-notice degraded">{error}</div>}
      {loading && <div className="notice command-notice">관제 상세 정보를 갱신하는 중입니다.</div>}

      <KpiStrip items={kpis} />

      <div className="command-dashboard-grid">
        <DashboardPanel
          title="Network Health"
          meta={`${topTraffic.length} top interfaces`}
          className="command-panel-network"
        >
          <div className="network-health-summary">
            <SignalTile label="SNMP Success" value={String(snmpPolls.success ?? 0)} tone="good" />
            <SignalTile label="SNMP Failure" value={String(snmpPolls.failure ?? 0)} tone={(snmpPolls.failure ?? 0) > 0 ? 'warning' : 'good'} />
          </div>
          <TrafficMeterList rows={topTraffic} maxTraffic={maxTraffic} />
          <NoisyInterfaceList rows={noisyInterfaces} />
        </DashboardPanel>

        <DashboardPanel title="Response Queue" meta={`${priorityItems.length} active`}>
          <PriorityList items={priorityItems} />
        </DashboardPanel>

        <DashboardPanel title="Security Posture" meta={formatDate(heartbeat.lastSeenAt)}>
          <div className="posture-grid">
            <SignalTile label="노출 포트" value={String(openPortCount)} tone={openPortCount > 0 ? 'warning' : 'good'} />
            <SignalTile label="서비스 이상" value={String(failedServiceCount)} tone={failedServiceCount > 0 ? 'critical' : 'good'} />
            <SignalTile label="방화벽 비활성" value={String(firewallDisabledCount)} tone={firewallDisabledCount > 0 ? 'warning' : 'good'} />
            <SignalTile label="보안 이벤트" value={String(securityEventCount)} tone={securityEventCount > 0 ? 'neutral' : 'good'} />
          </div>
          <FindingList
            empty="현재 표시할 보안 finding 없음"
            rows={[
              ...exposedSockets.map((socket) => ({
                key: `socket-${socket.assetUid}-${socket.localAddress}-${socket.localPort}`,
                title: socket.assetUid ?? '-',
                meta: socketEndpoint(socket),
                value: socket.processName ?? 'listening',
                tone: 'warning' as KpiTone
              })),
              ...failedServices.map((service) => ({
                key: `service-${service.assetUid}-${service.name}`,
                title: service.name ?? '-',
                meta: service.assetUid ?? '-',
                value: service.status ?? 'failed',
                tone: 'critical' as KpiTone
              })),
              ...firewallFindings.map((firewall) => ({
                key: `firewall-${firewall.assetUid}-${firewall.backend}-${firewall.profile}`,
                title: firewall.assetUid ?? '-',
                meta: firewall.backend ?? 'firewall',
                value: firewallStatus(firewall),
                tone: 'warning' as KpiTone
              }))
            ].slice(0, 6)}
          />
        </DashboardPanel>

        <DashboardPanel title="Collection Coverage" meta={`${collectors.length} collectors`}>
          <div className="collection-heartbeat">
            <span>Agent last seen</span>
            <strong>{formatDate(heartbeat.lastSeenAt)}</strong>
          </div>
          <CollectorList collectors={collectors.slice(0, 6)} />
        </DashboardPanel>

        <DashboardPanel title="Asset Top 5" meta={`${assetMetrics.assets.length} assets`} className="command-panel-asset-top">
          <div className="asset-top5-grid">
            <AssetTopList title="CPU" rows={rankedAssets.cpu} format={formatPercent} />
            <AssetTopList title="Memory" rows={rankedAssets.memory} format={formatPercent} />
            <AssetTopList title="Disk" rows={rankedAssets.disk} format={formatPercent} />
            <AssetTopList title="Disk I/O" rows={rankedAssets.diskIo} format={formatPercent} />
            <AssetTopList title="Network" rows={rankedAssets.network} format={(value) => formatBps(value)} />
          </div>
        </DashboardPanel>

        <DashboardPanel title="Recent Security Events" meta={`${events.length} events`} className="command-panel-events">
          <EventList events={events.slice(0, 6)} />
        </DashboardPanel>
      </div>
    </section>
  );
}

function DashboardPanel({
  title,
  meta,
  className = '',
  children
}: {
  title: string;
  meta: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section className={`command-panel ${className}`}>
      <div className="command-panel-heading">
        <h3>{title}</h3>
        <span>{meta}</span>
      </div>
      {children}
    </section>
  );
}

function KpiStrip({ items }: { items: KpiItem[] }) {
  return (
    <div className="command-kpi-strip">
      {items.map((item) => (
        <section className={`command-kpi-card ${item.tone}`} key={item.label}>
          <span className="command-kpi-label"><i aria-hidden="true" />{item.label}</span>
          <strong>{item.value}</strong>
          <small>{item.meta}</small>
        </section>
      ))}
    </div>
  );
}

function SignalTile({ label, value, tone }: { label: string; value: string; tone: KpiTone }) {
  return (
    <div className={`signal-tile ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function AssetTopList({ title, rows, format }: { title: string; rows: RankedAsset[]; format: (value: number) => string }) {
  const max = Math.max(1, ...rows.map((row) => row.value));
  return (
    <section className="asset-top5-list">
      <div>
        <strong>{title} Top 5</strong>
        <span>{rows.length} assets</span>
      </div>
      {rows.length === 0 ? (
        <p>표시할 자산 메트릭 없음</p>
      ) : (
        <ul>
          {rows.map((row) => (
            <li key={`${title}-${row.asset.assetUid}`}>
              <div>
                <span>{row.asset.name}</span>
                <em>{format(row.value)}</em>
              </div>
              <i style={{ width: `${Math.max(4, Math.min(100, (row.value / max) * 100))}%` }} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function TrafficMeterList({ rows, maxTraffic }: { rows: InterfaceTraffic[]; maxTraffic: number }) {
  if (rows.length === 0) {
    return <EmptyState title="트래픽 신호 대기" detail="수집된 인터페이스 트래픽이 아직 없습니다." />;
  }
  return (
    <ul className="traffic-meter-list">
      {rows.map((row) => (
        <li key={`${row.assetUid}-${row.interfaceName}`}>
          <div className="traffic-row-title">
            <strong>{row.assetUid}</strong>
            <span>{row.interfaceName}</span>
            <em>{formatBps(totalTraffic(row))}</em>
          </div>
          <Meter label="In" value={row.inBps} max={maxTraffic} tone="in" />
          <Meter label="Out" value={row.outBps} max={maxTraffic} tone="out" />
        </li>
      ))}
    </ul>
  );
}

function Meter({ label, value, max, tone }: { label: string; value: number; max: number; tone: 'in' | 'out' }) {
  return (
    <div className={`traffic-meter ${tone}`}>
      <span>{label}</span>
      <div><i style={{ width: `${meterWidth(value, max)}%` }} /></div>
      <em>{formatBps(value)}</em>
    </div>
  );
}

function NoisyInterfaceList({ rows }: { rows: InterfaceTraffic[] }) {
  if (rows.length === 0) {
    return (
      <div className="quiet-strip">
        <strong>에러/드롭 없음</strong>
        <span>최근 수집 기준 인터페이스 오류 신호가 없습니다.</span>
      </div>
    );
  }
  return (
    <div className="noisy-interface-strip">
      <strong>에러/드롭 인터페이스</strong>
      <ul>
        {rows.map((row) => (
          <li key={`${row.assetUid}-${row.interfaceName}-noise`}>
            <span>{row.assetUid} / {row.interfaceName}</span>
            <em>{row.errors + row.discards}</em>
          </li>
        ))}
      </ul>
    </div>
  );
}

function PriorityList({ items }: { items: PriorityItem[] }) {
  if (items.length === 0) {
    return <EmptyState title="대응 대기 없음" detail="활성 Critical/Warning 알림과 최근 보안 이벤트가 없습니다." />;
  }
  return (
    <ul className="command-priority-list">
      {items.map((item) => (
        <li key={item.id}>
          <span className={`badge ${severityClass(item.severity)}`}>{item.severity}</span>
          <div>
            <strong>{item.title}</strong>
            <span>{item.meta}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

function FindingList({
  empty,
  rows
}: {
  empty: string;
  rows: { key: string; title: string; meta: string; value: string; tone: KpiTone }[];
}) {
  if (rows.length === 0) {
    return <EmptyState title="보안 finding 없음" detail={empty} />;
  }
  return (
    <ul className="finding-list">
      {rows.map((row) => (
        <li key={row.key}>
          <div>
            <strong>{row.title}</strong>
            <span>{row.meta}</span>
          </div>
          <em className={row.tone}>{row.value}</em>
        </li>
      ))}
    </ul>
  );
}

function CollectorList({ collectors }: { collectors: NonNullable<AgentDashboard['collectors']> }) {
  if (collectors.length === 0) {
    return <EmptyState title="Collector 대기" detail="아직 표시할 collector coverage가 없습니다." />;
  }
  return (
    <ul className="collector-list">
      {collectors.map((collector) => (
        <li key={`${collector.name}-${collector.lastSeenAt}`}>
          <div>
            <strong>{collector.name ?? '-'}</strong>
            <span>{formatDate(collector.lastSeenAt)}</span>
          </div>
          <em>{collector.sampleCount ?? 0}</em>
        </li>
      ))}
    </ul>
  );
}

function EventList({ events }: { events: AgentEventSummary[] }) {
  if (events.length === 0) {
    return <EmptyState title="최근 보안 이벤트 없음" detail="수집된 event가 아직 없습니다." />;
  }
  return (
    <ul className="command-event-list">
      {events.map((event, index) => (
        <li key={`${event.assetUid}-${event.eventType}-${event.observedAt}-${event.message}-${index}`}>
          <span className={`badge ${severityClass(event.severity)}`}>{event.severity ?? 'INFO'}</span>
          <div>
            <strong>{event.message ?? event.sourceName ?? event.outcome ?? event.eventType ?? 'Security event'}</strong>
            <span>{[event.assetUid, event.eventType, formatDate(event.observedAt)].filter(Boolean).join(' · ')}</span>
          </div>
        </li>
      ))}
    </ul>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="command-empty-state">
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

function buildPriorityItems(alerts: AlertRow[], events: AgentEventSummary[]): PriorityItem[] {
  const alertItems = [...alerts]
    .sort((left, right) => severityRank(right.severity) - severityRank(left.severity))
    .slice(0, 5)
    .map((alert) => ({
      id: `alert-${alert.id}`,
      severity: alert.severity,
      title: alert.title,
      meta: alert.detail ?? '활성 알림'
    }));
  const eventItems = events.slice(0, 4).map((event, index) => ({
    id: `event-${event.assetUid ?? 'unknown'}-${event.eventType ?? index}-${event.observedAt ?? index}`,
    severity: event.severity ?? 'INFO',
    title: event.message ?? event.eventType ?? 'Security event',
    meta: [event.assetUid, event.eventType, formatDate(event.observedAt)].filter(Boolean).join(' · ')
  }));
  return [...alertItems, ...eventItems].slice(0, 8);
}

function mergeTrafficRows(groups: InterfaceTraffic[][]) {
  const rows = new Map<string, InterfaceTraffic>();
  for (const group of groups) {
    for (const row of group) {
      const key = `${row.assetUid}::${row.interfaceName}`;
      if (!rows.has(key)) {
        rows.set(key, row);
      }
    }
  }
  return [...rows.values()];
}

function rankAssets(assets: AssetMetricSummary[]) {
  return {
    cpu: rankBy(assets, (asset) => asset.metrics.cpuUsagePct),
    memory: rankBy(assets, (asset) => asset.metrics.memoryUsagePct),
    disk: rankBy(assets, (asset) => asset.metrics.diskUsagePct),
    diskIo: rankBy(assets, (asset) => asset.metrics.diskIoUtilizationPct),
    network: rankBy(assets, (asset) => (asset.metrics.networkInBps ?? 0) + (asset.metrics.networkOutBps ?? 0))
  };
}

function rankBy(assets: AssetMetricSummary[], selector: (asset: AssetMetricSummary) => number | null | undefined): RankedAsset[] {
  return assets
    .map((asset) => ({ asset, value: selector(asset) ?? 0 }))
    .filter((item) => item.value > 0)
    .sort((left, right) => right.value - left.value)
    .slice(0, 5);
}

function isListeningSocket(socket: AgentSocketState) {
  return socket.direction?.toLowerCase() === 'listening' || socket.state?.toLowerCase() === 'listen';
}

function isProblemService(service: AgentServiceState) {
  const status = (service.status ?? '').toLowerCase();
  return status === 'failed' || status === 'error';
}

function socketEndpoint(socket: AgentSocketState) {
  const address = socket.localAddress ?? '0.0.0.0';
  const port = socket.localPort ?? '-';
  return `${address}:${port}`;
}

function firewallStatus(firewall: AgentFirewallState) {
  if (firewall.enabled === false) {
    return '비활성';
  }
  if (firewall.enabled === true) {
    return '활성';
  }
  return '미확인';
}

function totalTraffic(row: InterfaceTraffic) {
  return row.inBps + row.outBps;
}

function formatPercent(value: number) {
  return `${value.toFixed(1)}%`;
}

function meterWidth(value: number, max: number) {
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) {
    return 0;
  }
  return Math.max(4, Math.min(100, (value / max) * 100));
}

function severityRank(severity?: string) {
  if (severity === 'CRITICAL') {
    return 3;
  }
  if (severity === 'WARNING') {
    return 2;
  }
  return 1;
}

function severityClass(severity?: string) {
  const value = (severity ?? '').toLowerCase();
  if (value === 'critical') {
    return 'critical';
  }
  if (value === 'warning') {
    return 'warning';
  }
  return 'info';
}

function formatDate(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('ko-KR');
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
