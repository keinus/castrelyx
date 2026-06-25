import {
  Activity,
  AlertTriangle,
  Boxes,
  CircleCheck,
  RadioTower,
  RefreshCw,
  Server,
  ShieldAlert
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { type ReactNode, useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import type {
  AgentCollectorSummary,
  AgentDashboard,
  AgentEventSummary,
  AgentFirewallState,
  AgentLogEvent,
  AgentServiceState,
  AgentSocketState,
  AlertRow,
  Asset,
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
  assets?: Asset[];
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
  icon: LucideIcon;
};

type ManagedAssetRow = Pick<
  AssetMetricSummary,
  'assetUid' | 'name' | 'assetType' | 'managementIp' | 'location' | 'status' | 'lastSeenAt' | 'stale' | 'health' | 'sources' | 'metrics' | 'security'
>;

type FleetSummary = {
  total: number;
  observed: number;
  stale: number;
  critical: number;
  warning: number;
  healthy: number;
  unknown: number;
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

export function OverviewView({ summary, alerts, assets = [] }: OverviewViewProps) {
  const [agentDashboard, setAgentDashboard] = useState<AgentDashboard>({});
  const [agentLogs, setAgentLogs] = useState<AgentLogEvent[]>([]);
  const [snmpDashboard, setSnmpDashboard] = useState<SnmpDashboard>({});
  const [trafficDashboardRows, setTrafficDashboardRows] = useState<InterfaceTraffic[]>([]);
  const [assetMetrics, setAssetMetrics] = useState<AssetMetricsOverview>(emptyAssetMetrics);
  const [lastUpdatedAt, setLastUpdatedAt] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function loadContext() {
    setLoading(true);
    setError('');

    const [agentResult, logResult, snmpResult, trafficResult, assetResult] = await Promise.all([
      loadWithTimeout(api.agentDashboard(), {}),
      loadWithTimeout(api.agentLogs('1h', 'ALL', 8), []),
      loadWithTimeout(api.snmpDashboard(), {}),
      loadWithTimeout(api.trafficInterfaces('1h'), []),
      loadWithTimeout(api.assetMetrics('1h'), emptyAssetMetrics)
    ]);

    setAgentDashboard(agentResult.value);
    setAgentLogs(logResult.value);
    setSnmpDashboard(snmpResult.value);
    setTrafficDashboardRows(trafficResult.value);
    setAssetMetrics(assetResult.value);

    if ([agentResult, logResult, snmpResult, trafficResult, assetResult].some((result) => !result.ok)) {
      setError('일부 관제 정보를 불러오지 못했습니다. 수집된 기본 신호로 개요를 표시합니다.');
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
    () => [...trafficRows].sort((left, right) => totalTraffic(right) - totalTraffic(left)).slice(0, 6),
    [trafficRows]
  );
  const maxTraffic = useMemo(() => Math.max(1, ...topTraffic.map(totalTraffic)), [topTraffic]);
  const noisyInterfaces = useMemo(
    () => trafficRows.filter((row) => row.errors + row.discards > 0).slice(0, 4),
    [trafficRows]
  );
  const managedAssets = useMemo(
    () => buildManagedAssets(assetMetrics.assets, assets),
    [assetMetrics.assets, assets]
  );
  const visibleManagedAssets = useMemo(
    () => [...managedAssets].sort(sortManagedAsset).slice(0, 12),
    [managedAssets]
  );
  const fleetSummary = useMemo(
    () => summarizeFleet(managedAssets, assetMetrics.summary, summary.activeAssets),
    [assetMetrics.summary, managedAssets, summary.activeAssets]
  );
  const trafficTotal = useMemo(
    () => summarizeTraffic(assetMetrics.summary, trafficRows),
    [assetMetrics.summary, trafficRows]
  );
  const rankedAssets = useMemo(() => rankAssets(assetMetrics.assets), [assetMetrics.assets]);

  const collectors = agentDashboard.collectors ?? [];
  const sockets = agentDashboard.states?.sockets ?? [];
  const services = agentDashboard.states?.services ?? [];
  const firewalls = agentDashboard.states?.firewalls ?? [];
  const posture = agentDashboard.securityPosture ?? {};
  const exposedSockets = useMemo(() => sockets.filter(isListeningSocket).slice(0, 4), [sockets]);
  const failedServices = useMemo(() => services.filter(isProblemService).slice(0, 4), [services]);
  const firewallFindings = useMemo(
    () => firewalls.filter((firewall) => firewall.enabled === false).slice(0, 4),
    [firewalls]
  );
  const openPortCount = posture.exposedPorts ?? exposedSockets.length;
  const failedServiceCount = posture.failedServices ?? failedServices.length;
  const firewallDisabledCount = posture.firewallDisabled ?? firewallFindings.length;
  const priorityItems = useMemo(
    () => buildPriorityItems(activeAlerts, agentLogs),
    [activeAlerts, agentLogs]
  );
  const recentEvents = agentLogs.slice(0, 8);

  const kpis: KpiItem[] = [
    {
      label: '관리 장비',
      value: String(fleetSummary.total),
      meta: `관측 ${fleetSummary.observed} · 미수집 ${fleetSummary.stale}`,
      tone: fleetSummary.critical > 0 ? 'critical' : fleetSummary.warning > 0 || fleetSummary.stale > 0 ? 'warning' : 'good',
      icon: Boxes
    },
    {
      label: '장애/경고',
      value: `${activeCriticalCount}/${activeWarningCount}`,
      meta: 'critical / warning',
      tone: activeCriticalCount > 0 ? 'critical' : activeWarningCount > 0 ? 'warning' : 'good',
      icon: AlertTriangle
    },
    {
      label: 'Agent',
      value: `${heartbeat.healthy ?? 0}/${heartbeat.stale ?? 0}`,
      meta: 'healthy / stale',
      tone: (heartbeat.stale ?? 0) > 0 ? 'warning' : 'good',
      icon: Server
    },
    {
      label: 'SNMP Poll',
      value: `${snmpPolls.success ?? 0}/${snmpPolls.failure ?? 0}`,
      meta: 'success / failure',
      tone: (snmpPolls.failure ?? 0) > 0 ? 'warning' : 'good',
      icon: RadioTower
    },
    {
      label: 'Traffic',
      value: formatBps(trafficTotal.inBps + trafficTotal.outBps),
      meta: `In ${formatBps(trafficTotal.inBps)} · Out ${formatBps(trafficTotal.outBps)}`,
      tone: 'neutral',
      icon: Activity
    }
  ];

  return (
    <section className="view-frame overview-nms">
      <header className="overview-header">
        <div>
          <h2>개요</h2>
          <p>현재 수집된 자산, 트래픽, Agent 로그를 기준으로 관리 장비 상태를 우선 표시합니다.</p>
        </div>
        <div className="overview-header-actions">
          <div className={`overview-live-state ${loading ? 'pending' : error ? 'degraded' : 'active'}`}>
            <span aria-hidden="true" />
            <div>
              <strong>{loading ? '갱신 중' : error ? '일부 지연' : '수집 정상'}</strong>
              <small>마지막 갱신 {formatDate(lastUpdatedAt)}</small>
            </div>
          </div>
          <button className="icon-button overview-refresh" aria-label="새로고침" onClick={() => void loadContext()} type="button">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      {error && <div className="notice overview-notice warning">{error}</div>}
      {loading && <div className="notice overview-notice">관제 상세 정보를 갱신하는 중입니다.</div>}

      <KpiStrip items={kpis} />

      <div className="overview-main-grid">
        <Panel
          title="관리 장비"
          meta={`${visibleManagedAssets.length} / ${fleetSummary.total} devices`}
          className="overview-panel-devices"
        >
          <FleetSummaryBand summary={fleetSummary} />
          <ManagedDeviceTable assets={visibleManagedAssets} />
        </Panel>

        <Panel title="상위 트래픽" meta={`${topTraffic.length} interfaces`}>
          <TrafficMeterList rows={topTraffic} maxTraffic={maxTraffic} />
          <NoisyInterfaceList rows={noisyInterfaces} />
        </Panel>

        <Panel title="대응 큐" meta={`${priorityItems.length} active`}>
          <PriorityList items={priorityItems} />
        </Panel>

        <Panel title="Agent 수집" meta={formatDate(heartbeat.lastSeenAt)}>
          <AgentHealthStrip healthy={heartbeat.healthy ?? 0} stale={heartbeat.stale ?? 0} />
          <CollectorList collectors={collectors.slice(0, 6)} />
        </Panel>

        <Panel title="보안/상태 신호" meta={`${openPortCount} ports · ${failedServiceCount} failed`}>
          <SecuritySignalGrid
            openPorts={openPortCount}
            failedServices={failedServiceCount}
            firewallDisabled={firewallDisabledCount}
          />
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
        </Panel>

        <Panel title="자산 리소스 Top" meta={`${assetMetrics.assets.length} assets`} className="overview-panel-resource">
          <div className="overview-resource-grid">
            <AssetTopList title="CPU" rows={rankedAssets.cpu} format={formatPercent} />
            <AssetTopList title="Memory" rows={rankedAssets.memory} format={formatPercent} />
            <AssetTopList title="Disk I/O" rows={rankedAssets.diskIo} format={formatPercent} />
            <AssetTopList title="Network" rows={rankedAssets.network} format={(value) => formatBps(value)} />
          </div>
        </Panel>

        <Panel title="최근 Agent 로그" meta={`${recentEvents.length} events`} className="overview-panel-logs">
          <EventList events={recentEvents} />
        </Panel>
      </div>
    </section>
  );
}

function Panel({
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
    <section className={`overview-panel ${className}`}>
      <div className="overview-panel-heading">
        <h3>{title}</h3>
        <span>{meta}</span>
      </div>
      {children}
    </section>
  );
}

function KpiStrip({ items }: { items: KpiItem[] }) {
  return (
    <div className="overview-kpi-strip">
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <section className={`overview-kpi ${item.tone}`} key={item.label}>
            <Icon size={18} aria-hidden="true" />
            <span>{item.label}</span>
            <strong>{item.value}</strong>
            <small>{item.meta}</small>
          </section>
        );
      })}
    </div>
  );
}

