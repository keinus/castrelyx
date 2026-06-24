import { Ban, Download, KeyRound, PackagePlus, RefreshCw, RotateCcw, ShieldCheck, ShieldAlert, UploadCloud } from 'lucide-react';
import { type FormEvent, type ReactNode, useEffect, useMemo, useState } from 'react';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type {
  CastrelSignAgent,
  CastrelSignAuditEvent,
  CastrelSignCertificate,
  CastrelSignToken,
  AgentRelease,
  AgentUpdateAttempt,
  AgentUpdatePolicy,
  IntegrationConfig,
  Role
} from '../lib/types';
import { canMutate } from '../lib/uiModel';

type CastrelSignViewProps = {
  role: Role;
};

type PackageForm = {
  agentId: string;
  tenantId: string;
  ttlSeconds: number;
};

type ReleaseForm = {
  version: string;
  os: string;
  arch: string;
  channel: string;
  publish: boolean;
  artifact?: File;
};

type AgentRow = CastrelSignAgent & {
  lifecycleStatus: string;
  activeCertificate?: CastrelSignCertificate;
  pendingToken?: CastrelSignToken;
};

const DEFAULT_PACKAGE_FORM: PackageForm = {
  agentId: '',
  tenantId: 'default',
  ttlSeconds: 3600
};

const DEFAULT_RELEASE_FORM: ReleaseForm = {
  version: '',
  os: 'linux',
  arch: 'amd64',
  channel: 'stable',
  publish: true
};

