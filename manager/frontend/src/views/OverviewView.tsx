import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import type { DashboardSummary } from '../lib/types';
import { summarizeDashboard } from '../lib/uiModel';

type OverviewViewProps = {
  summary: DashboardSummary;
};

export function OverviewView({ summary }: OverviewViewProps) {
  return (
    <ViewFrame title="개요">
      <MetricCards items={summarizeDashboard(summary)} />
      <div className="split-grid">
        <section className="data-panel">
          <h3>Agent 상태</h3>
          <p>{summary.agentHealth.healthy} healthy, {summary.agentHealth.stale} stale</p>
        </section>
        <section className="data-panel">
          <h3>SNMP polling</h3>
          <p>{summary.snmpPollHealth.success} success, {summary.snmpPollHealth.failure} failure</p>
        </section>
      </div>
    </ViewFrame>
  );
}
