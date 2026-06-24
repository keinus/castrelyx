import { FileText, RefreshCw, Search, Server } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { AgentLogEvent } from '../lib/types';

const rangeOptions = [
  ['15m', '15 min'],
  ['30m', '30 min'],
  ['1h', '1 hour'],
  ['6h', '6 hours'],
  ['24h', '24 hours']
] as const;

const severityOptions = [
  ['ALL', 'All severities'],
  ['INFO', 'Info'],
  ['WARNING', 'Warning'],
  ['ERROR', 'Error'],
  ['CRITICAL', 'Critical']
] as const;

export function AgentLogsView() {
  const [range, setRange] = useState('1h');
  const [severity, setSeverity] = useState('ALL');
  const [logs, setLogs] = useState<AgentLogEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [selectedAsset, setSelectedAsset] = useState('ALL');

  async function load(selectedRange = range, selectedSeverity = severity) {
    setLoading(true);
    setError('');
    try {
      setLogs(await api.agentLogs(selectedRange, selectedSeverity, 300));
    } catch {
      setLogs([]);
      setError('Agent logs를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(range, severity);
  }, [range, severity]);

  const assetOptions = useMemo(
    () => [...new Set(logs.map((log) => log.assetUid).filter((asset): asset is string => Boolean(asset)))].sort(),
    [logs]
  );
  const visibleLogs = useMemo(
    () => filterLogs(logs, selectedAsset, query),
    [logs, selectedAsset, query]
  );
  const summary = useMemo(() => summarizeLogs(visibleLogs), [visibleLogs]);

  return (
    <ViewFrame
      title="Agent Logs"
      actions={(
        <>
          <select aria-label="Log range" value={range} onChange={(event) => setRange(event.target.value)}>
            {rangeOptions.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
          <select aria-label="Log severity" value={severity} onChange={(event) => setSeverity(event.target.value)}>
            {severityOptions.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
          <button className="icon-button" aria-label="Refresh agent logs" onClick={() => void load()} type="button">
            <RefreshCw size={18} />
          </button>
        </>
      )}
    >
      {error && <div className="notice error">{error}</div>}
      {loading && <div className="notice">Agent logs를 갱신하는 중입니다.</div>}

      <div className="agent-log-control-bar">
        <label className="traffic-filter-field">
          <Search size={16} aria-hidden="true" />
          <input
            aria-label="Filter agent logs"
            placeholder="Filter logs"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <label className="traffic-filter-field">
          <Server size={16} aria-hidden="true" />
          <select aria-label="Log asset" value={selectedAsset} onChange={(event) => setSelectedAsset(event.target.value)}>
            <option value="ALL">All assets</option>
            {assetOptions.map((assetUid) => (
              <option value={assetUid} key={assetUid}>{assetUid}</option>
            ))}
          </select>
        </label>
      </div>

      <MetricCards
        items={[
          ['Visible logs', String(visibleLogs.length)],
          ['Warning+', String(summary.warningOrHigher)],
          ['Auth', String(summary.auth)],
          ['Assets', String(summary.assets)]
        ]}
      />

      <section className="data-panel">
        <div className="panel-heading">
          <h3>Collected logs</h3>
          <span>{logs.length} loaded</span>
        </div>
        <AgentLogTable logs={visibleLogs} loading={loading} />
      </section>
    </ViewFrame>
  );
}

function AgentLogTable({ logs, loading }: { logs: AgentLogEvent[]; loading: boolean }) {
  return (
    <div className="table-scroll">
      <table className="agent-log-table">
        <thead>
          <tr>
            <th>Observed</th>
            <th>Agent</th>
            <th>Severity</th>
            <th>Type</th>
            <th>Source</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={`${log.assetUid}-${log.eventType}-${log.observedAt}-${log.dedupKey ?? log.message}`}>
              <td>{formatDate(log.observedAt)}</td>
              <td>{log.assetUid ?? log.sourceId ?? '-'}</td>
              <td><span className={`badge ${severityClass(log.severity)}`}>{log.severity ?? 'INFO'}</span></td>
              <td>
                <div className="agent-log-meta">
                  <strong>{log.eventType ?? '-'}</strong>
                  <span>{log.eventCategory ?? log.outcome ?? '-'}</span>
                </div>
              </td>
              <td>
                <div className="agent-log-meta">
                  <strong>{log.sourceName ?? log.channel ?? '-'}</strong>
                  <span>{log.program ?? log.provider ?? log.platform ?? '-'}</span>
                </div>
              </td>
              <td className="agent-log-message">
                <div>
                  <FileText size={15} aria-hidden="true" />
                  <span>{log.message ?? '-'}</span>
                </div>
                {(log.actor || log.action) && <em>{[log.actor, log.action].filter(Boolean).join(' / ')}</em>}
              </td>
            </tr>
          ))}
          {!loading && logs.length === 0 && (
            <tr><td colSpan={6}>수집된 agent log 없음</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function filterLogs(logs: AgentLogEvent[], selectedAsset: string, query: string) {
  const normalizedQuery = query.trim().toLowerCase();
  return logs.filter((log) => {
    const assetMatches = selectedAsset === 'ALL' || log.assetUid === selectedAsset;
    const queryMatches = !normalizedQuery || [
      log.assetUid,
      log.sourceId,
      log.eventType,
      log.eventCategory,
      log.severity,
      log.sourceName,
      log.channel,
      log.program,
      log.provider,
      log.actor,
      log.action,
      log.outcome,
      log.message
    ].some((value) => value?.toLowerCase().includes(normalizedQuery));
    return assetMatches && queryMatches;
  });
}

function summarizeLogs(logs: AgentLogEvent[]) {
  return {
    warningOrHigher: logs.filter((log) => ['WARNING', 'ERROR', 'CRITICAL'].includes((log.severity ?? '').toUpperCase())).length,
    auth: logs.filter((log) => (log.eventCategory ?? log.eventType ?? '').toLowerCase().includes('auth')).length,
    assets: new Set(logs.map((log) => log.assetUid).filter(Boolean)).size
  };
}

function severityClass(severity?: string) {
  switch ((severity ?? '').toUpperCase()) {
    case 'CRITICAL':
    case 'ERROR':
      return 'critical';
    case 'WARNING':
      return 'warning';
    default:
      return 'info';
  }
}

function formatDate(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
