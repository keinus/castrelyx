import { Plus, RefreshCw, Save, X } from 'lucide-react';
import { type FormEvent, useState } from 'react';
import { MetricCards } from '../components/MetricCards';
import { ViewFrame } from '../components/ViewFrame';
import { ViewFeedback } from '../components/ViewFeedback';
import { api } from '../lib/api';
import type { Role, SnmpTarget, SnmpTargetInput } from '../lib/types';
import { canMutate, formatBps } from '../lib/uiModel';
import { useRemoteData } from '../lib/useRemoteData';

export function SnmpDashboardView({ role }: { role: Role }) {
  const targets = useRemoteData(api.snmpTargets, 'SNMP 대상을 불러오지 못했습니다.');
  const telemetry = useRemoteData(api.snmpDashboard, '인터페이스 데이터를 불러오지 못했습니다.');
  const [creating, setCreating] = useState(false);
  const [pending, setPending] = useState<number | null>(null);
  const [error, setError] = useState('');
  const canManage = canMutate(role, 'snmp:update');
  async function toggle(target: SnmpTarget) {
    if (pending !== null) return;
    setPending(target.id); setError('');
    try { await api.setSnmpTargetEnabled(target.id, !target.enabled); await targets.reload(); }
    catch (cause) { setError(`대상 상태를 변경하지 못했습니다. ${cause instanceof Error ? cause.message : ''}`); }
    finally { setPending(null); }
  }
  return <ViewFrame title="SNMP" subtitle="수집 대상의 등록 설정과 인터페이스 트래픽을 한곳에서 확인합니다." actions={<>
    <button type="button" aria-label="SNMP 새로고침" disabled={targets.loading || telemetry.loading} onClick={() => { void targets.reload(); void telemetry.reload(); }}><RefreshCw size={16} /></button>
    {canManage && <button className="cc-primary" type="button" onClick={() => setCreating(!creating)}>{creating ? <X size={16} /> : <Plus size={16} />}{creating ? '등록 닫기' : '대상 등록'}</button>}
  </>}>
    <ViewFeedback loading={targets.loading} error={targets.error} hasData={!!targets.data} />
    {error && <div className="notice error" role="alert">{error}</div>}
    <MetricCards items={[
      ['등록 대상', targets.data ? String(targets.data.length) : '—'],
      ['활성 설정', targets.data ? String(targets.data.filter((target) => target.enabled).length) : '—'],
      ['비활성 설정', targets.data ? String(targets.data.filter((target) => !target.enabled).length) : '—'],
      ['폴링 결과', '미제공']
    ]} />
    {creating && canManage && <TargetForm onClose={() => setCreating(false)} onSaved={async () => { setCreating(false); await targets.reload(); }} onFailure={targets.reload} />}
    <section className="data-panel"><div className="panel-heading"><h3>등록된 대상</h3><span>{targets.data ? `${targets.data.length} targets` : '미확인'}</span></div>
      <div className="table-scroll"><table><thead><tr><th>대상</th><th>주소</th><th>주기</th><th>Credential ID</th><th>등록 설정</th></tr></thead><tbody>
        {targets.data?.map((target) => <tr key={target.id}><td><div className="cc-cell-stack"><strong>{target.name}</strong><small>Target #{target.id}</small></div></td><td>{target.host}:{target.port}</td><td>{target.pollIntervalMs / 1000}초</td><td>{target.credentialId ?? '—'}</td>
          <td>{canManage ? <button type="button" role="switch" aria-label={`${target.name} 활성화`} aria-checked={target.enabled} disabled={pending !== null || targets.loading} onClick={() => void toggle(target)}>{pending === target.id ? '저장 중…' : target.enabled ? '활성' : '비활성'}</button> : <span className={`badge ${target.enabled ? 'success' : 'neutral'}`}>{target.enabled ? '활성' : '비활성'}</span>}</td></tr>)}
        {targets.data?.length === 0 && <tr><td colSpan={5} className="cc-empty">등록된 SNMP 대상이 없습니다.</td></tr>}
      </tbody></table></div>
      <p className="cc-help">활성 설정은 Manager의 대상 등록 상태입니다. 실제 폴링 실행 및 입력 어댑터 설정은 LogParser에서 확인하세요.</p>
    </section>
    <section className="data-panel"><div className="panel-heading"><h3>인터페이스 트래픽</h3><span>최근 1시간 · 전체 수집 자산</span></div>
      <ViewFeedback loading={telemetry.loading} error={telemetry.error} hasData={!!telemetry.data} />
      <div className="table-scroll"><table><thead><tr><th>자산 / 인터페이스</th><th>상태</th><th>수신</th><th>송신</th><th>사용률</th><th>오류 / 폐기</th></tr></thead><tbody>
        {telemetry.data?.interfaces?.map((row) => <tr key={`${row.assetUid}-${row.interfaceName}`}><td><div className="cc-cell-stack"><strong>{row.interfaceName}</strong><small>{row.assetUid}</small></div></td><td><span className={`badge ${row.status.toLowerCase() === 'up' ? 'success' : 'neutral'}`}>{row.status}</span></td><td>{formatBps(row.inBps)}</td><td>{formatBps(row.outBps)}</td><td>{row.utilizationPct.toFixed(1)}%</td><td>{row.errors} / {row.discards}</td></tr>)}
        {telemetry.data && !telemetry.data.interfaces?.length && <tr><td colSpan={6} className="cc-empty">수집된 인터페이스 데이터가 없습니다.</td></tr>}
      </tbody></table></div>
      <p className="cc-help">현재 제공되는 데이터는 전체 자산 기준입니다. 대상별 폴링 성공·실패 집계는 아직 제공되지 않습니다.</p>
    </section>
  </ViewFrame>;
}

