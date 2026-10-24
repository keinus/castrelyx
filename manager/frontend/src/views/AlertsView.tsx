import { useMemo, useState } from 'react';
import { ViewFrame } from '../components/ViewFrame';
import { MetricCards } from '../components/MetricCards';
import type { AlertRow, Role } from '../lib/types';
import { canMutate, filterAlerts } from '../lib/uiModel';

type AlertsViewProps = {
  role: Role;
  alerts: AlertRow[];
  onAcknowledge: (id: number) => Promise<void>;
  onResolve: (id: number) => Promise<void>;
};

export function AlertsView({ role, alerts, onAcknowledge, onResolve }: AlertsViewProps) {
  const [severity, setSeverity] = useState<AlertRow['severity'] | 'ALL'>('ALL');
  const [status, setStatus] = useState<AlertRow['status'] | 'ALL'>('ACTIVE');
  const filtered = useMemo(() => filterAlerts(alerts, { severity, status }), [alerts, severity, status]);
  const canManage = canMutate(role, 'alert:update');
  const [pending, setPending] = useState<number | null>(null);
  const [error, setError] = useState('');
  async function update(id: number, action: 'acknowledge' | 'resolve') {
    if (pending !== null) return;
    setPending(id); setError('');
    try { await (action === 'acknowledge' ? onAcknowledge(id) : onResolve(id)); }
    catch (cause) { setError(`알림을 처리하지 못했습니다. ${cause instanceof Error ? cause.message : ''}`); }
    finally { setPending(null); }
  }

  return (
    <ViewFrame
      title="알림"
      subtitle="발생한 문제의 근거를 확인하고, 확인과 해결 상태를 관리합니다."
      actions={
        <div className="segmented">
          <select aria-label="severity" value={severity} onChange={(event) => setSeverity(event.target.value as AlertRow['severity'] | 'ALL')}>
            <option value="ALL">All</option>
            <option value="CRITICAL">Critical</option>
            <option value="WARNING">Warning</option>
            <option value="INFO">Info</option>
          </select>
          <select aria-label="status" value={status} onChange={(event) => setStatus(event.target.value as AlertRow['status'] | 'ALL')}>
            <option value="ALL">All</option>
            <option value="ACTIVE">Active</option>
            <option value="ACKNOWLEDGED">Ack</option>
            <option value="RESOLVED">Resolved</option>
            <option value="MERGED">Merged</option>
          </select>
        </div>
      }
    >
      {error && <div className="notice error" role="alert">{error}</div>}
      <MetricCards items={[
        ['활성 알림', String(alerts.filter((alert) => alert.status === 'ACTIVE').length)],
        ['Critical · 미해결', String(alerts.filter((alert) => alert.severity === 'CRITICAL' && ['ACTIVE', 'ACKNOWLEDGED'].includes(alert.status)).length)],
        ['확인 완료', String(alerts.filter((alert) => alert.status === 'ACKNOWLEDGED').length)],
        ['해결 완료', String(alerts.filter((alert) => alert.status === 'RESOLVED').length)]
      ]} />
      <section className="data-panel"><div className="panel-heading"><h3>알림 목록</h3><span>{filtered.length} / {alerts.length} incidents</span></div>
      <div className="table-scroll"><table className="cc-alert-table">
        <thead>
          <tr>
            <th>Severity</th>
            <th>Status</th>
            <th>Title</th>
            <th>최근 발생</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((alert) => (
            <tr key={alert.id}>
              <td><span className={`badge ${alert.severity.toLowerCase()}`}>{alert.severity}</span></td>
              <td><span className={`badge ${alert.status === 'RESOLVED' ? 'success' : alert.status === 'ACTIVE' ? 'warning' : 'info'}`}>{alert.status}</span></td>
              <td><div className="cc-cell-stack"><strong>{alert.title}</strong>{alert.detail && <small>{alert.detail}</small>}</div></td>
              <td>{alert.lastSeenAt ? new Date(alert.lastSeenAt).toLocaleString('ko-KR') : '—'}</td>
              <td>
                <div className="cc-action-row" aria-busy={pending === alert.id}>
                {canManage && alert.status === 'ACTIVE' && (
                  <button type="button" disabled={pending !== null} onClick={() => void update(alert.id, 'acknowledge')}>확인</button>
                )}
                {canManage && ['ACTIVE', 'ACKNOWLEDGED'].includes(alert.status) && (
                  <button type="button" disabled={pending !== null} onClick={() => void update(alert.id, 'resolve')}>해결</button>
                )}
                {!canManage && <span className="muted">읽기 전용</span>}
                </div>
              </td>
            </tr>
          ))}
          {filtered.length === 0 && <tr><td colSpan={5} className="cc-empty">표시할 알림 없음</td></tr>}
        </tbody>
      </table></div></section>
    </ViewFrame>
  );
}
