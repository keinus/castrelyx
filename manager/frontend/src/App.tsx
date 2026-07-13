import {
  Activity,
  Bell,
  Boxes,
  FileText,
  Gauge,
  LogOut,
  Menu,
  Network,
  Router,
  ScrollText,
  Server,
  ShieldCheck,
  Settings,
  UserCircle,
  X
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { api, bootstrap as loadBootstrap } from './lib/api';
import type { AlertRow, Asset, BootstrapState, DashboardSummary, Role } from './lib/types';
import { menuItemsForRole, nextSurface, type MenuItem } from './lib/uiModel';
import { AgentDashboardView } from './views/AgentDashboardView';
import { AgentLogsView } from './views/AgentLogsView';
import { AlertsView } from './views/AlertsView';
import { AssetsView } from './views/AssetsView';
import { CastrelSignView } from './views/CastrelSignView';
import { LogparserView } from './views/LogparserView';
import { LoginView } from './views/LoginView';
import { OverviewView } from './views/OverviewView';
import { SettingsView } from './views/SettingsView';
import { SetupView } from './views/SetupView';
import { SnmpDashboardView } from './views/SnmpDashboardView';
import { TrafficView } from './views/TrafficView';
import './styles.css';

type AppProps = {
  bootstrap?: BootstrapState;
};

const fallbackSummary: DashboardSummary = {
  activeAssets: 0,
  criticalAlerts: 0,
  agentHealth: { healthy: 0, stale: 0 },
  snmpPollHealth: { success: 0, failure: 0 }
};

const fallbackAlerts: AlertRow[] = [];
const fallbackAssets: Asset[] = [];

function focusViewHeadingAfterCommit() {
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      document.querySelector<HTMLElement>('.command-view-header h2, .overview-action-rail h2')?.focus();
    });
  });
}

const icons = {
  overview: Gauge,
  assets: Boxes,
  traffic: Activity,
  agent: Server,
  agentLogs: FileText,
  snmp: Router,
  alerts: Bell,
  castrelsign: ShieldCheck,
  logparser: ScrollText,
  settings: Settings
};

