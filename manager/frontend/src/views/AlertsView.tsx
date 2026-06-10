import { useMemo, useState } from 'react';
import { ViewFrame } from '../components/ViewFrame';
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

  return (
    <ViewFrame
      title="알림"
      actions={
        <div className="segmented">
          <select aria-label="severity" value={severity} onChange={(event) => setSeverity(event.target.value as AlertRow['severity'] | 'ALL')}>
            <option value="ALL">All</option>
            <option value="CRITICAL">Critical</option>
            <option value="WARNING">Warning</option>
          </select>
          <select aria-label="status" value={status} onChange={(event) => setStatus(event.target.value as AlertRow['status'] | 'ALL')}>
            <option value="ALL">All</option>
            <option value="ACTIVE">Active</option>
            <option value="ACKNOWLEDGED">Ack</option>
            <option value="RESOLVED">Resolved</option>
          </select>
        </div>
      }
    >
      <table>
        <thead>
          <tr>
            <th>Severity</th>
            <th>Status</th>
            <th>Title</th>
            <th>Action</th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((alert) => (
            <tr key={alert.id}>
              <td>{alert.severity}</td>
              <td>{alert.status}</td>
              <td>{alert.title}</td>
              <td>
                {canManage && alert.status === 'ACTIVE' && (
                  <button type="button" onClick={() => onAcknowledge(alert.id)}>확인</button>
                )}
                {canManage && alert.status !== 'RESOLVED' && (
                  <button type="button" onClick={() => onResolve(alert.id)}>해결</button>
                )}
              </td>
            </tr>
          ))}
          {filtered.length === 0 && <tr><td colSpan={4}>표시할 알림 없음</td></tr>}
        </tbody>
      </table>
    </ViewFrame>
  );
}
