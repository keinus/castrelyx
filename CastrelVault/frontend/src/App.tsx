import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  Copy,
  Database,
  Eye,
  FileClock,
  KeyRound,
  Lock,
  LogOut,
  Play,
  Plus,
  RefreshCw,
  RotateCw,
  Search,
  ServerCog,
  Shield,
  ShieldCheck,
  Trash2,
  Unlock,
  XCircle
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { type FormEvent, type ReactNode, useEffect, useMemo, useState } from 'react';
import { api, type SecretWritePayload } from './lib/api';
import type {
  ApplicationCertificate,
  ApplicationPrincipal,
  ApplicationToken,
  AuditEvent,
  MigrationPlan,
  MigrationStatus,
  Secret,
  SecretType,
  SecretVersion,
  Session,
  VaultStatus
} from './lib/types';
import { formatDate, secretTypes, splitTags, statusTone } from './lib/ui';
import './styles.css';

type ViewId = 'dashboard' | 'secrets' | 'applications' | 'migration' | 'audit' | 'settings';

const navItems: { id: ViewId; label: string; icon: LucideIcon }[] = [
  { id: 'dashboard', label: 'Dashboard', icon: Activity },
  { id: 'secrets', label: 'Secrets', icon: KeyRound },
  { id: 'applications', label: 'Applications', icon: ShieldCheck },
  { id: 'migration', label: 'Migration', icon: ServerCog },
  { id: 'audit', label: 'Audit', icon: FileClock },
  { id: 'settings', label: 'Settings', icon: Database }
];

const emptySecretForm = {
  path: '',
  displayName: '',
  type: 'API_TOKEN' as SecretType,
  tags: '',
  description: '',
  value: '',
  username: '',
  password: '',
  host: '',
  community: '',
  authProtocol: 'SHA',
  authPassword: '',
  privacyProtocol: 'AES',
  privacyPassword: '',
  jdbcUrl: '',
  privateKey: '',
  certificateChain: '',
  payloadJson: '{\n  "value": ""\n}'
};

type SecretFormState = typeof emptySecretForm;

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [checkingSession, setCheckingSession] = useState(true);

  useEffect(() => {
    api.session()
      .then(setSession)
      .catch(() => setSession(null))
      .finally(() => setCheckingSession(false));
  }, []);

  if (checkingSession) {
    return <main className="loading">CastrelVault</main>;
  }

  if (!session) {
    return <AuthView onSession={setSession} />;
  }

  if (session.requiresPasswordChange) {
    return <PasswordChangeView username={session.username} onChanged={() => setSession({ ...session, requiresPasswordChange: false })} />;
  }

  return <Console session={session} onLogout={() => setSession(null)} />;
}

function AuthView({ onSession }: { onSession: (session: Session) => void }) {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      onSession(await api.login(username, password));
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-panel">
        <div className="auth-brand">
          <Shield size={34} />
          <div>
            <h1>CastrelVault</h1>
            <span>Credential control plane</span>
          </div>
        </div>
        <form onSubmit={submit} className="auth-form">
          <label>
            Username
            <input value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" required />
          </label>
          <label>
            Password
            <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" autoComplete="current-password" required />
          </label>
          {error && <p className="form-error">{error}</p>}
          <button type="submit">
            <Lock size={16} />
            Sign in
          </button>
        </form>
      </section>
    </main>
  );
}

function PasswordChangeView({ username, onChanged }: { username: string; onChanged: () => void }) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      await api.changePassword(currentPassword, newPassword);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <main className="auth-shell">
      <section className="auth-panel">
        <div className="auth-brand">
          <ShieldCheck size={34} />
          <div>
            <h1>Password change</h1>
            <span>{username}</span>
          </div>
        </div>
        <form onSubmit={submit} className="auth-form">
          <label>
            Current password
            <input value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} type="password" required />
          </label>
          <label>
            New password
            <input value={newPassword} onChange={(event) => setNewPassword(event.target.value)} type="password" minLength={12} required />
          </label>
          {error && <p className="form-error">{error}</p>}
          <button type="submit">
            <KeyRound size={16} />
            Update password
          </button>
        </form>
      </section>
    </main>
  );
}