export default function App({ bootstrap }: AppProps) {
  const [boot, setBoot] = useState<BootstrapState | null>(bootstrap ?? null);
  const [active, setActive] = useState('overview');
  const [summary, setSummary] = useState<DashboardSummary>(fallbackSummary);
  const [assets, setAssets] = useState<Asset[]>(fallbackAssets);
  const [alerts, setAlerts] = useState<AlertRow[]>(fallbackAlerts);

  useEffect(() => {
    if (!bootstrap) {
      loadBootstrap().then(setBoot).catch(() => setBoot({ setupRequired: false, authenticated: false }));
    }
  }, [bootstrap]);

  useEffect(() => {
    if (boot?.authenticated) {
      api.overview().then(setSummary).catch(() => setSummary(fallbackSummary));
      api.assets().then(setAssets).catch(() => setAssets(fallbackAssets));
      api.alerts().then(setAlerts).catch(() => setAlerts(fallbackAlerts));
    }
  }, [boot?.authenticated]);

  const surface = boot ? nextSurface(boot) : 'login';

  async function createAdmin(payload: { username: string; password: string; displayName?: string }) {
    await api.createAdmin(payload);
    setBoot({ setupRequired: false, authenticated: false });
  }

  async function login(payload: { username: string; password: string }) {
    const session = await api.login(payload);
    setBoot({ setupRequired: false, authenticated: true, user: session.user });
  }

  async function logout() {
    await api.logout();
    setBoot({ setupRequired: false, authenticated: false });
  }

  async function createAsset(payload: { name: string; assetType: string; managementIp?: string; location?: string; description?: string }) {
    const created = await api.createAsset(payload);
    setAssets((current) => [created, ...current]);
    return created;
  }

  async function updateAsset(id: number, payload: { name: string; location?: string; description?: string }) {
    const updated = await api.updateAsset(id, payload);
    setAssets((current) => current.map((asset) => (asset.id === id ? updated : asset)));
    return updated;
  }

  async function deleteAsset(id: number) {
    await api.deleteAsset(id);
    setAssets((current) => current.filter((asset) => asset.id !== id));
  }

  async function updateAlert(id: number, action: 'acknowledge' | 'resolve') {
    const updated = action === 'acknowledge'
      ? await api.acknowledgeAlert(id)
      : await api.resolveAlert(id);
    setAlerts((current) => current.map((alert) => (alert.id === id ? updated : alert)));
  }

  async function openLogparserUi() {
    const popup = window.open('about:blank', '_blank');
    if (popup) {
      popup.opener = null;
    }
    try {
      const links = await api.logparserLinks();
      const url = links.find((link) => link.url.trim().length > 0)?.url;
      if (url && popup) {
        popup.location.href = url;
        return;
      }
      if (url) {
        window.open(url, '_blank', 'noopener,noreferrer');
        return;
      }
      popup?.close();
      setActive('logparser');
      focusViewHeadingAfterCommit();
    } catch {
      popup?.close();
      setActive('logparser');
      focusViewHeadingAfterCommit();
    }
  }

  function openMenuItem(itemId: string) {
    if (itemId === 'logparser') {
      void openLogparserUi();
      return;
    }
    setActive(itemId);
  }

  if (!boot) {
    return <main className="loading">Castrelyx Manager</main>;
  }
  if (surface === 'setup') {
    return <SetupView onCreate={createAdmin} />;
  }
  if (surface === 'login') {
    return <LoginView onLogin={login} />;
  }

  const role = boot.user?.role ?? 'VIEWER';
  const menu = menuItemsForRole(role);
  const activeItem = menu.find((item) => item.id === active)
    ?? menu.find((item) => item.id === 'overview')
    ?? menu[0];
  const activeView = activeItem?.id ?? 'overview';

  return (
    <main className="app-shell operations-shell command-shell">
      <aside className="sidebar">
        <div className="brand">
          <Network aria-hidden="true" />
          <div>
            <h1>Castrelyx</h1>
            <span>{role}</span>
          </div>
        </div>
        <nav>
          {menu.map((item) => {
            const Icon = icons[item.id as keyof typeof icons];
            return (
              <button
                className={activeView === item.id ? 'active' : ''}
                key={item.id}
                onClick={() => openMenuItem(item.id)}
                type="button"
              >
                <Icon size={18} />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
        <button className="logout" type="button" onClick={logout}>
          <LogOut size={18} />
          <span>로그아웃</span>
        </button>
      </aside>
      <section className={activeView === 'overview' ? 'content content-operations' : 'content command-content'}>
        {activeView === 'overview' ? null : (
          <TopBar
            active={activeView}
            currentLabel={activeItem?.label ?? 'Operations'}
            navigationItems={menu}
            role={role}
            username={boot.user?.username ?? 'viewer'}
            onNavigate={openMenuItem}
          />
        )}
        <ViewSwitch
          active={activeView}
          role={role}
          username={boot.user?.username ?? 'viewer'}
          navigationItems={menu}
          summary={summary}
          assets={assets}
          alerts={alerts}
          onNavigate={openMenuItem}
          onCreateAsset={createAsset}
          onUpdateAsset={updateAsset}
          onDeleteAsset={deleteAsset}
          onAcknowledgeAlert={(id) => updateAlert(id, 'acknowledge')}
          onResolveAlert={(id) => updateAlert(id, 'resolve')}
        />
      </section>
    </main>
  );
}

function TopBar({
  active,
  currentLabel,
  navigationItems,
  role,
  username,
  onNavigate
}: {
  active: string;
  currentLabel: string;
  navigationItems: MenuItem[];
  role: string;
  username: string;
  onNavigate: (id: string) => void;
}) {
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  function navigate(itemId: string) {
    setMobileNavOpen(false);
    onNavigate(itemId);
    if (itemId !== 'logparser') {
      focusViewHeadingAfterCommit();
    }
  }

  return (
    <>
      <header className="topbar command-topbar">
        <button
          className="command-mobile-toggle"
          type="button"
          aria-controls="command-mobile-navigation"
          aria-expanded={mobileNavOpen}
          aria-label={mobileNavOpen ? '메뉴 닫기' : '메뉴 열기'}
          onClick={() => setMobileNavOpen((open) => !open)}
        >
          {mobileNavOpen ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
        </button>
        <div className="command-topbar-title">
          <span>NMS Control Plane</span>
          <strong>{currentLabel}</strong>
        </div>
        <div className="command-topbar-status" aria-label="Session context">
          <span className="command-session-role">{role}</span>
          <span className="command-plane-status">Manager Console</span>
          <span className="command-user"><UserCircle size={17} aria-hidden="true" /> {username}</span>
        </div>
      </header>
      {mobileNavOpen ? (
        <nav id="command-mobile-navigation" className="command-mobile-nav" aria-label="모바일 메뉴">
          {navigationItems.map((item) => {
            const Icon = icons[item.id as keyof typeof icons];
            return (
              <button
                className={active === item.id ? 'active' : ''}
                key={item.id}
                type="button"
                aria-current={active === item.id ? 'page' : undefined}
                onClick={() => navigate(item.id)}
              >
                <Icon size={18} aria-hidden="true" />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>
      ) : null}
    </>
  );
}

function ViewSwitch({
  active,
  role,
  username,
  navigationItems,
  summary,
  assets,
  alerts,
  onNavigate,
  onCreateAsset,
  onUpdateAsset,
  onDeleteAsset,
  onAcknowledgeAlert,
  onResolveAlert
}: {
  active: string;
  role: Role;
  username: string;
  navigationItems: MenuItem[];
  summary: DashboardSummary;
  assets: Asset[];
  alerts: AlertRow[];
  onNavigate: (id: string) => void;
  onCreateAsset: (payload: { name: string; assetType: string; managementIp?: string; location?: string; description?: string }) => Promise<Asset | void>;
  onUpdateAsset: (id: number, payload: { name: string; location?: string; description?: string }) => Promise<Asset | void>;
  onDeleteAsset: (id: number) => Promise<void>;
  onAcknowledgeAlert: (id: number) => Promise<void>;
  onResolveAlert: (id: number) => Promise<void>;
}) {
  const props = useMemo(() => ({ role }), [role]);
  switch (active) {
    case 'assets':
      return <AssetsView role={props.role} assets={assets} onCreate={onCreateAsset} onUpdate={onUpdateAsset} onDelete={onDeleteAsset} />;
    case 'traffic':
      return <TrafficView />;
    case 'agent':
      return <AgentDashboardView />;
    case 'agentLogs':
      return <AgentLogsView />;
    case 'snmp':
      return <SnmpDashboardView />;
    case 'alerts':
      return <AlertsView role={props.role} alerts={alerts} onAcknowledge={onAcknowledgeAlert} onResolve={onResolveAlert} />;
    case 'castrelsign':
      return <CastrelSignView role={props.role} />;
    case 'logparser':
      return <LogparserView />;
    case 'settings':
      return <SettingsView />;
    default:
      return (
        <OverviewView
          summary={summary}
          alerts={alerts}
          assets={assets}
          username={username}
          navigationItems={navigationItems}
          onNavigate={onNavigate}
          onAcknowledgeAlert={onAcknowledgeAlert}
        />
      );
  }
}