export function CastrelSignView({ role }: CastrelSignViewProps) {
  const [config, setConfig] = useState<IntegrationConfig | null>(null);
  const [tokens, setTokens] = useState<CastrelSignToken[]>([]);
  const [agents, setAgents] = useState<CastrelSignAgent[]>([]);
  const [certificates, setCertificates] = useState<CastrelSignCertificate[]>([]);
  const [auditEvents, setAuditEvents] = useState<CastrelSignAuditEvent[]>([]);
  const [releases, setReleases] = useState<AgentRelease[]>([]);
  const [policies, setPolicies] = useState<AgentUpdatePolicy[]>([]);
  const [attempts, setAttempts] = useState<AgentUpdateAttempt[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState('');
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [packageOpen, setPackageOpen] = useState(false);
  const [packageForm, setPackageForm] = useState<PackageForm>(DEFAULT_PACKAGE_FORM);
  const [releaseForm, setReleaseForm] = useState<ReleaseForm>(DEFAULT_RELEASE_FORM);
  const [confirmAgentId, setConfirmAgentId] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const canAdminister = canMutate(role, 'integration:update-secret');

  async function load() {
    setLoading(true);
    setError(null);
    const [configResult, tokensResult, agentsResult, certificatesResult, auditResult, releasesResult, policiesResult, attemptsResult] = await Promise.allSettled([
      api.castrelSign(),
      api.castrelSignTokens(),
      api.castrelSignAgents(),
      api.castrelSignCertificates(),
      api.castrelSignAuditEvents(),
      api.agentReleases(),
      api.agentUpdatePolicies(),
      api.agentUpdateAttempts()
    ]);

    if (configResult.status === 'fulfilled') {
      setConfig(configResult.value);
    } else {
      setError('CastrelSign 설정을 불러오지 못했습니다.');
    }
    const nextTokens = tokensResult.status === 'fulfilled' ? tokensResult.value : [];
    const nextAgents = agentsResult.status === 'fulfilled' ? agentsResult.value : [];
    const nextCertificates = certificatesResult.status === 'fulfilled' ? certificatesResult.value : [];
    setTokens(nextTokens);
    setAgents(nextAgents);
    setCertificates(nextCertificates);
    setAuditEvents(auditResult.status === 'fulfilled' ? auditResult.value : []);
    setReleases(releasesResult.status === 'fulfilled' ? releasesResult.value : []);
    setPolicies(policiesResult.status === 'fulfilled' ? policiesResult.value : []);
    setAttempts(attemptsResult.status === 'fulfilled' ? attemptsResult.value : []);
    setSelectedAgentId((current) => {
      if (current && nextAgents.some((agent) => agent.agentId === current)) {
        return current;
      }
      return nextAgents[0]?.agentId ?? nextTokens.find((token) => token.agentId)?.agentId ?? '';
    });
    setLoading(false);
  }

  useEffect(() => {
    void load();
  }, []);

  const activeCertificates = useMemo(() => {
    const byAgent = new Map<string, CastrelSignCertificate>();
    for (const certificate of certificates) {
      if (certificate.agentId && certificate.status === 'ACTIVE' && !byAgent.has(certificate.agentId)) {
        byAgent.set(certificate.agentId, certificate);
      }
    }
    return byAgent;
  }, [certificates]);

  const agentRows = useMemo<AgentRow[]>(() => {
    const rows = new Map<string, AgentRow>();
    for (const agent of agents) {
      rows.set(agent.agentId, {
        ...agent,
        lifecycleStatus: agent.status ?? 'UNKNOWN',
        activeCertificate: activeCertificates.get(agent.agentId)
      });
    }
    for (const token of tokens) {
      if (!token.agentId || rows.has(token.agentId) || token.revokedAt || (token.usedCount ?? 0) >= (token.maxUses ?? 1)) {
        continue;
      }
      rows.set(token.agentId, {
        agentId: token.agentId,
        status: 'PENDING',
        lifecycleStatus: 'PENDING',
        pendingToken: token
      });
    }
    return Array.from(rows.values());
  }, [activeCertificates, agents, tokens]);

  const selectedAgent = agentRows.find((agent) => agent.agentId === selectedAgentId) ?? agentRows[0];
  const selectedTokens = tokens.filter((token) => token.agentId === selectedAgent?.agentId);
  const selectedCertificates = certificates.filter((certificate) => certificate.agentId === selectedAgent?.agentId);
  const selectedAudit = auditEvents.filter((event) => event.agentId === selectedAgent?.agentId);
  const selectedAttempts = attempts.filter((attempt) => attempt.agentId === selectedAgent?.agentId);
  const globalPolicy = policies.find((policy) => !policy.agentId) ?? { enabled: true, channel: 'stable' };
  const activeReleases = releases.filter((release) => release.status === 'ACTIVE');
  const latestActiveRelease = activeReleases[0];
  const publishedTarget = globalPolicy.targetVersion || latestActiveRelease?.version || '-';

  function openPackageModal(agentId = '') {
    setPackageForm({
      ...DEFAULT_PACKAGE_FORM,
      agentId
    });
    setPackageOpen(true);
    setError(null);
    setNotice(null);
  }

  async function submitPackage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setWorking(true);
    setError(null);
    setNotice(null);
    try {
      const payload: { tenantId: string; ttlSeconds: number; agentId?: string } = {
        tenantId: packageForm.tenantId.trim() || 'default',
        ttlSeconds: packageForm.ttlSeconds,
        ...(packageForm.agentId.trim() ? { agentId: packageForm.agentId.trim() } : {})
      };
      const blob = await api.createCastrelSignEnrollmentPackage(payload);
      downloadBlob(blob, `castrelsign-${safeFileName(packageForm.agentId.trim() || 'hostname-auto')}-enrollment.zip`);
      setPackageOpen(false);
      payload.agentId = packageForm.agentId.trim() || 'hostname auto';
      setNotice(`${payload.agentId} enrollment package 다운로드를 시작했습니다.`);
      await load();
    } catch {
      setError('enrollment package 생성에 실패했습니다.');
    } finally {
      setWorking(false);
    }
  }

  async function blockSelectedAgent() {
    if (!selectedAgent || confirmAgentId !== selectedAgent.agentId) {
      return;
    }
    await runAgentAction(async () => {
      await api.blockCastrelSignAgent(selectedAgent.agentId);
      setNotice(`${selectedAgent.agentId} agent를 차단했습니다.`);
    });
  }

  async function reactivateSelectedAgent() {
    if (!selectedAgent || confirmAgentId !== selectedAgent.agentId) {
      return;
    }
    await runAgentAction(async () => {
      await api.reactivateCastrelSignAgent(selectedAgent.agentId);
      setNotice(`${selectedAgent.agentId} agent를 재활성화했습니다.`);
      openPackageModal(selectedAgent.agentId);
    });
  }

  async function revokeToken(token: CastrelSignToken) {
    if (!token.id) {
      return;
    }
    await runAgentAction(async () => {
      await api.revokeCastrelSignToken(token.id);
      setNotice(`Token ${token.id}을 폐기했습니다.`);
    });
  }

  async function submitRelease(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!releaseForm.artifact) {
      setError('Agent release artifact is required.');
      return;
    }
    await runAgentAction(async () => {
      await api.createAgentRelease({
        version: releaseForm.version.trim(),
        os: releaseForm.os,
        arch: releaseForm.arch,
        channel: releaseForm.channel.trim() || 'stable',
        publish: releaseForm.publish,
        artifact: releaseForm.artifact as File
      });
      setReleaseForm(DEFAULT_RELEASE_FORM);
      setNotice(releaseForm.publish
        ? 'Agent release published. Agents will update on their next check.'
        : 'Agent release uploaded as draft.');
    });
  }

  async function updateGlobalPolicy() {
    await runAgentAction(async () => {
      await api.updateAgentPolicy({
        enabled: globalPolicy.enabled,
        channel: globalPolicy.channel || 'stable',
        targetVersion: globalPolicy.targetVersion || undefined
      });
      setNotice('Agent update policy saved.');
    });
  }

  async function activateRelease(release: AgentRelease) {
    await runAgentAction(async () => {
      await api.activateAgentRelease(release.id);
      setNotice(`${release.version} release activated.`);
    });
  }

  async function revokeRelease(release: AgentRelease) {
    await runAgentAction(async () => {
      await api.revokeAgentRelease(release.id);
      setNotice(`${release.version} release revoked.`);
    });
  }

  async function runAgentAction(action: () => Promise<void>) {
    setWorking(true);
    setError(null);
    try {
      await action();
      setConfirmAgentId('');
      await load();
    } catch {
      setError('CastrelSign 작업에 실패했습니다.');
    } finally {
      setWorking(false);
    }
  }

  return (
    <ViewFrame
      title="CastrelSign"
      actions={(
        <>
          <button className="icon-button" aria-label="새로고침" type="button" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={18} />
          </button>
          {canAdminister && (
            <button type="button" onClick={() => openPackageModal()}>
              <PackagePlus size={16} />
              새 agent 패키지
            </button>
          )}
        </>
      )}
    >
      {error && <div className="notice error">{error}</div>}
      {notice && <div className="notice">{notice}</div>}

      <section className="castrelsign-summary">
        <SummaryTile label="연결" value={loading ? '확인 중' : config?.enabled ? 'ENABLED' : 'DISABLED'} icon="shield" />
        <SummaryTile label="Endpoint" value={config?.baseUrl || '-'} />
        <SummaryTile label="Active agents" value={String(agentRows.filter((agent) => agent.lifecycleStatus === 'ACTIVE').length)} />
        <SummaryTile label="Blocked" value={String(agentRows.filter((agent) => agent.lifecycleStatus === 'BLOCKED').length)} tone="danger" />
      </section>

      <div className="workbench-grid">
        <section className="data-panel">
          <div className="panel-heading">
            <h3>Agent lifecycle</h3>
            <span>{agentRows.length} agents</span>
          </div>
          <div className="table-scroll">
            <table className="lifecycle-table">
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>Status</th>
                  <th>Certificate</th>
                  <th>Last seen</th>
                </tr>
              </thead>
              <tbody>
                {agentRows.map((agent) => (
                  <tr
                    className={selectedAgent?.agentId === agent.agentId ? 'selected' : ''}
                    key={agent.agentId}
                    onClick={() => {
                      setSelectedAgentId(agent.agentId);
                      setConfirmAgentId('');
                    }}
                  >
                    <td>
                      <strong>{agent.agentId}</strong>
                      <span>{agent.hostname ?? '-'}</span>
                    </td>
                    <td><StatusPill value={agent.lifecycleStatus} /></td>
                    <td>
                      {agent.activeCertificate ? (
                        <div className="certificate-cell">
                          <span>{agent.activeCertificate.serialNumber ?? '-'}</span>
                          {isExpiringSoon(agent.activeCertificate.notAfter) && <span className="badge warning">인증서 만료 임박</span>}
                        </div>
                      ) : (
                        <span className="muted">인증서 없음</span>
                      )}
                    </td>
                    <td>{formatDate(agent.lastSeenAt)}</td>
                  </tr>
                ))}
                {agentRows.length === 0 && <tr><td colSpan={4}>등록된 agent 없음</td></tr>}
              </tbody>
            </table>
          </div>
        </section>

        <section className="data-panel agent-detail">
          <div className="panel-heading">
            <h3>Agent detail</h3>
            {selectedAgent && <StatusPill value={selectedAgent.lifecycleStatus} />}
          </div>
          {selectedAgent ? (
            <>
              <dl className="kv-list">
                <div><dt>Agent ID</dt><dd>{selectedAgent.agentId}</dd></div>
                <div><dt>Hostname</dt><dd>{selectedAgent.hostname ?? '-'}</dd></div>
                <div><dt>Version</dt><dd>{selectedAgent.version ?? '-'}</dd></div>
                <div><dt>First seen</dt><dd>{formatDate(selectedAgent.firstSeenAt)}</dd></div>
              </dl>

              {canAdminister && (
                <div className="danger-panel">
                  <label>
                    <span>확인 Agent ID</span>
                    <input value={confirmAgentId} onChange={(event) => setConfirmAgentId(event.target.value)} />
                  </label>
                  {selectedAgent.lifecycleStatus === 'BLOCKED' ? (
                    <button type="button" onClick={() => void reactivateSelectedAgent()} disabled={working || confirmAgentId !== selectedAgent.agentId}>
                      <RotateCcw size={16} />
                      재활성 + 새 패키지
                    </button>
                  ) : (
                    <button type="button" onClick={() => void blockSelectedAgent()} disabled={working || confirmAgentId !== selectedAgent.agentId}>
                      <Ban size={16} />
                      Agent 차단
                    </button>
                  )}
                </div>
              )}
            </>
          ) : (
            <p>선택된 agent 없음</p>
          )}
        </section>
      </div>

      <section className="data-panel update-panel">
        <div className="panel-heading">
          <h3>Agent updates</h3>
          <span>{releases.length} releases</span>
        </div>
        <div className="version-status-strip">
          <div>
            <span>Policy</span>
            <strong>{globalPolicy.enabled ? 'AUTO' : 'PAUSED'}</strong>
          </div>
          <div>
            <span>Channel</span>
            <strong>{globalPolicy.channel || 'stable'}</strong>
          </div>
          <div>
            <span>Target version</span>
            <strong>{publishedTarget}</strong>
          </div>
          <div>
            <span>Active releases</span>
            <strong>{activeReleases.length}</strong>
          </div>
        </div>
        <div className="update-grid">
          <form className="release-form" onSubmit={submitRelease}>
            <label>
              <span>Version</span>
              <input value={releaseForm.version} onChange={(event) => setReleaseForm((current) => ({ ...current, version: event.target.value }))} disabled={!canAdminister} />
            </label>
            <label>
              <span>OS</span>
              <select value={releaseForm.os} onChange={(event) => setReleaseForm((current) => ({ ...current, os: event.target.value }))} disabled={!canAdminister}>
                <option value="linux">linux</option>
                <option value="windows">windows</option>
              </select>
            </label>
            <label>
              <span>Arch</span>
              <select value={releaseForm.arch} onChange={(event) => setReleaseForm((current) => ({ ...current, arch: event.target.value }))} disabled={!canAdminister}>
                <option value="amd64">amd64</option>
                <option value="arm64">arm64</option>
              </select>
            </label>
            <label>
              <span>Channel</span>
              <input value={releaseForm.channel} onChange={(event) => setReleaseForm((current) => ({ ...current, channel: event.target.value }))} disabled={!canAdminister} />
            </label>
            <label className="file-input">
              <span>Artifact</span>
              <input type="file" onChange={(event) => setReleaseForm((current) => ({ ...current, artifact: event.target.files?.[0] }))} disabled={!canAdminister} />
            </label>
            <label className="inline-checkbox">
              <input
                type="checkbox"
                checked={releaseForm.publish}
                onChange={(event) => setReleaseForm((current) => ({ ...current, publish: event.target.checked }))}
                disabled={!canAdminister}
              />
              <span>Publish</span>
            </label>
            {canAdminister && (
              <button type="submit" disabled={working || !releaseForm.version || !releaseForm.artifact}>
                <UploadCloud size={16} />
                {releaseForm.publish ? 'Publish version' : 'Upload draft'}
              </button>
            )}
          </form>
          <div className="policy-panel">
            <h4>Global policy</h4>
            <label>
              <span>Enabled</span>
              <input
                type="checkbox"
                checked={globalPolicy.enabled}
                disabled={!canAdminister}
                onChange={(event) => setPolicies((current) => upsertGlobalPolicy(current, { ...globalPolicy, enabled: event.target.checked }))}
              />
            </label>
            <label>
              <span>Channel</span>
              <input
                value={globalPolicy.channel}
                disabled={!canAdminister}
                onChange={(event) => setPolicies((current) => upsertGlobalPolicy(current, { ...globalPolicy, channel: event.target.value }))}
              />
            </label>
            <label>
              <span>Target version</span>
              <input
                value={globalPolicy.targetVersion ?? ''}
                disabled={!canAdminister}
                onChange={(event) => setPolicies((current) => upsertGlobalPolicy(current, { ...globalPolicy, targetVersion: event.target.value }))}
              />
            </label>
            {canAdminister && <button type="button" onClick={() => void updateGlobalPolicy()} disabled={working}>Save policy</button>}
          </div>
        </div>
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Version</th>
                <th>Target</th>
                <th>Status</th>
                <th>SHA-256</th>
                <th>Size</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {releases.map((release) => (
                <tr key={release.id}>
                  <td><strong>{release.version}</strong></td>
                  <td>{release.os}/{release.arch} / {release.channel}</td>
                  <td><StatusPill value={release.status} /></td>
                  <td><code>{shortHash(release.sha256)}</code></td>
                  <td>{formatBytes(release.sizeBytes)}</td>
                  <td>
                    {canAdminister && release.status !== 'ACTIVE' && (
                      <button type="button" onClick={() => void activateRelease(release)} disabled={working}>Activate</button>
                    )}
                    {canAdminister && release.status !== 'REVOKED' && (
                      <button type="button" onClick={() => void revokeRelease(release)} disabled={working}>Revoke</button>
                    )}
                  </td>
                </tr>
              ))}
              {releases.length === 0 && <tr><td colSpan={6}>No agent releases uploaded.</td></tr>}
            </tbody>
          </table>
        </div>
      </section>

      <div className="timeline-grid">
        <TimelinePanel title="Enrollment tokens" empty="agent-bound token 없음">
          {selectedTokens.map((token) => (
            <li key={token.id}>
              <KeyRound size={15} />
              <div>
                <strong>{token.name ?? `Token ${token.id}`}</strong>
                <span>{tokenStatus(token)} · 만료 {formatDate(token.expiresAt)}</span>
              </div>
              {canAdminister && !token.revokedAt && token.id && (
                <button className="icon-button" aria-label={`Token ${token.id} 폐기`} type="button" onClick={() => void revokeToken(token)}>
                  <Ban size={15} />
                </button>
              )}
            </li>
          ))}
        </TimelinePanel>
        <TimelinePanel title="Certificates" empty="발급된 인증서 없음">
          {selectedCertificates.map((certificate) => (
            <li key={certificate.id ?? certificate.serialNumber}>
              <ShieldCheck size={15} />
              <div>
                <strong>{certificate.serialNumber ?? '-'}</strong>
                <span>{certificate.status ?? '-'} · 만료 {formatDate(certificate.notAfter)}</span>
              </div>
            </li>
          ))}
        </TimelinePanel>
        <TimelinePanel title="Audit timeline" empty="감사 이벤트 없음">
          {selectedAudit.map((event) => (
            <li key={event.id ?? `${event.eventType}-${event.createdAt}`}>
              <ShieldAlert size={15} />
              <div>
                <strong>{event.eventType ?? '-'}</strong>
                <span>{formatDate(event.createdAt)} · {event.message ?? '-'}</span>
              </div>
            </li>
          ))}
        </TimelinePanel>
        <TimelinePanel title="Update attempts" empty="No update attempts">
          {selectedAttempts.map((attempt) => (
            <li key={attempt.id ?? attempt.deploymentId}>
              <UploadCloud size={15} />
              <div>
                <strong>{attempt.status}</strong>
                <span>{attempt.fromVersion ?? '-'} - release {attempt.releaseId} - {formatDate(attempt.updatedAt)} - {attempt.message ?? '-'}</span>
              </div>
            </li>
          ))}
        </TimelinePanel>
      </div>

      {packageOpen && (
        <div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="새 agent 패키지">
          <form className="modal-panel package-form" onSubmit={submitPackage}>
            <div className="panel-heading">
              <h3>새 agent 패키지</h3>
              <button className="icon-button" aria-label="닫기" type="button" onClick={() => setPackageOpen(false)}>×</button>
            </div>
            {packageForm.agentId ? (
              <dl className="kv-list compact">
                <div><dt>Agent ID</dt><dd>{packageForm.agentId}</dd></div>
              </dl>
            ) : (
              <p className="muted">Agent ID는 설치 대상 host name으로 자동 설정됩니다.</p>
            )}
            <label>
              <span>Tenant ID</span>
              <input aria-label="Tenant ID" value={packageForm.tenantId} onChange={(event) => setPackageForm((current) => ({ ...current, tenantId: event.target.value }))} />
            </label>
            <label>
              <span>TTL seconds</span>
              <input aria-label="TTL seconds" type="number" min={60} value={packageForm.ttlSeconds} onChange={(event) => setPackageForm((current) => ({ ...current, ttlSeconds: Number(event.target.value) }))} />
            </label>
            <div className="modal-actions">
              <button type="button" onClick={() => setPackageOpen(false)}>취소</button>
              <button type="submit" disabled={working}>
                <Download size={16} />
                패키지 생성
              </button>
            </div>
          </form>
        </div>
      )}
    </ViewFrame>
  );
}

