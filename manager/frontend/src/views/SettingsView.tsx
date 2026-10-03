import { Plug, RefreshCw, Save, ShieldCheck, UserRound } from 'lucide-react';
import { type FormEvent, useCallback, useState } from 'react';
import { ViewFrame } from '../components/ViewFrame';
import { ViewFeedback } from '../components/ViewFeedback';
import { api } from '../lib/api';
import type { AlertRule, IntegrationConfig, IntegrationName, Role } from '../lib/types';
import { canMutate } from '../lib/uiModel';
import { useRemoteData } from '../lib/useRemoteData';

export function SettingsView({ role, username }: { role: Role; username: string }) {
  return <ViewFrame title="설정" subtitle="서비스 연결과 알림 규칙을 관리합니다. 변경 권한은 현재 계정의 역할에 따라 적용됩니다.">
    <section className="data-panel cc-account">
      <span className="cc-section-icon"><UserRound size={20} /></span>
      <div className="cc-cell-stack"><strong>{username}</strong><small>현재 로그인한 계정</small></div>
      <span className="badge info">{role}</span>
    </section>
    <div className="cc-settings-grid">
      <IntegrationSettings name="castrelsign" title="CastrelSign" description="Agent 등록, 인증서 및 업데이트 서비스" role={role} />
      <IntegrationSettings name="logparser" title="LogParser" description="로그 파이프라인 및 SNMP 입력 서비스" role={role} />
    </div>
    <AlertRules role={role} />
  </ViewFrame>;
}

function IntegrationSettings({ name, title, description, role }: { name: IntegrationName; title: string; description: string; role: Role }) {
  const [saved, setSaved] = useState(false);
  const fetchConfig = useCallback(() => api.integration(name), [name]);
  const { data, loading, error, reload } = useRemoteData(fetchConfig, `${title} 설정을 불러오지 못했습니다.`);
  return <section className="data-panel cc-settings-card" aria-label={`${title} 연동 설정`}>
    <div className="cc-section-intro"><span className="cc-section-icon">{name === 'castrelsign' ? <ShieldCheck size={21} /> : <Plug size={21} />}</span>
      <div><h3>{title}</h3><p>{description}</p></div>
      <span className={`badge ${data?.enabled ? 'success' : 'neutral'}`}>{data ? data.enabled ? '사용 중' : '비활성' : '미확인'}</span>
    </div>
    {(loading || error) && <div className="cc-panel-body"><ViewFeedback loading={loading} error={error} hasData={!!data} />
      {error && <button type="button" disabled={loading} onClick={() => void reload()}>다시 불러오기</button>}
    </div>}
    {saved && <div className="notice" role="status">연동 설정을 저장했습니다.</div>}
    {data && <IntegrationForm key={`${data.baseUrl}-${data.enabled}-${data.secret.masked}`} name={name} config={data} canEdit={canMutate(role, 'integration:update')} onSaved={async () => { setSaved(true); await reload(); }} />}
  </section>;
}

