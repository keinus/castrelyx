import { useCallback, useEffect, useRef, useState } from 'react';
import { RefreshCw, ShieldCheck } from 'lucide-react';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Field, FieldGroup, FieldLabel } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { routerControl, type RouterBinding, type RouterCommand, type RouterState } from '@/lib/routerControl';
import type { Role } from '@/lib/types';

type Draft = { request: Record<string, unknown>; title: string; diff?: unknown };
type RouterJob = { id: string; status: string; error?: string; request?: { kind?: string }; updatedAt?: string };
const print = (value: unknown) => JSON.stringify(value, null, 2);
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

export function RouterControlPanel({ assetUid, role, active }: { assetUid: string; role: Role; active: boolean }) {
  const admin = role === 'ADMIN';
  const [binding, setBinding] = useState<RouterBinding | null>(null);
  const [agentId, setAgentId] = useState('');
  const [routerId, setRouterId] = useState('');
  const [state, setState] = useState<RouterState | null>(null);
  const [config, setConfig] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [history, setHistory] = useState<RouterCommand[]>([]);
  const [jobs, setJobs] = useState<RouterJob[]>([]);
  const [backups, setBackups] = useState<{ id: string; createdAt: string }[]>([]);
  const [audit, setAudit] = useState<unknown>(null);
  const [service, setService] = useState('switch-zeek');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const control = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const lock = useRef(false);

  const perform = useCallback(async (work: (signal: AbortSignal) => Promise<void>) => {
    if (lock.current) return;
    lock.current = true; const current = generation.current;
    const abort = new AbortController(); control.current = abort;
    setBusy(true); setError(''); setNotice('');
    try { await work(abort.signal); }
    catch (failure) { if (!abort.signal.aborted) setError(message(failure)); }
    finally {
      if (generation.current === current) {
        lock.current = false; setBusy(false);
        if (!abort.signal.aborted) routerControl.history(assetUid).then(setHistory).catch(() => {});
      }
    }
  }, [assetUid]);

  const loadState = useCallback(async (signal: AbortSignal) => {
    const data = await routerControl.run(assetUid, { action: 'state' }, signal) as unknown as RouterState;
    if (signal.aborted) return;
    setState(data); setConfig(print(data.config)); setDraft(null);
  }, [assetUid]);

  useEffect(() => {
    generation.current++; control.current?.abort(); lock.current = false;
    setBusy(false); setBinding(null); setState(null); setHistory([]); setJobs([]); setBackups([]); setDraft(null); setError(''); setNotice(''); setAudit(null);
    if (!active) return;
    let cancelled = false;
    routerControl.binding(assetUid).then(value => {
      if (cancelled) return;
      setBinding(value); setAgentId(value.agentId ?? ''); setRouterId(value.routerId ?? '');
      if (value.enabled) void perform(loadState);
    }).catch(failure => { if (!cancelled) setError(message(failure)); });
    return () => { cancelled = true; generation.current++; control.current?.abort(); lock.current = false; };
  }, [active, assetUid, loadState, perform]);

  const prepare = (request: Record<string, unknown>, title: string) => void perform(async signal => {
    setDraft(null);
    const diff = await routerControl.run(assetUid, { ...request, action: 'validate' }, signal);
    if (!signal.aborted) setDraft({ request, title, diff });
  });
  const apply = () => {
    if (!draft) return;
    const selected = draft; setDraft(null);
    void perform(async signal => {
      const result = await routerControl.run(assetUid, selected.request, signal);
      if (signal.aborted) return;
      if (result.id) {
        setNotice(`Router 작업 ${String(result.id)} 접수됨. 실제 적용 결과는 작업 이력에서 확인하세요.`);
        const list = await routerControl.run(assetUid, { action: 'jobs' }, signal);
        if (!signal.aborted) setJobs((list.items ?? []) as RouterJob[]);
      } else setNotice('작업이 완료되었습니다.');
      await loadState(signal);
    });
  };
  const revision = state?.appliedRevision;

  if (!active) return null;
  return <div className="flex min-w-0 flex-col gap-4" aria-busy={busy}>
    <Card>
      <CardHeader><CardTitle className="flex items-center gap-2"><ShieldCheck aria-hidden="true" />Security Router 제어</CardTitle>
        <CardDescription>장비 상태를 확인하고 설정을 검증·적용합니다. 변경 전 설정을 자동 보관합니다.</CardDescription></CardHeader>
      <CardContent className="flex flex-col gap-4">
        {error ? <Alert variant="destructive"><AlertTitle>요청을 처리하지 못했습니다</AlertTitle><AlertDescription>{error}</AlertDescription></Alert> : null}
        {notice ? <Alert><AlertTitle>작업 결과</AlertTitle><AlertDescription>{notice}</AlertDescription></Alert> : null}
        {busy ? <p role="status" className="text-sm text-muted-foreground">Agent 응답을 기다리는 중입니다…</p> : null}
        {!binding && !error ? <p>연결 정보를 불러오는 중입니다.</p> : null}
        {binding?.enabled ? <div className="flex flex-wrap items-center gap-3">
          <Badge variant={state?.ready ? 'default' : 'secondary'}>{state ? (state.ready ? '정상' : state.snapshot.state) : '미조회'}</Badge>
          <span className="text-sm">{binding.routerId} · Agent {binding.agentId}</span>
          <Button variant="outline" size="sm" disabled={busy} onClick={() => void perform(loadState)}><RefreshCw data-icon="inline-start" />상태 새로고침</Button>
          {state ? <span className="text-sm text-muted-foreground">설정 버전 {state.appliedRevision} · {state.mode} · 관측 {new Date(state.snapshot.observedAt).toLocaleString()}</span> : null}
        </div> : binding ? <p>이 자산에 제어 Agent가 연결되지 않았습니다.{!admin ? ' 관리자에게 연결을 요청하세요.' : ''}</p> : null}
        {admin ? <details><summary className="cursor-pointer text-sm">제어 Agent 연결 설정</summary>
          <form className="mt-4" onSubmit={event => { event.preventDefault(); void perform(async signal => {
            const value = await routerControl.bind(assetUid, agentId, routerId); if (signal.aborted) return; setBinding(value); await loadState(signal);
          }); }}><FieldGroup><Field><FieldLabel htmlFor="router-agent-id">등록된 Agent ID</FieldLabel><Input id="router-agent-id" required value={agentId} onChange={event => setAgentId(event.target.value)} /></Field>
            <Field><FieldLabel htmlFor="router-device-id">Router ID (자산 로그의 Source ID)</FieldLabel><Input id="router-device-id" required value={routerId} onChange={event => setRouterId(event.target.value)} /></Field>
            <Button type="submit" disabled={busy}>연결 저장</Button></FieldGroup></form></details> : null}
      </CardContent>
    </Card>
    {state ? <>
      {draft ? <Card><CardHeader><CardTitle>{draft.title} — 적용 전 확인</CardTitle><CardDescription>버전 {revision} 기준입니다. 변경 후 Router가 검증하며 실패 시 자체 롤백을 수행합니다.</CardDescription></CardHeader>
        <CardContent className="flex flex-col gap-3">{draft.diff ? <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-3 text-xs">{print(draft.diff)}</pre> : <p>{print(draft.request)}</p>}
          <div className="flex gap-2"><Button disabled={busy} onClick={apply}>확인 후 적용</Button><Button variant="outline" disabled={busy} onClick={() => setDraft(null)}>취소</Button></div></CardContent></Card> : null}
      <Tabs defaultValue="status">
        <TabsList className="h-auto flex-wrap"><TabsTrigger value="status">상태·포트</TabsTrigger><TabsTrigger value="configuration">설정·정책</TabsTrigger><TabsTrigger value="operations">운영·복원</TabsTrigger><TabsTrigger value="history">작업 이력</TabsTrigger></TabsList>
        <TabsContent value="status"><Card><CardHeader><CardTitle>데이터 포트</CardTitle><CardDescription>관리 포트와 업링크 보호 규칙은 장비에서 검증합니다.</CardDescription></CardHeader><CardContent>
          <Table><TableHeader><TableRow><TableHead>포트</TableHead><TableHead>역할</TableHead><TableHead>관리 / 링크</TableHead>{admin ? <TableHead>제어</TableHead> : null}</TableRow></TableHeader><TableBody>
            {(state.snapshot.ports ?? []).map(port => <TableRow key={port.name}><TableCell>{port.name}</TableCell><TableCell>{port.role}</TableCell><TableCell>{port.admin ? 'UP' : 'DOWN'} / {port.oper ? 'UP' : 'DOWN'}</TableCell>
              {admin ? <TableCell><Button size="sm" variant="outline" disabled={busy || !['access', 'mirror-output'].includes(port.role)} onClick={() => prepare({ action: 'apply', kind: 'port.admin', expectedRevision: revision, parameters: { port: port.name, state: port.admin ? 'down' : 'up' } }, `${port.name} ${port.admin ? '비활성화' : '활성화'}`)}>변경 검증</Button></TableCell> : null}</TableRow>)}
          </TableBody></Table>
          <details className="mt-4"><summary>지원 기능과 제한</summary><Table><TableBody>{(state.capabilities ?? []).map(cap => <TableRow key={cap.id}><TableCell>{cap.id}</TableCell><TableCell>{cap.state}</TableCell><TableCell>{cap.reason}</TableCell></TableRow>)}</TableBody></Table></details>
        </CardContent></Card></TabsContent>
        <TabsContent value="configuration"><Card><CardHeader><CardTitle>설정 검증 및 적용</CardTitle><CardDescription>VLAN·ACL·보안 정책을 편집한 뒤 검증 결과의 변경 사항을 확인하세요. 비밀값은 표시되지 않습니다.</CardDescription></CardHeader><CardContent><FieldGroup>
          <Field><FieldLabel htmlFor="router-config">설정 JSON · 버전 {revision}</FieldLabel><Textarea id="router-config" className="min-h-80 font-mono" value={config} disabled={busy} readOnly={!admin} onChange={event => { setConfig(event.target.value); setDraft(null); }} /></Field>
          {admin ? <Button disabled={busy} onClick={() => { try { const parsed: unknown = JSON.parse(config); if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('설정은 JSON 객체여야 합니다.'); prepare({ action: 'apply', kind: 'config', expectedRevision: revision, config: parsed }, '설정 변경'); } catch (failure) { setError(message(failure)); } }}>설정 검증·차이 확인</Button> : null}
        </FieldGroup></CardContent></Card></TabsContent>
        <TabsContent value="operations"><div className="flex flex-col gap-4"><Card><CardHeader><CardTitle>운영 모드·서비스</CardTitle><CardDescription>유지보수는 1시간 후 만료됩니다. 서비스 재시작은 진행 중인 Router 작업이 없을 때 허용됩니다.</CardDescription></CardHeader><CardContent className="flex flex-col gap-4">
          {admin ? <div className="flex flex-wrap gap-2">{[{ mode: 'automatic', label: '자동 운영' }, { mode: 'maintenance', label: '1시간 유지보수' }, { mode: 'emergency-stop', label: '자동 변경 중지' }].map(item => <Button key={item.mode} variant="outline" disabled={busy} onClick={() => setDraft({ title: item.label, request: { action: 'mode', mode: item.mode, ...(item.mode === 'maintenance' ? { until: new Date(Date.now() + 3600_000).toISOString() } : {}) } })}>{item.label}</Button>)}</div> : null}
          <Table><TableBody>{(state.snapshot.services ?? []).map(item => <TableRow key={item.name}><TableCell>{item.name}</TableCell><TableCell>{item.state}</TableCell><TableCell>{item.detail}</TableCell></TableRow>)}</TableBody></Table>
          {admin ? <FieldGroup><Field><FieldLabel htmlFor="router-service">재시작할 서비스</FieldLabel><NativeSelect id="router-service" value={service} onChange={event => setService(event.target.value)}>{(state.restartServices ?? []).map(name => <option key={name}>{name}</option>)}</NativeSelect></Field><Button variant="outline" disabled={busy || !(state.restartServices ?? []).includes(service)} onClick={() => setDraft({ title: `${service} 재시작`, request: { action: 'restart', service } })}>재시작 확인</Button></FieldGroup> : null}
        </CardContent></Card>
        <Card><CardHeader><CardTitle>설정 백업·복원</CardTitle><CardDescription>Agent에 보관하는 설정 스냅샷입니다. 계정·인증서·비밀값과 전체 DB는 포함하지 않습니다.</CardDescription></CardHeader><CardContent className="flex flex-col gap-3"><div className="flex flex-wrap gap-2">
          {admin ? <Button disabled={busy} onClick={() => void perform(async signal => { const value = await routerControl.run(assetUid, { action: 'backup' }, signal); if (!signal.aborted) setNotice(`설정 백업 완료: ${String(value.backupId)}`); const list = await routerControl.run(assetUid, { action: 'backups' }, signal); if (!signal.aborted) setBackups(list.items as typeof backups); })}>현재 설정 백업</Button> : null}
          <Button variant="outline" disabled={busy} onClick={() => void perform(async signal => { const list = await routerControl.run(assetUid, { action: 'backups' }, signal); if (!signal.aborted) setBackups(list.items as typeof backups); })}>백업 목록 조회</Button></div>
          {backups.map(backup => <div className="flex flex-wrap items-center justify-between gap-2" key={backup.id}><span className="break-all text-sm">{new Date(backup.createdAt).toLocaleString()} · {backup.id}</span>{admin ? <Button size="sm" variant="outline" disabled={busy} onClick={() => prepare({ action: 'restore', backupId: backup.id, expectedRevision: revision }, '백업 설정 복원')}>복원 검증</Button> : null}</div>)}
        </CardContent></Card></div></TabsContent>
        <TabsContent value="history"><Card><CardHeader><CardTitle>작업과 감사 이력</CardTitle><CardDescription>Agent 전달 완료와 Router의 실제 적용 결과를 구분합니다. committed는 적용 완료, rolled_back은 복구 완료입니다.</CardDescription></CardHeader><CardContent className="flex flex-col gap-4">
          <div className="flex flex-wrap gap-2"><Button variant="outline" disabled={busy} onClick={() => void perform(async signal => { const result = await routerControl.run(assetUid, { action: 'jobs' }, signal); if (!signal.aborted) setJobs((result.items ?? []) as RouterJob[]); })}>Router 작업 새로고침</Button><Button variant="outline" disabled={busy} onClick={() => void perform(async signal => { const result = await routerControl.run(assetUid, { action: 'audit' }, signal); if (!signal.aborted) setAudit(result); })}>장비 감사 조회</Button></div>
          <Table><TableHeader><TableRow><TableHead>Router 작업</TableHead><TableHead>종류</TableHead><TableHead>적용 상태</TableHead><TableHead>상세</TableHead></TableRow></TableHeader><TableBody>{jobs.map(job => <TableRow key={job.id}><TableCell className="break-all">{job.id}</TableCell><TableCell>{job.request?.kind}</TableCell><TableCell>{job.status}</TableCell><TableCell>{job.error}</TableCell></TableRow>)}</TableBody></Table>
          <Table><TableHeader><TableRow><TableHead>요청 시각</TableHead><TableHead>사용자</TableHead><TableHead>작업</TableHead><TableHead>Agent 처리</TableHead><TableHead>상세</TableHead></TableRow></TableHeader><TableBody>{history.map(item => <TableRow key={item.id}><TableCell>{new Date(item.createdAt).toLocaleString()}</TableCell><TableCell>{item.createdBy}</TableCell><TableCell>{item.action}</TableCell><TableCell>{item.status}</TableCell><TableCell><details><summary>결과</summary><p className="break-all">{item.id}</p><pre className="max-h-48 max-w-lg overflow-auto whitespace-pre-wrap break-all text-xs">{item.errorMessage || item.resultJson || '대기 중'}</pre></details></TableCell></TableRow>)}</TableBody></Table>
          {audit ? <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all text-xs">{print(audit)}</pre> : null}
        </CardContent></Card></TabsContent>
      </Tabs>
    </> : null}
  </div>;
}
