import { ViewFrame } from '../components/ViewFrame';

export function SettingsView() {
  return (
    <ViewFrame title="설정">
      <div className="split-grid">
        <section className="data-panel"><h3>사용자</h3><p>ADMIN, OPERATOR, VIEWER</p></section>
        <section className="data-panel"><h3>Roadmap</h3><p>OAuth2/OIDC, NetFlow/sFlow/IPFIX, webhook/email/SMS</p></section>
      </div>
    </ViewFrame>
  );
}
