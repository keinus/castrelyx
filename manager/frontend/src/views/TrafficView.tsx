import {
  Activity,
  AlertTriangle,
  ArrowDownToLine,
  ArrowUpFromLine,
  RefreshCw,
  Search,
  Server,
  SlidersHorizontal
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { InterfaceTraffic } from '../lib/types';
import { formatBps } from '../lib/uiModel';

const rangeOptions = [
  ['15m', '15 min'],
  ['30m', '30 min'],
  ['1h', '1 hour'],
  ['6h', '6 hours'],
  ['24h', '24 hours']
] as const;

type AssetTrafficSummary = {
  assetUid: string;
  interfaceCount: number;
  totalInBps: number;
  totalOutBps: number;
  totalBps: number;
  peakUtilizationPct: number;
  errors: number;
  discards: number;
  exceedCount: number;
  busiestInterface?: InterfaceTraffic;
  status: string;
};

export function TrafficView() {
  const [range, setRange] = useState('1h');
  const [rows, setRows] = useState<InterfaceTraffic[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [selectedAsset, setSelectedAsset] = useState('ALL');
  const [exceedThresholdMbps, setExceedThresholdMbps] = useState(10);

  async function load(selectedRange = range) {
    setLoading(true);
    setError('');
    try {
      setRows(await api.trafficInterfaces(selectedRange));
    } catch {
      setRows([]);
      setError('Unable to load traffic data.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(range);
  }, [range]);

  const thresholdBps = Math.max(0, exceedThresholdMbps) * 1_000_000;
  const assetSummaries = useMemo(() => buildAssetSummaries(rows, thresholdBps), [rows, thresholdBps]);
  const assetOptions = useMemo(() => assetSummaries.map((asset) => asset.assetUid), [assetSummaries]);

  const visibleRows = useMemo(
    () => filterRows(rows, selectedAsset, query).sort((left, right) => totalTraffic(right) - totalTraffic(left)),
    [rows, selectedAsset, query]
  );
  const visibleAssets = useMemo(
    () => filterAssetSummaries(assetSummaries, selectedAsset, query),
    [assetSummaries, selectedAsset, query]
  );
  const exceedRows = useMemo(
    () => visibleRows.filter((row) => exceedsTraffic(row, thresholdBps)).slice(0, 8),
    [visibleRows, thresholdBps]
  );

  const summary = useMemo(() => {
    const totalIn = visibleRows.reduce((sum, row) => sum + row.inBps, 0);
    const totalOut = visibleRows.reduce((sum, row) => sum + row.outBps, 0);
    const exceedCount = visibleRows.filter((row) => exceedsTraffic(row, thresholdBps)).length;
    return {
      totalIn,
      totalOut,
      total: totalIn + totalOut,
      exceedCount
    };
  }, [visibleRows, thresholdBps]);

  const maxTraffic = Math.max(1, thresholdBps, ...visibleRows.map(totalTraffic));

  return (
    <ViewFrame
      title="Traffic"
      actions={(
        <>
          <select aria-label="Range" value={range} onChange={(event) => setRange(event.target.value)}>
            {rangeOptions.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
          <button className="icon-button" aria-label="Refresh traffic" onClick={() => void load()} type="button">
            <RefreshCw size={18} />
          </button>
        </>
      )}
    >
      {error && <div className="notice error">{error}</div>}
      {loading && <div className="notice">Refreshing traffic data.</div>}

      <div className="traffic-control-bar">
        <label className="traffic-filter-field">
          <Search size={16} aria-hidden="true" />
          <input
            aria-label="Filter asset or interface"
            placeholder="Filter asset/interface"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>
        <label className="traffic-filter-field">
          <Server size={16} aria-hidden="true" />
          <select aria-label="Asset" value={selectedAsset} onChange={(event) => setSelectedAsset(event.target.value)}>
            <option value="ALL">All assets</option>
            {assetOptions.map((assetUid) => (
              <option value={assetUid} key={assetUid}>{assetUid}</option>
            ))}
          </select>
        </label>
        <label className="traffic-filter-field">
          <SlidersHorizontal size={16} aria-hidden="true" />
          <input
            aria-label="Exceed threshold Mbps"
            min={0}
            step={1}
            type="number"
            value={exceedThresholdMbps}
            onChange={(event) => setExceedThresholdMbps(Number(event.target.value))}
          />
        </label>
      </div>

      <MetricCards
        items={[
          ['Total', formatBps(summary.total)],
          ['Inbound', formatBps(summary.totalIn)],
          ['Outbound', formatBps(summary.totalOut)],
          ['Exceed', String(summary.exceedCount)]
        ]}
      />

      <div className="traffic-board">
        <AssetTrafficPanel assets={visibleAssets} selectedAsset={selectedAsset} onSelect={setSelectedAsset} />
        <InterfaceFlowPanel rows={visibleRows} loading={loading} maxTraffic={maxTraffic} thresholdBps={thresholdBps} />
      </div>

      <TrafficExceedPanel rows={exceedRows} thresholdBps={thresholdBps} maxTraffic={maxTraffic} />
    </ViewFrame>
  );
}

function AssetTrafficPanel({
  assets,
  selectedAsset,
  onSelect
}: {
  assets: AssetTrafficSummary[];
  selectedAsset: string;
  onSelect: (assetUid: string) => void;
}) {
  return (
    <section className="data-panel traffic-assets-panel">
      <div className="panel-heading">
        <h3>Assets</h3>
        <span>{assets.length} assets</span>
      </div>
      <div className="table-scroll">
        <table className="traffic-asset-table">
          <thead>
            <tr>
              <th>Asset</th>
              <th>Interfaces</th>
              <th>Total</th>
              <th>Exceed</th>
            </tr>
          </thead>
          <tbody>
            {assets.map((asset) => (
              <tr key={asset.assetUid} className={selectedAsset === asset.assetUid ? 'selected' : ''}>
                <td>
                  <button className="traffic-asset-button" type="button" onClick={() => onSelect(asset.assetUid)}>
                    <Server size={16} aria-hidden="true" />
                    <span>{asset.assetUid}</span>
                  </button>
                </td>
                <td>{asset.interfaceCount}</td>
                <td>{formatBps(asset.totalBps)}</td>
                <td><ExceedBadge count={asset.exceedCount} /></td>
              </tr>
            ))}
            {assets.length === 0 && (
              <tr><td colSpan={4}>No asset traffic.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function InterfaceFlowPanel({
  rows,
  loading,
  maxTraffic,
  thresholdBps
}: {
  rows: InterfaceTraffic[];
  loading: boolean;
  maxTraffic: number;
  thresholdBps: number;
}) {
  return (
    <section className="data-panel traffic-flow-panel">
      <div className="panel-heading">
        <h3>Interface flows</h3>
        <span>{rows.length} interfaces</span>
      </div>
      <div className="table-scroll">
        <table className="traffic-flow-table">
          <thead>
            <tr>
              <th>Asset</th>
              <th>Interface</th>
              <th>Flow</th>
              <th>Rate</th>
              <th>Util</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={`${row.assetUid}-${row.interfaceName}`}>
                <td>{row.assetUid}</td>
                <td>{row.interfaceName}</td>
                <td className="traffic-flow-cell">
                  <TrafficFlowBars row={row} maxTraffic={maxTraffic} />
                </td>
                <td>
                  <div className="traffic-rate-stack">
                    <strong>{formatBps(totalTraffic(row))}</strong>
                    {exceedsTraffic(row, thresholdBps) && <span className="traffic-over">over</span>}
                  </div>
                </td>
                <td>{formatPct(row.utilizationPct)}</td>
                <td><span className={`status-pill ${statusClass(row.status, row)}`}>{statusLabel(row.status, row)}</span></td>
              </tr>
            ))}
            {!loading && rows.length === 0 && (
              <tr><td colSpan={6}>No interface traffic.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function TrafficExceedPanel({
  rows,
  thresholdBps,
  maxTraffic
}: {
  rows: InterfaceTraffic[];
  thresholdBps: number;
  maxTraffic: number;
}) {
  return (
    <section className="data-panel traffic-exceed-panel">
      <div className="panel-heading">
        <h3>Traffic exceed</h3>
        <span>{thresholdBps > 0 ? `${formatBps(thresholdBps)} threshold` : 'utilization only'}</span>
      </div>
      {rows.length > 0 ? (
        <ul className="traffic-exceed-list">
          {rows.map((row) => (
            <li key={`${row.assetUid}-${row.interfaceName}`}>
              <div className="traffic-exceed-title">
                <AlertTriangle size={16} aria-hidden="true" />
                <strong>{row.assetUid}</strong>
                <span>{row.interfaceName}</span>
                <em>{formatBps(totalTraffic(row))}</em>
              </div>
              <TrafficFlowBars row={row} maxTraffic={maxTraffic} />
            </li>
          ))}
        </ul>
      ) : (
        <div className="traffic-empty">
          <Activity size={16} aria-hidden="true" />
          <span>No traffic exceed.</span>
        </div>
      )}
    </section>
  );
}

function TrafficFlowBars({ row, maxTraffic }: { row: InterfaceTraffic; maxTraffic: number }) {
  return (
    <div className="traffic-flow-bars">
      <div className="traffic-mini-bar in">
        <ArrowDownToLine size={14} aria-hidden="true" />
        <div><i style={{ width: barWidth(row.inBps, maxTraffic) }} /></div>
        <em>{formatBps(row.inBps)}</em>
      </div>
      <div className="traffic-mini-bar out">
        <ArrowUpFromLine size={14} aria-hidden="true" />
        <div><i style={{ width: barWidth(row.outBps, maxTraffic) }} /></div>
        <em>{formatBps(row.outBps)}</em>
      </div>
    </div>
  );
}

function ExceedBadge({ count }: { count: number }) {
  if (count <= 0) {
    return <span className="badge info">0</span>;
  }
  return <span className="badge warning">{count}</span>;
}

function buildAssetSummaries(rows: InterfaceTraffic[], thresholdBps: number): AssetTrafficSummary[] {
  const summaries = new Map<string, AssetTrafficSummary>();
  for (const row of rows) {
    const summary = summaries.get(row.assetUid) ?? {
      assetUid: row.assetUid,
      interfaceCount: 0,
      totalInBps: 0,
      totalOutBps: 0,
      totalBps: 0,
      peakUtilizationPct: 0,
      errors: 0,
      discards: 0,
      exceedCount: 0,
      status: 'unknown'
    };
    summary.interfaceCount += 1;
    summary.totalInBps += row.inBps;
    summary.totalOutBps += row.outBps;
    summary.totalBps += totalTraffic(row);
    summary.peakUtilizationPct = Math.max(summary.peakUtilizationPct, safeNumber(row.utilizationPct));
    summary.errors += row.errors;
    summary.discards += row.discards;
    summary.exceedCount += exceedsTraffic(row, thresholdBps) ? 1 : 0;
    summary.busiestInterface = !summary.busiestInterface || totalTraffic(row) > totalTraffic(summary.busiestInterface)
      ? row
      : summary.busiestInterface;
    summary.status = mergeStatus(summary.status, row.status);
    summaries.set(row.assetUid, summary);
  }
  return [...summaries.values()].sort((left, right) => right.totalBps - left.totalBps);
}

function filterRows(rows: InterfaceTraffic[], selectedAsset: string, query: string) {
  const normalizedQuery = query.trim().toLowerCase();
  return rows.filter((row) => {
    const assetMatches = selectedAsset === 'ALL' || row.assetUid === selectedAsset;
    const queryMatches = !normalizedQuery
      || row.assetUid.toLowerCase().includes(normalizedQuery)
      || row.interfaceName.toLowerCase().includes(normalizedQuery);
    return assetMatches && queryMatches;
  });
}

function filterAssetSummaries(assets: AssetTrafficSummary[], selectedAsset: string, query: string) {
  const normalizedQuery = query.trim().toLowerCase();
  return assets.filter((asset) => {
    const assetMatches = selectedAsset === 'ALL' || asset.assetUid === selectedAsset;
    const queryMatches = !normalizedQuery
      || asset.assetUid.toLowerCase().includes(normalizedQuery)
      || asset.busiestInterface?.interfaceName.toLowerCase().includes(normalizedQuery);
    return assetMatches && queryMatches;
  });
}

function exceedsTraffic(row: InterfaceTraffic, thresholdBps: number) {
  return (thresholdBps > 0 && totalTraffic(row) >= thresholdBps) || safeNumber(row.utilizationPct) >= 80;
}

function totalTraffic(row: InterfaceTraffic) {
  return safeNumber(row.inBps) + safeNumber(row.outBps);
}

function barWidth(value: number, maxTraffic: number) {
  return `${Math.max(2, Math.min(100, (safeNumber(value) / Math.max(1, maxTraffic)) * 100)).toFixed(1)}%`;
}

function safeNumber(value: number) {
  return Number.isFinite(value) ? value : 0;
}

function formatPct(value: number) {
  return `${safeNumber(value).toFixed(1)}%`;
}

function mergeStatus(current: string, next: string) {
  const normalized = next.toLowerCase();
  if (normalized === 'down' || normalized === 'failed') {
    return 'down';
  }
  if (current === 'down') {
    return current;
  }
  if (normalized === 'up' || normalized === 'active' || normalized === 'healthy') {
    return 'up';
  }
  return current === 'unknown' ? normalized || 'unknown' : current;
}

function statusClass(status: string, row: InterfaceTraffic) {
  const normalized = status.toLowerCase();
  if (normalized === 'up' || normalized === 'active' || normalized === 'healthy') {
    return row.errors + row.discards > 0 ? 'pending' : 'active';
  }
  if (normalized === 'down' || normalized === 'failed') {
    return 'blocked';
  }
  return 'unknown';
}

function statusLabel(status: string, row: InterfaceTraffic) {
  if (row.errors + row.discards > 0) {
    return 'NOISY';
  }
  return status ? status.toUpperCase() : 'UNKNOWN';
}
