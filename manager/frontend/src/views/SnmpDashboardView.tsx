import { ViewFrame } from '../components/ViewFrame';

export function SnmpDashboardView() {
  return (
    <ViewFrame title="SNMP">
      <div className="split-grid">
        <section className="data-panel"><h3>Poll health</h3><p>target success/failure</p></section>
        <section className="data-panel"><h3>Interfaces</h3><p>status, traffic, errors, discards</p></section>
      </div>
    </ViewFrame>
  );
}