function Console({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const [active, setActive] = useState<ViewId>('dashboard');

  async function logout() {
    await api.logout().catch(() => null);
    onLogout();
  }

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <Shield size={24} />
          <div>
            <strong>CastrelVault</strong>
            <span>{session.username}</span>
          </div>
        </div>
        <nav>
          {navItems.map((item) => {
            const Icon = item.icon;
            return (
              <button key={item.id} className={active === item.id ? 'active' : ''} onClick={() => setActive(item.id)} type="button">
                <Icon size={17} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
        <button className="logout" type="button" onClick={() => void logout()}>
          <LogOut size={17} />
          Logout
        </button>
      </aside>
      <section className="content">
        {active === 'dashboard' && <DashboardView />}
        {active === 'secrets' && <SecretsView />}
        {active === 'applications' && <ApplicationsView />}
        {active === 'migration' && <MigrationView />}
        {active === 'audit' && <AuditView />}
        {active === 'settings' && <SettingsView />}
      </section>
    </main>
  );
}

function DashboardView() {
  const [status, setStatus] = useState<VaultStatus | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      setStatus(await api.status());
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <ViewFrame title="Dashboard" actions={<IconButton label="Refresh" icon={RefreshCw} onClick={() => void load()} />}>
      {error && <Notice tone="danger">{error}</Notice>}
      {status && (
        <>
          <section className="metric-grid">
            <Metric label="Secrets" value={status.secrets.total} detail={`${status.secrets.enabled} enabled`} tone="good" />
            <Metric label="Versions" value={status.secrets.versions} detail={`active key ${status.activeMasterKeyId}`} />
            <Metric label="Reveal events" value={status.audit.reveals} detail={`${status.audit.denied} denied`} tone={status.audit.denied ? 'warning' : 'good'} />
            <Metric label="CastrelSign" value={status.castrelSign.state ?? 'UNKNOWN'} detail={status.castrelSign.detail ?? status.castrelSign.baseUrl ?? '-'} tone={statusTone(status.castrelSign.state)} />
          </section>
          <section className="two-column">
            <Panel title="Security posture">
              <StatusRow label="Server TLS configured" ok={status.tls.serverTlsConfigured} />
              <StatusRow label="Trust store configured" ok={status.tls.trustStoreConfigured} />
              <StatusRow label="CastrelSign CA configured" ok={status.tls.castrelSignCaConfigured} />
              <StatusRow label="Manager migration proxy" ok={String(status.managerMigration.status ?? '').toUpperCase() !== 'UNAVAILABLE'} />
            </Panel>
            <Panel title="Storage">
              <Definition label="Data dir" value={status.dataDir} />
              <Definition label="SQLite" value={status.databasePath} />
              <Definition label="Key IDs" value={status.configuredMasterKeyIds.join(', ')} />
            </Panel>
          </section>
        </>
      )}
    </ViewFrame>
  );
}

function SecretsView() {
  const [secrets, setSecrets] = useState<Secret[]>([]);
  const [selectedId, setSelectedId] = useState<string>('');
  const [versions, setVersions] = useState<SecretVersion[]>([]);
  const [form, setForm] = useState<SecretFormState>(emptySecretForm);
  const [query, setQuery] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revealTarget, setRevealTarget] = useState<Secret | null>(null);

  const selected = secrets.find((secret) => secret.id === selectedId) ?? secrets[0];
  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) {
      return secrets;
    }
    return secrets.filter((secret) => `${secret.path} ${secret.displayName} ${secret.type} ${secret.tags.join(' ')}`.toLowerCase().includes(needle));
  }, [query, secrets]);

  async function load() {
    setError(null);
    try {
      const rows = await api.secrets();
      setSecrets(rows);
      setSelectedId((current) => current || rows[0]?.id || '');
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function loadVersions(id?: string) {
    if (!id) {
      setVersions([]);
      return;
    }
    setVersions(await api.secretVersions(id).catch(() => []));
  }

  useEffect(() => {
    void load();
  }, []);

  useEffect(() => {
    void loadVersions(selected?.id);
  }, [selected?.id]);

  async function create(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    try {
      const created = await api.createSecret(secretPayload(form));
      setNotice(`${created.path} created`);
      setForm(emptySecretForm);
      await load();
      setSelectedId(created.id);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function rotate() {
    if (!selected) {
      return;
    }
    setError(null);
    try {
      await api.rotateSecret(selected.id, payloadOnly(form));
      setNotice(`${selected.path} rotated`);
      await load();
      await loadVersions(selected.id);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function setEnabled(secret: Secret, enabled: boolean) {
    await api.setSecretEnabled(secret.id, enabled);
    await load();
  }

  async function deleteSecret(secret: Secret) {
    await api.deleteSecret(secret.id);
    setSelectedId('');
    await load();
  }

  return (
    <ViewFrame title="Secrets" actions={<IconButton label="Refresh" icon={RefreshCw} onClick={() => void load()} />}>
      {notice && <Notice>{notice}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}
      <section className="secret-layout">
        <Panel title="Inventory" meta={`${filtered.length} secrets`}>
          <div className="search-box">
            <Search size={16} />
            <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="path, tag, type" />
          </div>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>Path</th>
                  <th>Type</th>
                  <th>Version</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((secret) => (
                  <tr key={secret.id} className={selected?.id === secret.id ? 'selected' : ''} onClick={() => setSelectedId(secret.id)}>
                    <td>
                      <strong>{secret.displayName}</strong>
                      <span>{secret.path}</span>
                    </td>
                    <td>{secret.type}</td>
                    <td>v{secret.currentVersion ?? '-'}</td>
                    <td><Badge value={secret.enabled ? 'ENABLED' : 'DISABLED'} /></td>
                  </tr>
                ))}
                {filtered.length === 0 && <tr><td colSpan={4}>No secrets</td></tr>}
              </tbody>
            </table>
          </div>
        </Panel>

        <Panel title="Detail" meta={selected?.path ?? 'Select a secret'}>
          {selected ? (
            <>
              <div className="detail-head">
                <div>
                  <h3>{selected.displayName}</h3>
                  <span>{selected.path}</span>
                </div>
                <Badge value={selected.enabled ? 'ENABLED' : 'DISABLED'} />
              </div>
              <div className="action-row">
                <button type="button" onClick={() => setRevealTarget(selected)} disabled={!selected.enabled}>
                  <Eye size={15} />
                  Reveal
                </button>
                <button type="button" className="secondary" onClick={() => void rotate()}>
                  <RotateCw size={15} />
                  Rotate from form
                </button>
                <button type="button" className="secondary" onClick={() => void setEnabled(selected, !selected.enabled)}>
                  {selected.enabled ? <Lock size={15} /> : <Unlock size={15} />}
                  {selected.enabled ? 'Disable' : 'Enable'}
                </button>
                <button type="button" className="danger" onClick={() => void deleteSecret(selected)}>
                  <Trash2 size={15} />
                  Delete
                </button>
              </div>
              <dl className="definition-grid">
                <Definition label="Type" value={selected.type} />
                <Definition label="Updated" value={formatDate(selected.updatedAt)} />
                <Definition label="Tags" value={selected.tags.join(', ') || '-'} />
                <Definition label="Payload" value={selected.payload.masked} />
              </dl>
              <h4>Version history</h4>
              <div className="version-list">
                {versions.map((version) => (
                  <div key={version.id} className={version.current ? 'version-row current' : 'version-row'}>
                    <strong>v{version.version}</strong>
                    <span>{version.keyId}</span>
                    <span>{formatDate(version.createdAt)}</span>
                    <code>{version.payloadContentHash.slice(0, 12)}</code>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <EmptyState title="No secret selected" detail="Create or select a secret from inventory." />
          )}
        </Panel>

        <Panel title="Create or rotate payload">
          <SecretForm form={form} setForm={setForm} onSubmit={create} />
        </Panel>
      </section>
      {revealTarget && <RevealDialog secret={revealTarget} onClose={() => setRevealTarget(null)} />}
    </ViewFrame>
  );
}

function SecretForm({ form, setForm, onSubmit }: {
  form: SecretFormState;
  setForm: (form: SecretFormState) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  const update = (patch: Partial<SecretFormState>) => setForm({ ...form, ...patch });
  return (
    <form className="grid-form" onSubmit={onSubmit}>
      <label>Path<input value={form.path} onChange={(event) => update({ path: event.target.value })} placeholder="/manager/integrations/castrelsign/secret" required /></label>
      <label>Display name<input value={form.displayName} onChange={(event) => update({ displayName: event.target.value })} required /></label>
      <label>Type<select value={form.type} onChange={(event) => update({ type: event.target.value as SecretType })}>{secretTypes.map((type) => <option key={type}>{type}</option>)}</select></label>
      <label>Tags<input value={form.tags} onChange={(event) => update({ tags: event.target.value })} placeholder="manager, integration" /></label>
      <label className="wide">Description<input value={form.description} onChange={(event) => update({ description: event.target.value })} /></label>
      <PayloadFields form={form} update={update} />
      <button type="submit"><Plus size={15} />Create secret</button>
    </form>
  );
}

function PayloadFields({ form, update }: { form: SecretFormState; update: (patch: Partial<SecretFormState>) => void }) {
  if (form.type === 'GENERIC') {
    return (
      <label className="wide">Payload JSON<textarea value={form.payloadJson} onChange={(event) => update({ payloadJson: event.target.value })} rows={8} /></label>
    );
  }
  if (form.type === 'SERVER_LOGIN' || form.type === 'WINDOWS_LOGIN') {
    return (
      <>
        <label>Host<input value={form.host} onChange={(event) => update({ host: event.target.value })} /></label>
        <label>Username<input value={form.username} onChange={(event) => update({ username: event.target.value })} /></label>
        <label className="wide">Password<input value={form.password} onChange={(event) => update({ password: event.target.value })} type="password" required /></label>
      </>
    );
  }
  if (form.type === 'SNMP_V2C') {
    return <label className="wide">Community<input value={form.community} onChange={(event) => update({ community: event.target.value })} type="password" required /></label>;
  }
  if (form.type === 'SNMP_V3') {
    return (
      <>
        <label>Username<input value={form.username} onChange={(event) => update({ username: event.target.value })} required /></label>
        <label>Auth protocol<input value={form.authProtocol} onChange={(event) => update({ authProtocol: event.target.value })} /></label>
        <label>Auth password<input value={form.authPassword} onChange={(event) => update({ authPassword: event.target.value })} type="password" /></label>
        <label>Privacy protocol<input value={form.privacyProtocol} onChange={(event) => update({ privacyProtocol: event.target.value })} /></label>
        <label className="wide">Privacy password<input value={form.privacyPassword} onChange={(event) => update({ privacyPassword: event.target.value })} type="password" /></label>
      </>
    );
  }
  if (form.type === 'DB_PASSWORD') {
    return (
      <>
        <label>JDBC URL<input value={form.jdbcUrl} onChange={(event) => update({ jdbcUrl: event.target.value })} /></label>
        <label>Username<input value={form.username} onChange={(event) => update({ username: event.target.value })} /></label>
        <label className="wide">Password<input value={form.password} onChange={(event) => update({ password: event.target.value })} type="password" required /></label>
      </>
    );
  }
  if (form.type === 'CERTIFICATE_KEY') {
    return (
      <>
        <label className="wide">Private key<textarea value={form.privateKey} onChange={(event) => update({ privateKey: event.target.value })} rows={5} required /></label>
        <label className="wide">Certificate chain<textarea value={form.certificateChain} onChange={(event) => update({ certificateChain: event.target.value })} rows={5} /></label>
      </>
    );
  }
  return <label className="wide">Secret value<input value={form.value} onChange={(event) => update({ value: event.target.value })} type="password" required /></label>;
}

function RevealDialog({ secret, onClose }: { secret: Secret; onClose: () => void }) {
  const [password, setPassword] = useState('');
  const [reason, setReason] = useState('');
  const [payload, setPayload] = useState<Record<string, unknown> | null>(null);
  const [expiresIn, setExpiresIn] = useState(0);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!payload) {
      return;
    }
    setExpiresIn(60);
    const interval = window.setInterval(() => setExpiresIn((value) => Math.max(0, value - 1)), 1000);
    const timeout = window.setTimeout(() => setPayload(null), 60_000);
    return () => {
      window.clearInterval(interval);
      window.clearTimeout(timeout);
    };
  }, [payload]);

  async function reveal(event: FormEvent) {
    event.preventDefault();
    setError(null);
    try {
      const result = await api.revealSecret(secret.id, password, reason);
      setPayload(result.payload);
      setPassword('');
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <div className="modal-backdrop">
      <section className="modal">
        <div className="modal-head">
          <div>
            <h3>Reveal secret</h3>
            <span>{secret.path}</span>
          </div>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>×</button>
        </div>
        <form className="grid-form" onSubmit={reveal}>
          <label className="wide">Admin password<input value={password} onChange={(event) => setPassword(event.target.value)} type="password" required /></label>
          <label className="wide">Reason<input value={reason} onChange={(event) => setReason(event.target.value)} required /></label>
          {error && <p className="form-error wide">{error}</p>}
          <button type="submit"><Eye size={15} />Reveal</button>
        </form>
        {payload && (
          <div className="reveal-output">
            <div>
              <strong>Visible for {expiresIn}s</strong>
              <button type="button" className="secondary" onClick={() => void navigator.clipboard?.writeText(JSON.stringify(payload, null, 2))}>
                <Copy size={15} />
                Copy
              </button>
            </div>
            <pre>{JSON.stringify(payload, null, 2)}</pre>
          </div>
        )}
      </section>
    </div>
  );
}

function ApplicationsView() {
  const [apps, setApps] = useState<ApplicationPrincipal[]>([]);
  const [certs, setCerts] = useState<ApplicationCertificate[]>([]);
  const [tokens, setTokens] = useState<ApplicationToken[]>([]);
  const [principalId, setPrincipalId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [selected, setSelected] = useState('');
  const [newToken, setNewToken] = useState<ApplicationToken | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      const [nextApps, nextCerts, nextTokens] = await Promise.all([
        api.applications(),
        api.applicationCertificates(),
        api.applicationTokens()
      ]);
      setApps(nextApps);
      setCerts(nextCerts);
      setTokens(nextTokens);
      setSelected((current) => current || nextApps[0]?.principalId || '');
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  const active = apps.find((app) => app.principalId === selected) ?? apps[0];
  const activeCerts = certs.filter((cert) => cert.principalId === active?.principalId);
  const activeTokens = tokens.filter((token) => token.principalId === active?.principalId);

  async function create(event: FormEvent) {
    event.preventDefault();
    const app = await api.createApplication({ principal_id: principalId, display_name: displayName });
    await api.grantPermission(app.principalId, 'vault:resolve');
    setPrincipalId('');
    setDisplayName('');
    setNotice(`${app.principalId} created with vault:resolve`);
    await load();
    setSelected(app.principalId);
  }

  async function createToken() {
    if (!active) {
      return;
    }
    const token = await api.createApplicationToken({
      name: `${active.principalId} enrollment`,
      principal_id: active.principalId,
      ttl_seconds: 86_400
    });
    setNewToken(token);
    await load();
  }

  async function setStatus(app: ApplicationPrincipal, status: 'ACTIVE' | 'BLOCKED') {
    await api.setApplicationStatus(app.principalId, status);
    await load();
  }

  return (
    <ViewFrame title="Applications" actions={<IconButton label="Refresh" icon={RefreshCw} onClick={() => void load()} />}>
      {notice && <Notice>{notice}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}
      <section className="two-column wide-left">
        <Panel title="Application principals" meta={`${apps.length} principals`}>
          <div className="table-scroll">
            <table>
              <thead><tr><th>Principal</th><th>Permissions</th><th>Status</th></tr></thead>
              <tbody>
                {apps.map((app) => (
                  <tr key={app.principalId} className={active?.principalId === app.principalId ? 'selected' : ''} onClick={() => setSelected(app.principalId)}>
                    <td><strong>{app.displayName ?? app.principalId}</strong><span>{app.principalId}</span></td>
                    <td>{app.permissions?.join(', ') || '-'}</td>
                    <td><Badge value={app.status} /></td>
                  </tr>
                ))}
                {apps.length === 0 && <tr><td colSpan={3}>No application principals</td></tr>}
              </tbody>
            </table>
          </div>
        </Panel>
        <Panel title="Principal detail" meta={active?.principalId ?? '-'}>
          {active ? (
            <>
              <div className="detail-head">
                <div>
                  <h3>{active.displayName ?? active.principalId}</h3>
                  <span>{active.permissions?.join(', ') || 'No permissions'}</span>
                </div>
                <Badge value={active.status} />
              </div>
              <div className="action-row">
                <button type="button" onClick={() => void createToken()}><KeyRound size={15} />One-use token</button>
                <button type="button" className="secondary" onClick={() => void api.grantPermission(active.principalId, 'vault:resolve').then(load)}>
                  <ShieldCheck size={15} />Grant resolve
                </button>
                <button type="button" className="secondary" onClick={() => void setStatus(active, active.status === 'ACTIVE' ? 'BLOCKED' : 'ACTIVE')}>
                  {active.status === 'ACTIVE' ? 'Block' : 'Reactivate'}
                </button>
              </div>
              {newToken?.token && (
                <Notice tone="warning">
                  One-use token: <code>{newToken.token}</code>
                </Notice>
              )}
              <h4>Certificates</h4>
              <div className="compact-list">
                {activeCerts.map((cert) => (
                  <div key={cert.serialNumber}>
                    <strong>{cert.serialNumber}</strong>
                    <span>{cert.status} · expires {formatDate(cert.notAfter)}</span>
                  </div>
                ))}
                {activeCerts.length === 0 && <span className="muted">No certificates</span>}
              </div>
              <h4>Enrollment tokens</h4>
              <div className="compact-list">
                {activeTokens.map((token) => (
                  <div key={token.id}>
                    <strong>{token.name ?? `Token ${token.id}`}</strong>
                    <span>{token.revokedAt ? 'revoked' : token.usedAt ? 'used' : 'pending'} · expires {formatDate(token.expiresAt)}</span>
                  </div>
                ))}
                {activeTokens.length === 0 && <span className="muted">No tokens</span>}
              </div>
            </>
          ) : (
            <EmptyState title="No principal selected" detail="Create an application principal first." />
          )}
        </Panel>
      </section>
      <Panel title="Create application principal">
        <form className="inline-form" onSubmit={(event) => void create(event)}>
          <label>Principal ID<input value={principalId} onChange={(event) => setPrincipalId(event.target.value)} required /></label>
          <label>Display name<input value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label>
          <button type="submit"><Plus size={15} />Create</button>
        </form>
      </Panel>
    </ViewFrame>
  );
}

function MigrationView() {
  const [status, setStatus] = useState<MigrationStatus | null>(null);
  const [plan, setPlan] = useState<MigrationPlan | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      setStatus(await api.migrationStatus());
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  async function dryRun() {
    setPlan(await api.migrationDryRun());
  }

  async function run() {
    const result = await api.migrationRun();
    setNotice(`Migrated ${result.migratedIntegrationSecrets ?? 0} integration and ${result.migratedSnmpCredentials ?? 0} SNMP secrets`);
    await load();
  }

  return (
    <ViewFrame title="Manager Migration" actions={<IconButton label="Refresh" icon={RefreshCw} onClick={() => void load()} />}>
      {notice && <Notice>{notice}</Notice>}
      {error && <Notice tone="danger">{error}</Notice>}
      <section className="metric-grid">
        <Metric label="Vault client" value={status?.vaultEnabled ? 'ENABLED' : status?.configured === false ? 'UNCONFIGURED' : 'DISABLED'} detail={status?.status ?? status?.detail ?? '-'} tone={status?.vaultEnabled ? 'good' : 'warning'} />
        <Metric label="Pending integrations" value={status?.pendingIntegrationSecrets ?? 0} />
        <Metric label="Pending SNMP" value={status?.pendingSnmpCredentials ?? 0} />
        <Metric label="Migrated" value={(status?.migratedIntegrationSecrets ?? 0) + (status?.migratedSnmpCredentials ?? 0)} />
      </section>
      <div className="action-row">
        <button type="button" onClick={() => void dryRun()}><Search size={15} />Dry-run</button>
        <button type="button" className="danger" onClick={() => void run()} disabled={!plan}>
          <Play size={15} />Run migration
        </button>
      </div>
      {plan && (
        <Panel title="Dry-run plan" meta={`${(plan.pendingIntegrationSecrets ?? 0) + (plan.pendingSnmpCredentials ?? 0)} pending`}>
          <div className="compact-list">
            {[...(plan.integrations ?? []), ...(plan.snmpCredentials ?? [])].map((item) => (
              <div key={`${item.source}:${item.vaultPath}`}>
                <strong>{item.name}</strong>
                <span>{item.source} · {item.type} · {item.vaultPath}</span>
              </div>
            ))}
          </div>
        </Panel>
      )}
    </ViewFrame>
  );
}

function AuditView() {
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [total, setTotal] = useState(0);
  const [filters, setFilters] = useState({ action: '', result: '', secretPath: '', limit: '100' });
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setError(null);
    try {
      const params = Object.fromEntries(Object.entries(filters).filter(([, value]) => value));
      const page = await api.audit(params);
      setEvents(page.events);
      setTotal(page.total);
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  useEffect(() => {
    void load();
  }, []);

  return (
    <ViewFrame title="Audit" actions={<IconButton label="Refresh" icon={RefreshCw} onClick={() => void load()} />}>
      {error && <Notice tone="danger">{error}</Notice>}
      <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); void load(); }}>
        <input value={filters.action} onChange={(event) => setFilters({ ...filters, action: event.target.value })} placeholder="action" />
        <input value={filters.result} onChange={(event) => setFilters({ ...filters, result: event.target.value })} placeholder="result" />
        <input value={filters.secretPath} onChange={(event) => setFilters({ ...filters, secretPath: event.target.value })} placeholder="secret path" />
        <button type="submit"><Search size={15} />Search</button>
      </form>
      <Panel title="Audit events" meta={`${total} matched`}>
        <div className="table-scroll">
          <table>
            <thead><tr><th>Time</th><th>Action</th><th>Actor</th><th>Secret</th><th>Reason</th></tr></thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id}>
                  <td>{formatDate(event.timestamp)}</td>
                  <td><Badge value={`${event.action} ${event.result}`} /></td>
                  <td>{event.actorType}:{event.actorId ?? '-'}</td>
                  <td>{event.secretPath ?? '-'}</td>
                  <td>{event.reason ?? '-'}</td>
                </tr>
              ))}
              {events.length === 0 && <tr><td colSpan={5}>No audit events</td></tr>}
            </tbody>
          </table>
        </div>
      </Panel>
    </ViewFrame>
  );
}

function SettingsView() {
  const [status, setStatus] = useState<VaultStatus | null>(null);

  useEffect(() => {
    api.status().then(setStatus).catch(() => setStatus(null));
  }, []);

  return (
    <ViewFrame title="Settings">
      {status ? (
        <section className="two-column">
          <Panel title="Runtime">
            <Definition label="Service" value={status.service} />
            <Definition label="Data dir" value={status.dataDir} />
            <Definition label="Database" value={status.databasePath} />
          </Panel>
          <Panel title="Key status">
            <Definition label="Active key" value={status.activeMasterKeyId} />
            <Definition label="Configured keys" value={status.configuredMasterKeyIds.join(', ')} />
          </Panel>
        </section>
      ) : (
        <EmptyState title="Status unavailable" detail="Refresh the dashboard to reload configuration state." />
      )}
    </ViewFrame>
  );
}

function ViewFrame({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="view">
      <header className="view-head">
        <div>
          <h2>{title}</h2>
        </div>
        <div className="view-actions">{actions}</div>
      </header>
      {children}
    </section>
  );
}

function Panel({ title, meta, children }: { title: string; meta?: string; children: ReactNode }) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h3>{title}</h3>
        {meta && <span>{meta}</span>}
      </div>
      {children}
    </section>
  );
}

