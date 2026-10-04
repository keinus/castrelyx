import { Activity, ChevronLeft, ChevronRight, FileText, RefreshCw, Search, ShieldAlert, SlidersHorizontal } from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ViewFrame } from '../components/ViewFrame';
import { ViewFeedback } from '../components/ViewFeedback';
import { api } from '../lib/api';
import { useRemoteData } from '../lib/useRemoteData';
import type { Asset, HuntAssessment, HuntCategory, HuntFinding, HuntLog, HuntLogRef } from '../lib/types';
import { AgentLogsView } from './AgentLogsView';
import './security-hunt.css';

const categories: [HuntCategory, string, string][] = [
  ['THREAT', '위협 의심', '보안 탐지 또는 연관 징후'],
  ['OPERATIONAL', '운영 문제', '서비스·연결·설정 문제'],
  ['REVIEW', '확인 필요', '추가 맥락이 필요한 활동'],
  ['OBSERVATION', '통신·활동 기록', '활동 설명 · 안전 보장 아님'],
  ['UNKNOWN', '판단 보류', '지원 규칙·근거 부족']
];
const label = (value: HuntCategory) => categories.find(([key]) => key === value)?.[1] ?? value;
const confidence = { HIGH: '직접 로그 근거', MEDIUM: '맥락 확인 필요', LOW: '근거 제한적' };

export function SecurityHuntView({ assets = [] }: { assets?: Asset[] }) {
  const [mode, setMode] = useState('analysis');
  return <div className="hunt-workspace">
    <div className="hunt-mode-bar" aria-label="Hunt 조회 방식">
      <button type="button" aria-pressed={mode === 'analysis'} onClick={() => setMode('analysis')}><ShieldAlert size={16} />분석·조사</button>
      <button type="button" aria-pressed={mode === 'raw'} onClick={() => setMode('raw')}><FileText size={16} />전체 기간 원문 검색</button>
    </div>
    {mode === 'analysis' ? <HuntAnalysis assets={assets} /> : <AgentLogsView assets={assets} />}
  </div>;
}

