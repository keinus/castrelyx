import { ExternalLink, RefreshCw } from 'lucide-react';
import { api } from '../lib/api';
import { ViewFrame } from '../components/ViewFrame';
import { ViewFeedback } from '../components/ViewFeedback';
import { MetricCards } from '../components/MetricCards';
import { useRemoteData } from '../lib/useRemoteData';

export function LogparserView() {
  const config = useRemoteData(api.logparser, 'LogParser 설정을 불러오지 못했습니다.');
  const pipeline = useRemoteData(api.logparserStatus, '파이프라인 상태를 불러오지 못했습니다.');
  const links = useRemoteData(api.logparserLinks, 'LogParser 링크를 불러오지 못했습니다.');
  const status = pipeline.data;
  const loading = config.loading || pipeline.loading || links.loading;
  return <ViewFrame title="LogParser" subtitle="수집 파이프라인의 실행 상태와 처리량을 확인하고 원본 콘솔로 이동합니다." actions={
    <button type="button" aria-label="LogParser 새로고침" disabled={loading} onClick={() => { void config.reload(); void pipeline.reload(); void links.reload(); }}><RefreshCw size={16} /></button>
  }>
    <ViewFeedback loading={config.loading} error={config.error} hasData={!!config.data} />
    <ViewFeedback loading={pipeline.loading} error={pipeline.error} hasData={!!status} />
    <MetricCards items={[
      ['입력 어댑터', metric(status?.inputAdapterCount)], ['파서', metric(status?.parserCount)],
      ['변환 단계', metric(status?.transformCount)], ['출력 어댑터', metric(status?.outputAdapterCount)]
    ]} />
    <div className="split-grid">
      <section className="data-panel"><div className="panel-heading"><h3>Pipeline status</h3><span className={`badge ${status?.status === 'RUNNING' ? 'success' : status?.status === 'ERROR' ? 'critical' : 'neutral'}`}>{status?.status ?? '미확인'}</span></div>
        <div className="cc-status-grid"><div><span>대기 메시지</span><strong>{metric(status?.queueSize)}</strong></div><div><span>큐 용량</span><strong>{metric(status?.queueCapacity)}</strong></div><div><span>처리량 / 초</span><strong>{metric(status?.throughput)}</strong></div></div>
        {status?.configured === false && <p className="cc-help">LogParser 서비스 주소가 설정되지 않았습니다. 설정에서 연동을 구성하세요.</p>}
      </section>
      <section className="data-panel"><h3>연결 설정</h3><dl className="kv-list">
        <div><dt>연동 사용</dt><dd>{config.data ? config.data.enabled ? 'ENABLED' : 'DISABLED' : '미확인'}</dd></div>
        <div><dt>Endpoint</dt><dd>{config.data?.baseUrl || '미설정'}</dd></div>
      </dl><p className="cc-help">연동 사용 설정과 파이프라인의 실제 실행 상태는 별도로 표시됩니다.</p></section>
    </div>
    <section className="data-panel"><h3>LogParser 콘솔</h3>
      <ViewFeedback loading={links.loading} error={links.error} hasData={!!links.data} />
      <div className="cc-link-grid">{links.data?.filter((link) => /^https?:\/\//i.test(link.url)).map((link) => <a className="button-link" href={link.url} key={link.url} target="_blank" rel="noreferrer"><span>{link.label}</span><ExternalLink size={16} /></a>)}</div>
      {links.data?.length === 0 && <p className="cc-empty">설정된 콘솔 링크가 없습니다.</p>}
    </section>
  </ViewFrame>;
}

function metric(value?: number) { return value == null ? '—' : value.toLocaleString('ko-KR', { maximumFractionDigits: 1 }); }