function IntegrationForm({ name, config, canEdit, onSaved }: { name: IntegrationName; config: IntegrationConfig; canEdit: boolean; onSaved: () => Promise<void> }) {
  const [baseUrl, setBaseUrl] = useState(config.baseUrl ?? '');
  const [enabled, setEnabled] = useState(config.enabled);
  const [secret, setSecret] = useState('');
  const [working, setWorking] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const dirty = baseUrl.trim() !== (config.baseUrl ?? '') || enabled !== config.enabled || !!secret;
  async function save(event: FormEvent) {
    event.preventDefault();
    if (!canEdit || working) return;
    setWorking(true); setError(''); setNotice('');
    try {
      await api.updateIntegration(name, { baseUrl: baseUrl.trim(), enabled, ...(secret.trim() ? { [name === 'castrelsign' ? 'adminToken' : 'apiToken']: secret.trim() } : {}) });
      setSecret('');
      await onSaved();
    } catch (cause) { setError(`저장하지 못했습니다. ${cause instanceof Error ? cause.message : ''}`); }
    finally { setWorking(false); }
  }
  async function test() {
    if (!canEdit || working || dirty || !config.baseUrl?.trim()) return;
    setWorking(true); setError(''); setNotice('');
    try {
      const result = await api.testIntegration(name);
      if (!result.ok) throw new Error('서비스가 연결 성공을 반환하지 않았습니다.');
      setNotice('저장된 설정으로 연결 확인을 완료했습니다.');
    } catch (cause) { setError(`연결 확인 실패. ${cause instanceof Error ? cause.message : ''}`); }
    finally { setWorking(false); }
  }
  return <form className="cc-form" onSubmit={(event) => void save(event)}>
    <label>서비스 URL<input type="url" value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} placeholder="https://service.example.com" required={enabled} disabled={!canEdit || working} /></label>
    <label>{name === 'castrelsign' ? '관리자 토큰' : 'API 토큰'}
      <input type="password" autoComplete="new-password" value={secret} onChange={(event) => setSecret(event.target.value)} placeholder={config.secret.configured ? '새 토큰을 입력하면 교체됩니다' : '토큰 입력'} disabled={!canEdit || working} />
      <small>{config.secret.configured ? `저장된 토큰: ${config.secret.masked} · 비워 두면 기존 토큰을 유지합니다.` : '저장된 토큰이 없습니다.'}</small>
    </label>
    <label className="cc-check"><input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} disabled={!canEdit || working} />연동 사용</label>
    {error && <div className="notice error" role="alert">{error}</div>}
    {notice && <div className="notice" role="status">{notice}</div>}
    {canEdit ? <><small>연결 확인은 저장된 설정을 사용합니다. 변경 후 먼저 저장하세요.</small><div className="cc-action-row">
      <button type="button" onClick={() => void test()} disabled={working || dirty || !config.baseUrl?.trim()}><Plug size={15} />연결 확인</button>
      <button className="cc-primary" type="submit" disabled={working || !dirty}><Save size={15} />{working ? '처리 중…' : '설정 저장'}</button>
    </div></> : <small>연동 설정은 ADMIN 계정에서 변경할 수 있습니다.</small>}
  </form>;
}

function AlertRules({ role }: { role: Role }) {
  const { data: rules, loading, error, reload } = useRemoteData(api.alertRules, '알림 규칙을 불러오지 못했습니다.');
  const [pending, setPending] = useState<number | null>(null);
  const [mutationError, setMutationError] = useState('');
  async function toggle(rule: AlertRule) {
    if (pending !== null) return;
    setPending(rule.id); setMutationError('');
    try { await api.updateAlertRule({ ...rule, enabled: !rule.enabled }); await reload(); }
    catch (cause) { setMutationError(`규칙을 변경하지 못했습니다. ${cause instanceof Error ? cause.message : ''}`); }
    finally { setPending(null); }
  }
  return <section className="data-panel">
    <div className="panel-heading"><h3>알림 규칙</h3><button type="button" aria-label="알림 규칙 새로고침" disabled={loading || pending !== null} onClick={() => void reload()}><RefreshCw size={15} /></button></div>
    <ViewFeedback loading={loading} error={error} hasData={!!rules} />
    {mutationError && <div className="notice error" role="alert">{mutationError}</div>}
    <div className="table-scroll"><table className="cc-rule-table"><thead><tr><th>규칙</th><th>심각도</th><th>조건</th><th>상태</th></tr></thead><tbody>
      {rules?.map((rule) => <tr key={rule.id}><td><div className="cc-cell-stack"><strong>{rule.name}</strong><small>{rule.ruleType}</small></div></td>
        <td><span className={`badge ${rule.severity.toLowerCase()}`}>{rule.severity}</span></td><td><code>{rule.expressionJson?.trim() === '{}' ? '서버 기본 조건' : rule.expressionJson}</code></td>
        <td>{canMutate(role, 'alert:update') ? <button type="button" role="switch" aria-checked={rule.enabled} aria-label={`${rule.name} 활성화`} disabled={pending !== null || loading} onClick={() => void toggle(rule)}>{pending === rule.id ? '저장 중…' : rule.enabled ? '활성' : '비활성'}</button> : <span>{rule.enabled ? '활성' : '비활성'}</span>}</td></tr>)}
      {rules?.length === 0 && <tr><td colSpan={4} className="cc-empty">등록된 알림 규칙이 없습니다.</td></tr>}
    </tbody></table></div>
  </section>;
}
