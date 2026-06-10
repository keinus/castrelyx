import { ExternalLink, RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { DeepLink, IntegrationConfig, LogparserStatus } from '../lib/types';
import { ViewFrame } from '../components/ViewFrame';

export function LogparserView() {
  const [config, setConfig] = useState<IntegrationConfig | null>(null);
  const [status, setStatus] = useState<LogparserStatus | null>(null);
  const [links, setLinks] = useState<DeepLink[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;

    async function load() {
      setLoading(true);
      setError(null);
      const [configResult, statusResult, linksResult] = await Promise.allSettled([
        api.logparser(),
        api.logparserStatus(),
        api.logparserLinks()
      ]);

      if (!mounted) {
        return;
      }
      if (configResult.status === 'fulfilled') {
        setConfig(configResult.value);
      } else {
        setError('LogParser 설정을 불러오지 못했습니다.');
      }
      setStatus(statusResult.status === 'fulfilled' ? statusResult.value : null);
      setLinks(linksResult.status === 'fulfilled' ? linksResult.value : []);
      setLoading(false);
    }

    load();
    return () => {
      mounted = false;
    };
  }, []);

  const statusRows = status
    ? Object.entries(status).map(([key, value]) => [key, formatValue(value)] as [string, string])
    : [];

  return (
    <ViewFrame title="LogParser">
      {error && <div className="notice error">{error}</div>}
      <div className="split-grid">
        <section className="data-panel">
          <h3>연결 설정</h3>
          <dl className="kv-list">
            <div><dt>상태</dt><dd>{loading ? '확인 중' : config?.enabled ? 'ENABLED' : 'DISABLED'}</dd></div>
            <div><dt>Endpoint</dt><dd>{config?.baseUrl || '-'}</dd></div>
          </dl>
        </section>
        <section className="data-panel">
          <h3>Pipeline status</h3>
          <DataList rows={statusRows} empty="상태 정보 없음" />
        </section>
        <section className="data-panel">
          <h3>Deep links</h3>
          {links.length === 0 ? (
            <p>사용 가능한 링크 없음</p>
          ) : (
            <div className="link-list">
              {links.map((link) => (
                <a className="button-link" href={link.url} key={link.url} target="_blank" rel="noreferrer">
                  <ExternalLink size={16} />
                  {link.label}
                </a>
              ))}
            </div>
          )}
        </section>
        <section className="data-panel">
          <h3>SNMP input adapter</h3>
          <p>Manager SNMP 자산과 LogParser 입력 어댑터를 같은 구성 경계에서 관리합니다.</p>
          <button type="button" disabled>
            <RefreshCw size={16} />
            동기화 대기
          </button>
        </section>
      </div>
    </ViewFrame>
  );
}

function DataList({ rows, empty }: { rows: [string, string][]; empty: string }) {
  if (rows.length === 0) {
    return <p>{empty}</p>;
  }
  return (
    <ul className="compact-list">
      {rows.map(([primary, secondary]) => (
        <li key={`${primary}-${secondary}`}>
          <strong>{primary}</strong>
          <span>{secondary}</span>
        </li>
      ))}
    </ul>
  );
}

function formatValue(value: unknown): string {
  if (value === null || value === undefined) {
    return '-';
  }
  if (typeof value === 'object') {
    return JSON.stringify(value);
  }
  return String(value);
}