function SummaryTile({ icon, label, tone, value }: { icon?: 'shield'; label: string; tone?: 'danger'; value: string }) {
  return (
    <section className={`summary-tile ${tone ?? ''}`}>
      {icon === 'shield' && <ShieldCheck size={18} />}
      <span>{label}</span>
      <strong>{value}</strong>
    </section>
  );
}

function TimelinePanel({ children, empty, title }: { children: ReactNode; empty: string; title: string }) {
  const items = Array.isArray(children) ? children.filter(Boolean) : children;
  const emptyList = Array.isArray(items) && items.length === 0;
  return (
    <section className="data-panel timeline-panel">
      <h3>{title}</h3>
      {emptyList ? <p>{empty}</p> : <ul>{children}</ul>}
    </section>
  );
}

function StatusPill({ value }: { value: string }) {
  return <span className={`status-pill ${value.toLowerCase()}`}>{value}</span>;
}

function tokenStatus(token: CastrelSignToken): string {
  if (token.revokedAt) {
    return 'REVOKED';
  }
  if ((token.usedCount ?? 0) >= (token.maxUses ?? 1)) {
    return 'USED';
  }
  return 'READY';
}

function isExpiringSoon(value?: string): boolean {
  if (!value) {
    return false;
  }
  const time = Date.parse(value);
  if (Number.isNaN(time)) {
    return false;
  }
  const days = (time - Date.now()) / 86_400_000;
  return days >= 0 && days <= 30;
}

