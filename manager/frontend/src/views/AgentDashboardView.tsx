import { RefreshCw } from 'lucide-react';
import type { ReactNode } from 'react';
import { useEffect, useMemo, useState } from 'react';
import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type {
  AgentDashboard,
  AgentEventSummary,
  AgentFirewallState,
  AgentMetricSummary,
  AgentServiceState,
  AgentSocketState
} from '../lib/types';

export function AgentDashboardView() {
  const [dashboard, setDashboard] = useState<AgentDashboard>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load() {
    setLoading(true);
    setError('');
    try {
      setDashboard(await api.agentDashboard());
    } catch {
      setError('Agent 관제 정보를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const agents = dashboard.agents ?? [];
  const collectors = dashboard.collectors ?? [];
  const metrics = dashboard.resources?.metrics ?? [];
  const events = dashboard.events ?? [];
  const sockets = dashboard.states?.sockets ?? [];
  const services = dashboard.states?.services ?? [];
  const firewalls = dashboard.states?.firewalls ?? [];
  const posture = dashboard.securityPosture ?? {};
  const exposedSockets = useMemo(() => sockets.filter(isListeningSocket).slice(0, 12), [sockets]);
  const failedServices = useMemo(() => services.filter(isProblemService).slice(0, 12), [services]);
  const firewallFindings = useMemo(() => firewalls.filter((firewall) => firewall.enabled === false).slice(0, 12), [firewalls]);

  return (
    <ViewFrame
      title="보안 관제"
      actions={(
        <button className="icon-button" aria-label="Agent 정보 새로고침" onClick={() => void load()} type="button">
          <RefreshCw size={18} />
        </button>
      )}
    >
      {error && <div className="notice error">{error}</div>}
      {loading && <div className="notice">Agent telemetry를 갱신하는 중입니다.</div>}

      <MetricCards
        items={[
          ['정상 Agent', String(dashboard.heartbeat?.healthy ?? 0)],
          ['오래된 Agent', String(dashboard.heartbeat?.stale ?? 0)],
          ['노출 포트', String(posture.exposedPorts ?? exposedSockets.length)],
          ['보안 이벤트', String(posture.securityEvents ?? events.length)]
        ]}
      />

      <div className="security-dashboard-grid">
        <section className="data-panel security-panel-wide">
          <div className="panel-heading">
            <h3>Agent 상태</h3>
            <span>마지막 수집 {formatDate(dashboard.heartbeat?.lastSeenAt)}</span>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>Source</th>
                  <th>Status</th>
                  <th>Last seen</th>
                </tr>
              </thead>
              <tbody>
                {agents.map((agent) => (
                  <tr key={`${agent.sourceId ?? agent.assetUid}-${agent.assetUid}`}>
                    <td>{agent.assetUid ?? agent.sourceId ?? '-'}</td>
                    <td>{agent.sourceId ?? '-'}</td>
                    <td><span className={`status-pill ${isStale(agent.lastSeenAt) ? 'pending' : 'active'}`}>{isStale(agent.lastSeenAt) ? 'STALE' : 'HEALTHY'}</span></td>
                    <td>{formatDate(agent.lastSeenAt)}</td>
                  </tr>
                ))}
                {agents.length === 0 && <tr><td colSpan={4}>수집된 agent 없음</td></tr>}
              </tbody>
            </table>
          </div>
        </section>

        <section className="data-panel">
          <div className="panel-heading">
            <h3>Collector coverage</h3>
            <span>{collectors.length} types</span>
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr><th>Collector</th><th>Samples</th><th>Last seen</th></tr>
              </thead>
              <tbody>
                {collectors.map((collector) => (
                  <tr key={collector.name}>
                    <td>{collector.name ?? '-'}</td>
                    <td>{collector.sampleCount ?? 0}</td>
                    <td>{formatDate(collector.lastSeenAt)}</td>
                  </tr>
                ))}
                {collectors.length === 0 && <tr><td colSpan={3}>수집된 collector 없음</td></tr>}
              </tbody>
            </table>
          </div>
        </section>

        <SecurityFindingPanel
          title="공격면"
          badge={`${posture.exposedPorts ?? exposedSockets.length} open`}
          empty="수집된 listening socket 없음"
          rows={exposedSockets}
          renderRow={(socket) => (
            <li key={`${socket.assetUid}-${socket.protocol}-${socket.localAddress}-${socket.localPort}`}>
              <strong>{socket.assetUid ?? '-'}</strong>
              <span>{socketEndpoint(socket)}</span>
              <em>{socket.processName ?? '-'}</em>
            </li>
          )}
        />

        <SecurityFindingPanel
          title="서비스 이상"
          badge={`${posture.failedServices ?? failedServices.length} failed`}
          empty="실패 상태 서비스 없음"
          rows={failedServices}
          renderRow={(service) => (
            <li key={`${service.assetUid}-${service.name}`}>
              <strong>{service.name ?? '-'}</strong>
              <span>{service.assetUid ?? '-'}</span>
              <em>{service.status ?? '-'}</em>
            </li>
          )}
        />

        <SecurityFindingPanel
          title="Host firewall"
          badge={`${posture.firewallDisabled ?? firewallFindings.length} disabled`}
          empty="방화벽 비활성 항목 없음"
          rows={firewallFindings}
          renderRow={(firewall) => (
            <li key={`${firewall.assetUid}-${firewall.backend}-${firewall.profile}`}>
              <strong>{firewall.assetUid ?? '-'}</strong>
              <span>{firewall.backend ?? '-'}</span>
              <em>{firewallStatus(firewall)}</em>
            </li>
          )}
        />

        <section className="data-panel security-panel-wide">
          <div className="panel-heading">
            <h3>Resource telemetry</h3>
            <span>{metrics.length} latest metrics</span>
          </div>
          <MetricTable metrics={metrics} />
        </section>

        <section className="data-panel security-panel-wide">
          <div className="panel-heading">
            <h3>Recent security events</h3>
            <span>{events.length} events</span>
          </div>
          <EventTable events={events} />
        </section>
      </div>
    </ViewFrame>
  );
}

function SecurityFindingPanel<T>({
  title,
  badge,
  empty,
  rows,
  renderRow
}: {
  title: string;
  badge: string;
  empty: string;
  rows: T[];
  renderRow: (row: T) => ReactNode;
}) {
  return (
    <section className="data-panel security-finding-panel">
      <div className="panel-heading">
        <h3>{title}</h3>
        <span>{badge}</span>
      </div>
      {rows.length === 0 ? <p>{empty}</p> : <ul>{rows.map(renderRow)}</ul>}
    </section>
  );
}

function MetricTable({ metrics }: { metrics: AgentMetricSummary[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr><th>Agent</th><th>Metric</th><th>Value</th><th>Observed</th></tr>
        </thead>
        <tbody>
          {metrics.map((metric) => (
            <tr key={`${metric.assetUid}-${metric.metricName}`}>
              <td>{metric.assetUid ?? '-'}</td>
              <td>{metric.metricName ?? '-'}</td>
              <td>{formatValue(metric.value, metric.unit)}</td>
              <td>{formatDate(metric.observedAt)}</td>
            </tr>
          ))}
          {metrics.length === 0 && <tr><td colSpan={4}>수집된 resource metric 없음</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function EventTable({ events }: { events: AgentEventSummary[] }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr><th>Agent</th><th>Type</th><th>Severity</th><th>Message</th><th>Observed</th></tr>
        </thead>
        <tbody>
          {events.map((event) => (
            <tr key={`${event.assetUid}-${event.eventType}-${event.observedAt}-${event.message}`}>
              <td>{event.assetUid ?? '-'}</td>
              <td>{event.eventType ?? '-'}</td>
              <td>{event.severity ?? '-'}</td>
              <td className="wrap-cell">{event.message ?? event.sourceName ?? event.outcome ?? '-'}</td>
              <td>{formatDate(event.observedAt)}</td>
            </tr>
          ))}
          {events.length === 0 && <tr><td colSpan={5}>수집된 event 없음</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function isListeningSocket(socket: AgentSocketState) {
  return socket.direction === 'listening' || socket.state === 'listen';
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
    return '방화벽 비활성';
  }
  if (firewall.enabled === true) {
    return '활성';
  }
  return '상태 미확인';
}

function isStale(value?: string) {
  if (!value) {
    return true;
  }
  const seen = new Date(value).getTime();
  return Number.isNaN(seen) || Date.now() - seen > 5 * 60 * 1000;
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

function formatValue(value?: number, unit?: string) {
  if (value === undefined || value === null) {
    return '-';
  }
  if (unit === 'bytes') {
    return formatBytes(value);
  }
  const rounded = Number.isInteger(value) ? String(value) : value.toFixed(2);
  return unit ? `${rounded} ${unit}` : rounded;
}

function formatBytes(value: number) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let current = value;
  let unitIndex = 0;
  while (current >= 1024 && unitIndex < units.length - 1) {
    current /= 1024;
    unitIndex++;
  }
  return `${current.toFixed(unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}