function FleetSummaryBand({ summary }: { summary: FleetSummary }) {
  return (
    <div className="fleet-summary-band">
      <SummaryChip label="정상" value={summary.healthy} tone="good" />
      <SummaryChip label="주의" value={summary.warning} tone="warning" />
      <SummaryChip label="위험" value={summary.critical} tone="critical" />
      <SummaryChip label="미확인" value={summary.unknown} tone="neutral" />
    </div>
  );
}

function SummaryChip({ label, value, tone }: { label: string; value: number; tone: KpiTone }) {
  return (
    <div className={`summary-chip ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function ManagedDeviceTable({ assets }: { assets: ManagedAssetRow[] }) {
  return (
    <div className="managed-device-table-wrap">
      <table className="managed-device-table">
        <thead>
          <tr>
            <th>장비</th>
            <th>관리 IP</th>
            <th>상태</th>
            <th>수집 신호</th>
            <th>사용률</th>
          </tr>
        </thead>
        <tbody>
          {assets.length === 0 ? (
            <tr>
              <td colSpan={5}>
                <EmptyState title="관리 장비 대기" detail="등록되거나 관측된 자산 정보가 아직 없습니다." />
              </td>
            </tr>
          ) : assets.map((asset) => (
            <tr className={`managed-device-row ${asset.health}`} key={asset.assetUid}>
              <td data-label="장비">
                <div className="device-identity">
                  <span className={`health-dot ${asset.health}`} aria-hidden="true" />
                  <div>
                    <strong>{asset.name || asset.assetUid}</strong>
                    <span>{asset.assetUid} · {asset.assetType || '-'}</span>
                  </div>
                </div>
              </td>
              <td data-label="관리 IP">
                <div className="device-meta">
                  <strong>{asset.managementIp ?? '-'}</strong>
                  <span>{asset.location ?? asset.status ?? '-'}</span>
                </div>
              </td>
              <td data-label="상태">
                <div className="device-state-cell">
                  <HealthBadge health={asset.health} stale={asset.stale} />
                  <span>{formatDate(asset.lastSeenAt ?? undefined)}</span>
                </div>
              </td>
              <td data-label="수집 신호"><SourceBadges sources={asset.sources} /></td>
              <td data-label="사용률"><UtilizationCell asset={asset} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function HealthBadge({ health, stale }: { health: AssetMetricSummary['health']; stale?: boolean }) {
  const label = stale ? '미수집' : healthLabel(health);
  const tone = stale ? 'warning' : health === 'critical' ? 'critical' : health === 'warning' ? 'warning' : health === 'healthy' ? 'good' : 'neutral';
  return <span className={`overview-status-badge ${tone}`}>{label}</span>;
}

function SourceBadges({ sources }: { sources: AssetMetricSummary['sources'] }) {
  const entries: Array<[string, boolean | undefined]> = [
    ['Agent', sources.agent],
    ['SNMP', sources.snmp],
    ['Traffic', sources.traffic],
    ['Disk', sources.diskIo],
    ['Signals', sources.security]
  ];
  const active = entries.filter(([, value]) => value);
  if (active.length === 0) {
    return <span className="overview-muted">-</span>;
  }
  return (
    <div className="source-badge-row">
      {active.map(([label]) => <span key={label}>{label}</span>)}
    </div>
  );
}

function UtilizationCell({ asset }: { asset: ManagedAssetRow }) {
  const values = [
    ['CPU', asset.metrics.cpuUsagePct],
    ['Mem', asset.metrics.memoryUsagePct],
    ['Disk', asset.metrics.diskUsagePct]
  ] as const;
  if (values.every(([, value]) => value == null)) {
    return <span className="overview-muted">metric 대기</span>;
  }
  return (
    <div className="utilization-stack">
      {values.map(([label, value]) => (
        <MiniMetricBar key={label} label={label} value={value} />
      ))}
    </div>
  );
}

function MiniMetricBar({ label, value }: { label: string; value?: number | null }) {
  const normalized = value == null ? 0 : Math.max(0, Math.min(100, value));
  return (
    <div className="mini-metric-bar">
      <span>{label}</span>
      <div><i style={{ width: `${normalized}%` }} /></div>
      <em>{value == null ? '-' : formatPercent(value)}</em>
    </div>
  );
}

function TrafficMeterList({ rows, maxTraffic }: { rows: InterfaceTraffic[]; maxTraffic: number }) {
  if (rows.length === 0) {
    return <EmptyState title="트래픽 신호 대기" detail="수집된 인터페이스 트래픽이 아직 없습니다." />;
  }
  return (
    <ul className="overview-traffic-list">
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
    <div className={`overview-traffic-meter ${tone}`}>
      <span>{label}</span>
      <div><i style={{ width: `${meterWidth(value, max)}%` }} /></div>
      <em>{formatBps(value)}</em>
    </div>
  );
}

function NoisyInterfaceList({ rows }: { rows: InterfaceTraffic[] }) {
  if (rows.length === 0) {
    return (
      <div className="overview-quiet-strip">
        <CircleCheck size={15} aria-hidden="true" />
        <strong>에러/드롭 없음</strong>
        <span>최근 수집 기준 인터페이스 오류 신호가 없습니다.</span>
      </div>
    );
  }
  return (
    <div className="overview-noise-list">
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
    return <EmptyState title="대응 대기 없음" detail="활성 Critical/Warning 알림과 최근 Agent 로그가 없습니다." />;
  }
  return (
    <ul className="overview-priority-list">
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

function AgentHealthStrip({ healthy, stale }: { healthy: number; stale: number }) {
  return (
    <div className="agent-health-strip">
      <div>
        <Server size={16} aria-hidden="true" />
        <span>Healthy</span>
        <strong>{healthy}</strong>
      </div>
      <div className={stale > 0 ? 'warning' : ''}>
        <AlertTriangle size={16} aria-hidden="true" />
        <span>Stale</span>
        <strong>{stale}</strong>
      </div>
    </div>
  );
}

function CollectorList({ collectors }: { collectors: AgentCollectorSummary[] }) {
  if (collectors.length === 0) {
    return <EmptyState title="Collector 대기" detail="아직 표시할 collector coverage가 없습니다." />;
  }
  return (
    <ul className="overview-collector-list">
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

function SecuritySignalGrid({
  openPorts,
  failedServices,
  firewallDisabled
}: {
  openPorts: number;
  failedServices: number;
  firewallDisabled: number;
}) {
  return (
    <div className="security-signal-grid">
      <SignalItem icon={ShieldAlert} label="노출 포트" value={openPorts} tone={openPorts > 0 ? 'warning' : 'good'} />
      <SignalItem icon={Server} label="서비스 이상" value={failedServices} tone={failedServices > 0 ? 'critical' : 'good'} />
      <SignalItem icon={AlertTriangle} label="방화벽 비활성" value={firewallDisabled} tone={firewallDisabled > 0 ? 'warning' : 'good'} />
    </div>
  );
}

function SignalItem({ icon: Icon, label, value, tone }: { icon: LucideIcon; label: string; value: number; tone: KpiTone }) {
  return (
    <div className={`signal-item ${tone}`}>
      <Icon size={16} aria-hidden="true" />
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
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
    <ul className="overview-finding-list">
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

function AssetTopList({ title, rows, format }: { title: string; rows: RankedAsset[]; format: (value: number) => string }) {
  const max = Math.max(1, ...rows.map((row) => row.value));
  return (
    <section className="overview-resource-list">
      <div>
        <strong>{title}</strong>
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

function EventList({ events }: { events: AgentEventSummary[] }) {
  if (events.length === 0) {
    return <EmptyState title="최근 Agent 로그 없음" detail="수집된 로그 이벤트가 아직 없습니다." />;
  }
  return (
    <ul className="overview-event-list">
      {events.map((event, index) => (
        <li key={`${event.assetUid}-${event.eventType}-${event.observedAt}-${event.message}-${index}`}>
          <span className={`badge ${severityClass(event.severity)}`}>{event.severity ?? 'INFO'}</span>
          <div>
            <strong>{event.message ?? event.sourceName ?? event.outcome ?? event.eventType ?? 'Agent event'}</strong>
            <span>{[event.assetUid, event.eventType, formatDate(event.observedAt ?? event.eventTime)].filter(Boolean).join(' · ')}</span>
          </div>
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
    title: event.message ?? event.eventType ?? 'Agent event',
    meta: [event.assetUid, event.eventType, formatDate(event.observedAt ?? event.eventTime)].filter(Boolean).join(' · ')
  }));
  return [...alertItems, ...eventItems].slice(0, 8);
}

function buildManagedAssets(metricAssets: AssetMetricSummary[], registeredAssets: Asset[]): ManagedAssetRow[] {
  const rows = new Map<string, ManagedAssetRow>();
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
        metrics: {}
      });
    }
  }
  return [...rows.values()];
}

function summarizeFleet(assets: ManagedAssetRow[], summary: AssetMetricsOverview['summary'], fallbackTotal: number): FleetSummary {
  const total = summary.totalAssets || assets.length || fallbackTotal;
  const observed = summary.observedAssets || assets.filter((asset) => asset.sources.observed || asset.sources.agent || asset.sources.traffic || asset.sources.snmp).length;
  const stale = summary.staleAssets || assets.filter((asset) => asset.stale).length;
  const critical = summary.criticalAssets || assets.filter((asset) => asset.health === 'critical').length;
  const warning = summary.warningAssets || assets.filter((asset) => asset.health === 'warning').length;
  const healthy = assets.filter((asset) => asset.health === 'healthy').length;
  const unknown = Math.max(0, total - critical - warning - healthy);
  return { total, observed, stale, critical, warning, healthy, unknown };
}

function summarizeTraffic(summary: AssetMetricsOverview['summary'], rows: InterfaceTraffic[]) {
  const inBps = summary.totalNetworkInBps ?? rows.reduce((total, row) => total + row.inBps, 0);
  const outBps = summary.totalNetworkOutBps ?? rows.reduce((total, row) => total + row.outBps, 0);
  return { inBps, outBps };
}

function sortManagedAsset(left: ManagedAssetRow, right: ManagedAssetRow) {
  const leftRank = healthRank(left.health) + (left.stale ? 2 : 0);
  const rightRank = healthRank(right.health) + (right.stale ? 2 : 0);
  if (leftRank !== rightRank) {
    return rightRank - leftRank;
  }
  return assetTraffic(right) - assetTraffic(left);
}

function healthRank(health?: AssetMetricSummary['health']) {
  if (health === 'critical') {
    return 5;
  }
  if (health === 'warning') {
    return 4;
  }
  if (health === 'unknown') {
    return 2;
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
    diskIo: rankBy(assets, (asset) => asset.metrics.diskIoUtilizationPct),
    network: rankBy(assets, assetTraffic)
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

function assetTraffic(asset: Pick<AssetMetricSummary, 'metrics'>) {
  return (asset.metrics.networkInBps ?? 0) + (asset.metrics.networkOutBps ?? 0);
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
