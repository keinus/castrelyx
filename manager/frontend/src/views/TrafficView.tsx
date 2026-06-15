import { RefreshCw } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { InterfaceTraffic } from '../lib/types';
import { formatBps } from '../lib/uiModel';

const rangeOptions = [
  ['15m', '15분'],
  ['30m', '30분'],
  ['1h', '1시간'],
  ['6h', '6시간'],
  ['24h', '24시간']
] as const;

export function TrafficView() {
  const [range, setRange] = useState('1h');
  const [rows, setRows] = useState<InterfaceTraffic[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  async function load(selectedRange = range) {
    setLoading(true);
    setError('');
    try {
      setRows(await api.trafficInterfaces(selectedRange));
    } catch {
      setRows([]);
      setError('인터페이스 트래픽 정보를 불러오지 못했습니다.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(range);
  }, [range]);

  const summary = useMemo(() => {
    const totalIn = rows.reduce((sum, row) => sum + row.inBps, 0);
    const totalOut = rows.reduce((sum, row) => sum + row.outBps, 0);
    const noisy = rows.filter((row) => row.errors + row.discards > 0).length;
    return { totalIn, totalOut, noisy };
  }, [rows]);

  return (
    <ViewFrame
      title="인터페이스 트래픽"
      actions={(
        <>
          <select aria-label="조회 범위" value={range} onChange={(event) => setRange(event.target.value)}>
            {rangeOptions.map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
          <button className="icon-button" aria-label="새로고침" onClick={() => void load()} type="button">
            <RefreshCw size={18} />
          </button>
        </>
      )}
    >
      {error && <div className="notice error">{error}</div>}
      {loading && <div className="notice">수집된 인터페이스 트래픽을 갱신하는 중입니다.</div>}

      <MetricCards
        items={[
          ['인터페이스', String(rows.length)],
          ['Inbound', formatBps(summary.totalIn)],
          ['Outbound', formatBps(summary.totalOut)],
          ['에러/드롭', String(summary.noisy)]
        ]}
      />

      <section className="data-panel">
        <div className="panel-heading">
          <h3>수집 기준 트래픽</h3>
          <span>{rangeOptions.find(([value]) => value === range)?.[1] ?? range}</span>
        </div>
        <InterfaceTrafficTable rows={rows} loading={loading} />
      </section>
    </ViewFrame>
  );
}

function InterfaceTrafficTable({ rows, loading }: { rows: InterfaceTraffic[]; loading: boolean }) {
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>자산</th>
            <th>인터페이스</th>
            <th>In</th>
            <th>Out</th>
            <th>Util</th>
            <th>Errors/Discards</th>
            <th>상태</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.assetUid}-${row.interfaceName}`}>
              <td>{row.assetUid}</td>
              <td>{row.interfaceName}</td>
              <td>{formatBps(row.inBps)}</td>
              <td>{formatBps(row.outBps)}</td>
              <td>{formatPct(row.utilizationPct)}</td>
              <td>{row.errors + row.discards}</td>
              <td><span className={`status-pill ${statusClass(row.status)}`}>{statusLabel(row.status)}</span></td>
            </tr>
          ))}
          {!loading && rows.length === 0 && (
            <tr><td colSpan={7}>표시할 인터페이스 트래픽 없음</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function formatPct(value: number) {
  return `${Number.isFinite(value) ? value.toFixed(1) : '0.0'}%`;
}

function statusClass(status: string) {
  const normalized = status.toLowerCase();
  if (normalized === 'up' || normalized === 'active' || normalized === 'healthy') {
    return 'active';
  }
  if (normalized === 'down' || normalized === 'failed') {
    return 'blocked';
  }
  return 'unknown';
}

function statusLabel(status: string) {
  return status ? status.toUpperCase() : 'UNKNOWN';
}
