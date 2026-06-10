import { ViewFrame } from '../components/ViewFrame';

export function AgentDashboardView() {
  return (
    <ViewFrame title="Agent">
      <div className="split-grid">
        <section className="data-panel"><h3>Heartbeat</h3><p>최근 수집 상태 기준</p></section>
        <section className="data-panel"><h3>Collectors</h3><p>CPU, memory, disk, process, service, port</p></section>
        <section className="data-panel"><h3>Resources</h3><p>CPU / Memory / Disk / Network</p></section>
        <section className="data-panel"><h3>Events</h3><p>process, service, port, log events</p></section>
      </div>
    </ViewFrame>
  );
}