function TargetForm({ onClose, onSaved, onFailure }: { onClose: () => void; onSaved: () => Promise<void>; onFailure: () => Promise<void> }) {
  const [form, setForm] = useState<SnmpTargetInput>({ name: '', host: '', port: 161, credentialId: null, enabled: true, pollIntervalMs: 60000, oids: [] });
  const [oids, setOids] = useState('1.3.6.1.2.1.2.2.1');
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (working) return;
    const values = oids.split(/[\s,]+/).filter(Boolean);
    if (!values.length || values.some((oid) => !/^\.?\d+(\.\d+)+$/.test(oid))) { setError('수집 OID를 숫자와 점으로 입력하세요.'); return; }
    setWorking(true); setError('');
    try { await api.createSnmpTarget({ ...form, name: form.name.trim(), host: form.host.trim(), oids: values }); await onSaved(); }
    catch (cause) {
      setError(`대상 등록 또는 어댑터 연결에 실패했습니다. 다시 등록하기 전에 아래 대상 목록을 확인하세요. ${cause instanceof Error ? cause.message : ''}`);
      await onFailure();
    } finally { setWorking(false); }
  }
  return <section className="data-panel"><div className="panel-heading"><h3>새 SNMP 대상</h3></div>
    <form className="cc-form cc-panel-body" onSubmit={(event) => void submit(event)}>
      <div className="cc-form-grid">
        <label>대상 이름<input required value={form.name} disabled={working} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label>
        <label>호스트 / IP<input required value={form.host} disabled={working} onChange={(event) => setForm({ ...form, host: event.target.value })} /></label>
        <label>포트<input type="number" min={1} max={65535} required value={form.port} disabled={working} onChange={(event) => setForm({ ...form, port: Number(event.target.value) })} /></label>
        <label>수집 주기 (초)<input type="number" min={1} required value={form.pollIntervalMs / 1000} disabled={working} onChange={(event) => setForm({ ...form, pollIntervalMs: Number(event.target.value) * 1000 })} /></label>
        <label>Credential ID (선택)<input type="number" min={1} value={form.credentialId ?? ''} disabled={working} onChange={(event) => setForm({ ...form, credentialId: event.target.value ? Number(event.target.value) : null })} /><small>기존에 등록된 자격 증명의 ID를 입력합니다.</small></label>
        <label>수집 OID<textarea required rows={3} value={oids} disabled={working} onChange={(event) => setOids(event.target.value)} /><small>여러 OID는 줄바꿈 또는 쉼표로 구분합니다.</small></label>
      </div>
      <label className="cc-check"><input type="checkbox" checked={form.enabled} disabled={working} onChange={(event) => setForm({ ...form, enabled: event.target.checked })} />대상 활성 설정</label>
      {error && <div className="notice error" role="alert">{error}</div>}
      <div className="cc-action-row"><button type="button" disabled={working} onClick={onClose}>취소</button><button className="cc-primary" type="submit" disabled={working}><Save size={15} />{working ? '등록 중…' : '대상 저장'}</button></div>
    </form>
  </section>;
}