function HuntAnalysis({ assets }: { assets: Asset[] }) {
  const [range, setRange] = useState('1h');
  const [assetUid, setAssetUid] = useState('');
  const [family, setFamily] = useState('ALL');
  const [severity, setSeverity] = useState('ALL');
  const [query, setQuery] = useState('');
  const [serverQuery, setServerQuery] = useState('');
  const [category, setCategory] = useState('ALL');
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<HuntLog | null>(null);
  const [finding, setFinding] = useState<HuntFinding | null>(null);
  const detailRef = useRef<HTMLElement>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  useEffect(() => {
    if (selected || finding) {
      detailRef.current?.scrollIntoView({ block: 'start' });
      detailRef.current?.focus({ preventScroll: true });
    }
  }, [selected, finding]);
  useEffect(() => { const timer = window.setTimeout(() => setServerQuery(query.trim()), 350); return () => window.clearTimeout(timer); }, [query]);
  const requestKey = JSON.stringify([range, assetUid, family, severity, serverQuery, category, offset]);
  const fetchHunt = useCallback(async () => ({ key: requestKey, page: await api.securityHunt({ range, assetUid, family, severity, query: serverQuery, category, offset }) }),
    [requestKey, range, assetUid, family, severity, serverQuery, category, offset]);
  const { data, loading, error, reload } = useRemoteData(fetchHunt, '보안 로그 분석을 불러오지 못했습니다.');
  const page = data?.key === requestKey ? data.page : null;
  const busy = loading || query.trim() !== serverQuery;
  const assetOptions = useMemo(() => [...new Set([...assets.map(a => a.assetUid), ...(page?.items.map(e => e.assetUid) ?? []), assetUid].filter(Boolean))].sort(), [assets, page, assetUid]);
  function change(setter: (value: string) => void, value: string) { setter(value); setOffset(0); setSelected(null); setFinding(null); }
  function inspect(id: string) {
    change(setQuery, id); setCategory('ALL'); setFamily('ALL'); setSeverity('ALL');
    searchRef.current?.scrollIntoView({ block: 'center' });
    searchRef.current?.focus({ preventScroll: true });
  }
  function closeDetail() {
    setSelected(null); setFinding(null);
    triggerRef.current?.focus({ preventScroll: true });
    triggerRef.current?.scrollIntoView({ block: 'center' });
  }
  return <ViewFrame title="Security Log Hunt" subtitle="로그의 의미를 읽고, 반복되는 징후를 묶어 조사합니다. 분류와 판단 근거를 함께 확인하세요."
    actions={<>
      <select aria-label="Hunt 조회 기간" value={range} onChange={e => change(setRange, e.target.value)}>
        <option value="15m">최근 15분</option><option value="30m">최근 30분</option><option value="1h">최근 1시간</option><option value="6h">최근 6시간</option><option value="24h">최근 24시간</option>
      </select>
      <button type="button" aria-label="Hunt 분석 새로고침" disabled={busy} onClick={() => { setSelected(null); setFinding(null); void reload(); }}><RefreshCw size={16} />새로고침</button>
    </>}>
    <ViewFeedback loading={busy} error={error} hasData={!!page} />
    <div className="hunt-toolbar">
      <label className="hunt-search"><Search size={16} /><input ref={searchRef} aria-label="Hunt 로그 검색" placeholder="IP, 도메인, UID, 메시지, 해석 검색" maxLength={256} value={query} onChange={e => change(setQuery, e.target.value)} /></label>
      <select aria-label="Hunt 자산" value={assetUid} onChange={e => change(setAssetUid, e.target.value)}><option value="">전체 자산</option>{assetOptions.map(uid => <option key={uid} value={uid}>{uid}</option>)}</select>
      <select aria-label="Hunt 로그 종류" value={family} onChange={e => change(setFamily, e.target.value)}><option value="ALL">전체 종류</option><option value="host">호스트 로그</option><option value="zeek">Zeek</option><option value="suricata">Suricata</option><option value="security">Router 보안</option><option value="audit">Router 감사</option></select>
      <select aria-label="Hunt 원천 심각도" value={severity} onChange={e => change(setSeverity, e.target.value)}><option value="ALL">전체 원천 등급</option>{['INFO', 'WARNING', 'ERROR', 'CRITICAL'].map(s => <option key={s}>{s}</option>)}</select>
    </div>
    <div className="hunt-summary" aria-label="로그 해석별 분류">
      {categories.map(([key, name, help]) => <button type="button" key={key} className={`hunt-summary-card hunt-${key.toLowerCase()}`} aria-pressed={category === key} onClick={() => change(setCategory, category === key ? 'ALL' : key)}>
        <span>{name}</span><strong>{page ? page.summary[key].toLocaleString() : '—'}</strong><small>{help}</small>
      </button>)}
    </div>
    <div className={`hunt-coverage ${page?.coverage.truncated ? 'hunt-coverage-limited' : ''}`} role="status">
      <Activity size={17} /><div>{page ? <>
        <strong>{page.coverage.truncated ? '일부 로그만 분석됨' : '조회 범위 분석 완료'} · {page.coverage.analyzed.toLocaleString()}건</strong>
        <span>관측 {date(page.coverage.oldest)} ~ {date(page.coverage.newest)} · 현재 조회 시점의 규칙 기반 분석</span>
        {page.coverage.truncated && <span>최신 {page.coverage.limit.toLocaleString()}건까지만 분석합니다. 이전 징후는 누락될 수 있으니 기간·자산을 좁히거나 전체 기간 원문 검색을 사용하세요.</span>}
      </> : <span>{error ? '분석 결과를 확인할 수 없습니다.' : '저장된 로그를 분석하고 있습니다.'}</span>}
      <small>검색·종류·원천 등급·분류는 분석 후 표시 필터입니다. 0건은 위협이 없다는 보장이 아닙니다.</small></div>
    </div>
    <section className="data-panel hunt-cases">
      <div className="panel-heading"><div><h3>연관 분석 · 우선 조사</h3><p>같은 원본과 시간 구간에서 반복되는 기록을 묶었습니다.</p></div><span>{page ? `${page.findingCount}개 조사 항목` : '—'}</span></div>
      {!!page?.findings.length && <div className="hunt-case-list">{page.findings.map(item => <button type="button" key={item.id} className={`hunt-case hunt-${item.assessment.category.toLowerCase()}`} onClick={event => { triggerRef.current = event.currentTarget; setFinding(item); setSelected(null); }} aria-pressed={finding?.id === item.id}>
        <span className="hunt-case-top"><AssessmentBadge assessment={item.assessment} /><small>{item.assetUid} · {item.count}건</small></span>
        <strong>{item.assessment.title}</strong><span>{item.assessment.explanation}</span><small>{date(item.firstSeen)} ~ {date(item.lastSeen)} · 근거·시간 흐름 보기</small>
      </button>)}</div>}
      {page && !page.findings.length && <p className="cc-help">현재 분석 범위와 표시 필터에서 연관 규칙에 일치한 항목이 없습니다. 개별 로그의 해석과 판단 보류 기록도 확인하세요.</p>}
      {page && page.findingCount > page.findings.length && <p className="cc-help">우선순위 상위 {page.findings.length}개를 표시합니다. 자산·기간·검색으로 범위를 좁히세요.</p>}
    </section>
    {finding && <section className="data-panel hunt-detail" aria-label="연관 조사 상세" ref={detailRef} tabIndex={-1}>
      <div className="panel-heading"><h3>{finding.assessment.title}</h3><button type="button" onClick={closeDetail}>조사 상세 닫기</button></div>
      <AssessmentDetail assessment={finding.assessment} />
      <Timeline rows={finding.timeline} total={finding.count} onInspect={inspect} title="근거 로그 시간 흐름" />
    </section>}
    <section className="data-panel">
      <div className="panel-heading"><div><h3>해석된 로그</h3><p>조사 우선순위 → 최신순 · 원천 심각도와 위협 분류는 별개입니다.</p></div>
        <select aria-label="Hunt 판단 분류" value={category} onChange={e => change(setCategory, e.target.value)}><option value="ALL">전체 분류</option>{categories.map(([key, name]) => <option key={key} value={key}>{name}</option>)}</select>
      </div>
      {page?.items.length ? <div className="hunt-log-list">{page.items.map(log => <button type="button" className="hunt-log-row" key={log.id} aria-pressed={selected?.id === log.id} onClick={event => { triggerRef.current = event.currentTarget; setSelected(log); setFinding(null); }}>
        <span className="hunt-log-verdict"><AssessmentBadge assessment={log.assessment} /><small>{date(log.observedAt)}</small><small>{log.assetUid} · 원천 {log.event.severity ?? '미제공'}</small></span>
        <span className="hunt-log-meaning"><strong>{log.assessment.title}</strong><span>{log.interpretation.explanation}</span><small>{flow(log)}{log.relatedCount ? ` · 연결 관련 ${log.relatedCount}건` : ''}</small></span>
        <span className="hunt-log-source"><code>{log.eventType}</code><small>{log.event.sourceType === 'SECURITY_ROUTER' ? 'Security Router' : 'Agent'} · {log.event.sourceName ?? log.event.sourceId}</small><span>근거 보기 →</span></span>
      </button>)}</div> : <p className="cc-help">{busy ? '분석 중입니다.' : error ? '조회 오류를 확인해 주세요.' : '조건에 맞는 로그가 없습니다.'}</p>}
      <div className="hunt-pagination"><span>{page ? `${page.total.toLocaleString()}건 중 ${page.total ? offset + 1 : 0}–${offset + page.items.length} 표시` : '—'}</span>
        <button type="button" disabled={busy || offset === 0} onClick={() => { setOffset(Math.max(0, offset - 50)); setSelected(null); }}><ChevronLeft size={16} />이전</button>
        <button type="button" disabled={busy || !page?.hasMore} onClick={() => { setOffset(page?.nextOffset ?? offset); setSelected(null); }}>다음<ChevronRight size={16} /></button>
      </div>
    </section>
    {selected && <section className="data-panel hunt-detail" aria-label="로그 해석 상세" ref={detailRef} tabIndex={-1}>
      <div className="panel-heading"><div><h3>{selected.assessment.title}</h3><p>{selected.assetUid} · {selected.eventType} · {date(selected.observedAt)}</p></div><button type="button" onClick={closeDetail}>로그 상세 닫기</button></div>
      <AssessmentDetail assessment={selected.assessment} />
      {selected.interpretation.ruleId !== selected.assessment.ruleId && <div className="hunt-original-meaning"><h4>개별 로그의 의미</h4><p>{selected.interpretation.explanation}</p><p>{selected.interpretation.caveat}</p></div>}
      <div className="hunt-connection"><strong>{flow(selected)}</strong>{selected.connectionUid && <><span>Zeek UID: {selected.connectionUid}</span><button type="button" onClick={() => inspect(selected.connectionUid)}>이 연결 로그 검색</button></>}</div>
      {selected.relatedCount > 0 && <Timeline rows={selected.related} total={selected.relatedCount} onInspect={inspect} title="같은 연결의 관련 로그" />}
      <details className="hunt-raw"><summary>원본 메시지·구조화 필드</summary><pre>{selected.event.message}</pre><pre>{JSON.stringify(selected.event.raw ?? selected.event, null, 2)}</pre></details>
    </section>}
    <details className="data-panel hunt-rulebook"><summary><SlidersHorizontal size={16} />분석 규칙과 한계</summary>
      <p>자동 해석은 조사 보조입니다. “위협 의심”은 침해 확정이 아니며, 통신·활동 기록 분류는 안전 판정이 아닙니다. 외부 위협 인텔리전스 조회나 자동 차단은 수행하지 않습니다.</p>
      <p>조사 우선순위: P1 즉시 확인 · P2 우선 조사 · P3 맥락 확인 · P4 참고 기록. 대응 조치나 침해 여부를 확정하는 등급은 아닙니다.</p>
      <p>근거 수준은 분류를 뒷받침하는 데이터의 수준이며 공격 확률이 아닙니다. 임계값은 초기 운영 기준으로, 환경에 맞는 후속 조정이 필요합니다. 각 원본 그룹에서 가장 큰 5분 구간을 대표로 표시하며 연속 감시·영구 사건 저장은 하지 않습니다.</p>
      {page?.rules.map(rule => <div key={rule.name}><strong>{rule.name}</strong><span>{rule.condition}</span></div>)}
      <p>시각은 수집 로그의 관측 시각을 사용합니다. 시계 오차·누락·분석 건수 제한은 연관 결과에 영향을 줍니다. 관련 로그는 같은 자산·수집원·Zeek UID 범위입니다.</p>
      <p><a href="https://docs.zeek.org/en/current/reference/logs/weird-and-notice.html" target="_blank" rel="noreferrer">Zeek notice·weird 해석 근거</a> · <a href="https://docs.zeek.org/en/current/scripts/base/protocols/conn/main.zeek.html" target="_blank" rel="noreferrer">연결 상태 정의</a> · {page?.ruleVersion}</p>
    </details>
  </ViewFrame>;
}