function formatDate(value?: string): string {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toISOString().replace('.000Z', 'Z');
}

function downloadBlob(blob: Blob, filename: string) {
  const urlApi = globalThis.URL as (typeof URL & {
    createObjectURL?: (blob: Blob) => string;
    revokeObjectURL?: (url: string) => void;
  });
  if (!urlApi?.createObjectURL) {
    return;
  }
  const url = urlApi.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  urlApi.revokeObjectURL?.(url);
}

function upsertGlobalPolicy(policies: AgentUpdatePolicy[], policy: AgentUpdatePolicy): AgentUpdatePolicy[] {
  const nextPolicy = { ...policy, agentId: undefined, policyKey: policy.policyKey ?? 'global' };
  const index = policies.findIndex((item) => !item.agentId);
  if (index < 0) {
    return [nextPolicy, ...policies];
  }
  return policies.map((item, itemIndex) => (itemIndex === index ? nextPolicy : item));
}

function shortHash(value?: string): string {
  if (!value) {
    return '-';
  }
  return value.length <= 16 ? value : `${value.slice(0, 12)}...`;
}

function formatBytes(value?: number): string {
  if (!Number.isFinite(value)) {
    return '-';
  }
  const bytes = value ?? 0;
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
  }
  if (bytes >= 1024) {
    return `${(bytes / 1024).toFixed(1)} KiB`;
  }
  return `${bytes} B`;
}

function safeFileName(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, '_');
}
