import { ExternalLink, KeyRound } from 'lucide-react';
import type { Role } from '../lib/types';
import { canMutate } from '../lib/uiModel';
import { ViewFrame } from '../components/ViewFrame';

type IntegrationsViewProps = {
  role: Role;
};

export function IntegrationsView({ role }: IntegrationsViewProps) {
  return (
    <ViewFrame title="연동">
      <div className="split-grid">
        <section className="data-panel">
          <h3>CastrelSign</h3>
          <p>enrollment token, agents, certificates</p>
          {canMutate(role, 'integration:update-secret') && <button><KeyRound size={16} />토큰 갱신</button>}
        </section>
        <section className="data-panel">
          <h3>logparser</h3>
          <p>SNMP input adapter, pipeline status, deep link</p>
          <button><ExternalLink size={16} />열기</button>
        </section>
      </div>
    </ViewFrame>
  );
}
