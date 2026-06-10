import { Activity } from 'lucide-react';
import { ViewFrame } from '../components/ViewFrame';
import { formatBps } from '../lib/uiModel';

export function TrafficView() {
  const rows = [
    { assetUid: 'edge-router', interfaceName: 'eth0', inBps: 1200000, outBps: 900000, utilizationPct: 12.4, errors: 0, discards: 0, status: 'up' }
  ];

  return (
    <ViewFrame title="Traffic" actions={<button className="icon-button" aria-label="새로고침"><Activity size={18} /></button>}>
      <table>
        <thead>
          <tr>
            <th>자산</th>
            <th>Interface</th>
            <th>In</th>
            <th>Out</th>
            <th>Util</th>
            <th>Errors</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.assetUid}-${row.interfaceName}`}>
              <td>{row.assetUid}</td>
              <td>{row.interfaceName}</td>
              <td>{formatBps(row.inBps)}</td>
              <td>{formatBps(row.outBps)}</td>
              <td>{row.utilizationPct.toFixed(1)}%</td>
              <td>{row.errors + row.discards}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </ViewFrame>
  );
}