function AssessmentBadge({ assessment }: { assessment: HuntAssessment }) {
  return <span className={`hunt-badge hunt-${assessment.category.toLowerCase()}`}><b>{assessment.priority}</b>{label(assessment.category)}</span>;
}
function AssessmentDetail({ assessment }: { assessment: HuntAssessment }) {
  return <div className="hunt-assessment"><div className="hunt-explanation"><AssessmentBadge assessment={assessment} /><p>{assessment.explanation}</p><small>분류 근거 수준: {confidence[assessment.evidenceLevel]} · 규칙 {assessment.ruleId}</small></div>
    <div className="hunt-evidence"><div><h4>판단 근거</h4><ul>{assessment.evidence.map((e, i) => <li key={`${i}-${e}`}>{e}</li>)}</ul></div><div><h4>다음 확인</h4><ol>{assessment.nextSteps.map(step => <li key={step}>{step}</li>)}</ol></div></div>
    <p className="hunt-caveat"><strong>아직 알 수 없는 점</strong>{assessment.caveat}</p>
  </div>;
}
function Timeline({ rows, total, onInspect, title }: { rows: HuntLogRef[]; total: number; onInspect: (id: string) => void; title: string }) {
  return <div className="hunt-timeline"><h4>{title} · {total}건</h4><ol>{rows.map(row => <li key={row.id}><div><time>{date(row.observedAt)}</time><strong>{row.eventType}</strong><span>{flow(row)}</span><button type="button" onClick={() => onInspect(row.id)}>이 로그 조사</button></div><p>{row.message}</p></li>)}</ol>{total > rows.length && <p>앞선 {rows.length}건을 표시합니다. 검색으로 범위를 좁혀 나머지 기록을 확인하세요.</p>}</div>;
}
function flow(log: HuntLogRef) { return log.sourceIp || log.targetIp ? `${log.sourceIp || '원본 미제공'} → ${log.targetIp || log.assetUid}${log.targetPort ? ` : ${log.targetPort}` : ''}${log.protocol ? ` · ${log.protocol.toUpperCase()}` : ''}` : '통신 주소 미제공 · 원본 기록 확인'; }
function date(value: string) { if (!value) return '기록 없음'; const parsed = new Date(value); return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString(); }
