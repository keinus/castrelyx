import { FileText, RefreshCw, Search, Server } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { AgentLogEvent, Asset } from '../lib/types';
import { useRemoteData } from '../lib/useRemoteData';
import { ViewFeedback } from '../components/ViewFeedback';

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

export function AgentLogsView({ assets = [] }: { assets?: Asset[] }) {
  const [range, setRange] = useState('1h');
  const [severity, setSeverity] = useState('ALL');
  const [query, setQuery] = useState('');
  const [serverQuery, setServerQuery] = useState('');
  const [family, setFamily] = useState('ALL');
  const [selectedAsset, setSelectedAsset] = useState('ALL');

  useEffect(() => {
    const timer = window.setTimeout(() => setServerQuery(query.trim()), 300);
    return () => window.clearTimeout(timer);
  }, [query]);

  const fetchLogs = useCallback(() => api.agentLogs(range, severity, 300, selectedAsset, family, serverQuery), [range, severity, selectedAsset, family, serverQuery]);
  const { data, loading, error, reload: load } = useRemoteData(fetchLogs, 'Agent logs를 불러오지 못했습니다.');
  const logs = data ?? [];

  const assetOptions = useMemo(
    () => [...new Set([...assets.map((asset) => asset.assetUid), ...logs.map((log) => log.assetUid), ...(selectedAsset === 'ALL' ? [] : [selectedAsset])].filter((asset): asset is string => Boolean(asset)))].sort(),
    [assets, logs, selectedAsset]
  );
  const visibleLogs = logs;
  const summary = useMemo(() => summarizeLogs(visibleLogs), [visibleLogs]);

  return (
    <ViewFrame
      title="Agent Logs"
      subtitle="Agent의 호스트 로그와 Security Router의 Zeek 로그를 기간·심각도·자산별로 조회합니다."
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
          <button className="icon-button" aria-label="Refresh agent logs" disabled={loading || query.trim() !== serverQuery} onClick={() => void load()} type="button">
            <RefreshCw size={18} />
          </button>
        </>
      )}
    >
      <ViewFeedback loading={loading} error={error} hasData={!!data} />

      <div className="agent-log-control-bar">
        <label className="traffic-filter-field">
          <Search size={16} aria-hidden="true" />
          <input
            aria-label="Filter agent logs"
            placeholder="Search messages, IPs, event types…"
            maxLength={256}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <label className="traffic-filter-field">
          <FileText size={16} aria-hidden="true" />
          <select aria-label="Log family" value={family} onChange={(event) => setFamily(event.target.value)}>
            <option value="ALL">All logs</option>
            <option value="host">Host logs</option>
            <option value="zeek">Zeek</option>
          </select>
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
          ['Visible logs', data ? String(visibleLogs.length) : '—'],
          ['Warning+', data ? String(summary.warningOrHigher) : '—'],
          ['Auth', data ? String(summary.auth) : '—'],
          ['Assets', data ? String(summary.assets) : '—']
        ]}
      />

      <section className="data-panel">
        <div className="panel-heading">
          <h3>Collected logs</h3>
          <span>{data ? `${logs.length} loaded · 최대 300건` : '미확인'}</span>
        </div>
        <AgentLogTable logs={visibleLogs} loading={loading} />
        <p className="cc-help">선택한 기간의 저장된 로그 전체에서 검색한 뒤 최근 300건을 표시합니다. Zeek 필터로 네트워크 분석 로그만 조회할 수 있습니다.</p>
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
            <th>Asset</th>
            <th>Severity</th>
            <th>Type</th>
            <th>Source</th>
            <th>Message</th>
          </tr>
        </thead>
        <tbody>
          {logs.map((log) => (
            <tr key={`${log.assetUid}-${log.sourceType}-${log.sourceId}-${log.logKey ?? `${log.eventType}-${log.observedAt}-${log.dedupKey ?? log.message}`}`}>
              <td>{formatDate(log.observedAt)}</td>
              <td>{log.assetUid ?? log.sourceId ?? '-'}</td>
              <td><span className={`badge ${severityClass(log.severity)}`}>{log.severity ?? '미제공'}</span></td>
              <td>
                <div className="agent-log-meta">
                  <strong>{log.eventType ?? '-'}</strong>
                  <span>{log.logFamily ?? log.eventCategory ?? log.outcome ?? '-'}</span>
                </div>
              </td>
              <td>
                <div className="agent-log-meta">
                  <strong>{log.sourceName ?? log.channel ?? '-'}</strong>
                  <span>{log.sourceType === 'SECURITY_ROUTER' ? `Security Router · ${log.sourceId ?? '-'}` : log.program ?? log.provider ?? log.platform ?? '-'}</span>
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
            <tr><td colSpan={6}>조건에 맞는 로그가 없습니다.</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
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
