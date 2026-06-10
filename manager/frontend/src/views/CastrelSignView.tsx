import { KeyRound, RefreshCw } from 'lucide-react';
import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type {
  CastrelSignAgent,
  CastrelSignCertificate,
  CastrelSignToken,
  IntegrationConfig,
  Role
} from '../lib/types';
import { canMutate } from '../lib/uiModel';
import { ViewFrame } from '../components/ViewFrame';

type CastrelSignViewProps = {
  role: Role;
};

export function CastrelSignView({ role }: CastrelSignViewProps) {
  const [config, setConfig] = useState<IntegrationConfig | null>(null);
  const [tokens, setTokens] = useState<CastrelSignToken[]>([]);
  const [agents, setAgents] = useState<CastrelSignAgent[]>([]);
  const [certificates, setCertificates] = useState<CastrelSignCertificate[]>([]);
  const [issuedToken, setIssuedToken] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [issuing, setIssuing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let mounted = true;

    async function load() {
      setLoading(true);
      setError(null);
      const [configResult, tokensResult, agentsResult, certificatesResult] = await Promise.allSettled([
        api.castrelSign(),
        api.castrelSignTokens(),
        api.castrelSignAgents(),
        api.castrelSignCertificates()
      ]);

      if (!mounted) {
        return;
      }
      if (configResult.status === 'fulfilled') {
        setConfig(configResult.value);
      } else {
        setError('CastrelSign 설정을 불러오지 못했습니다.');
      }
      setTokens(tokensResult.status === 'fulfilled' ? tokensResult.value : []);
      setAgents(agentsResult.status === 'fulfilled' ? agentsResult.value : []);
      setCertificates(certificatesResult.status === 'fulfilled' ? certificatesResult.value : []);
      setLoading(false);
    }

    load();
    return () => {
      mounted = false;
    };
  }, []);

  async function issueToken() {
    setIssuing(true);
    setIssuedToken(null);
    setError(null);
    try {
      const token = await api.createCastrelSignToken({ description: 'Manager issued token' });
      setTokens((current) => [token, ...current]);
      setIssuedToken(readString(token, 'token') ?? readString(token, 'value') ?? `token-${token.id ?? 'created'}`);
    } catch {
      setError('CastrelSign 토큰 갱신에 실패했습니다.');
    } finally {
      setIssuing(false);
    }
  }

  return (
    <ViewFrame
      title="CastrelSign"
      actions={canMutate(role, 'integration:update-secret') && (
        <button type="button" onClick={issueToken} disabled={issuing}>
          {issuing ? <RefreshCw size={16} /> : <KeyRound size={16} />}
          토큰 갱신
        </button>
      )}
    >
      {error && <div className="notice error">{error}</div>}
      {issuedToken && (
        <section className="data-panel token-output">
          <h3>신규 enrollment token</h3>
          <code>{issuedToken}</code>
        </section>
      )}
      <div className="split-grid">
        <section className="data-panel">
          <h3>연결 설정</h3>
          <dl className="kv-list">
            <div><dt>상태</dt><dd>{loading ? '확인 중' : config?.enabled ? 'ENABLED' : 'DISABLED'}</dd></div>
            <div><dt>Endpoint</dt><dd>{config?.baseUrl || '-'}</dd></div>
            <div><dt>Admin token</dt><dd>{config?.secret.configured ? config.secret.masked : '미설정'}</dd></div>
          </dl>
        </section>
        <section className="data-panel">
          <h3>Enrollment tokens</h3>
          <DataList
            empty="등록된 토큰 없음"
            rows={tokens.map((token) => [
              readString(token, 'description') ?? `Token ${token.id ?? '-'}`,
              readString(token, 'revoked') ?? 'ACTIVE'
            ])}
          />
        </section>
        <section className="data-panel">
          <h3>Agents</h3>
          <DataList
            empty="등록된 Agent 없음"
            rows={agents.map((agent) => [
              readString(agent, 'agentId') ?? readString(agent, 'id') ?? '-',
              readString(agent, 'hostname') ?? readString(agent, 'status') ?? '-'
            ])}
          />
        </section>
        <section className="data-panel">
          <h3>Certificates</h3>
          <DataList
            empty="발급된 인증서 없음"
            rows={certificates.map((certificate) => [
              readString(certificate, 'serialNumber') ?? readString(certificate, 'serial') ?? '-',
              readString(certificate, 'subject') ?? readString(certificate, 'status') ?? '-'
            ])}
          />
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

function readString(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key];
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  return String(value);
}