function Metric({ label, value, detail, tone = 'neutral' }: { label: string; value: string | number; detail?: string; tone?: 'good' | 'warning' | 'danger' | 'neutral' }) {
  return (
    <div className={`metric ${tone}`}>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{detail ?? '-'}</small>
    </div>
  );
}

function Badge({ value }: { value: string }) {
  return <span className={`badge ${statusTone(value)}`}>{value}</span>;
}

function Notice({ children, tone = 'good' }: { children: ReactNode; tone?: 'good' | 'warning' | 'danger' }) {
  return <div className={`notice ${tone}`}>{children}</div>;
}

function IconButton({ label, icon: Icon, onClick }: { label: string; icon: LucideIcon; onClick: () => void }) {
  return (
    <button className="icon-button" aria-label={label} title={label} type="button" onClick={onClick}>
      <Icon size={17} />
    </button>
  );
}

function StatusRow({ label, ok }: { label: string; ok: boolean }) {
  return (
    <div className="status-row">
      {ok ? <CheckCircle2 size={16} /> : <XCircle size={16} />}
      <span>{label}</span>
      <Badge value={ok ? 'READY' : 'MISSING'} />
    </div>
  );
}

function Definition({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="definition">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="empty-state">
      <AlertTriangle size={24} />
      <strong>{title}</strong>
      <span>{detail}</span>
    </div>
  );
}

function secretPayload(form: SecretFormState): SecretWritePayload {
  return {
    path: form.path.trim(),
    displayName: form.displayName.trim(),
    type: form.type,
    tags: splitTags(form.tags),
    description: form.description.trim() || undefined,
    payload: payloadOnly(form)
  };
}

function payloadOnly(form: SecretFormState): Record<string, unknown> {
  switch (form.type) {
    case 'SERVER_LOGIN':
    case 'WINDOWS_LOGIN':
      return { host: form.host, username: form.username, password: form.password };
    case 'SNMP_V2C':
      return { community: form.community };
    case 'SNMP_V3':
      return {
        username: form.username,
        authProtocol: form.authProtocol,
        authPassword: form.authPassword,
        privacyProtocol: form.privacyProtocol,
        privacyPassword: form.privacyPassword
      };
    case 'DB_PASSWORD':
      return { jdbcUrl: form.jdbcUrl, username: form.username, password: form.password };
    case 'CERTIFICATE_KEY':
      return { privateKey: form.privateKey, certificateChain: form.certificateChain };
    case 'GENERIC':
      return JSON.parse(form.payloadJson) as Record<string, unknown>;
    default:
      return { value: form.value };
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'request failed';
}
