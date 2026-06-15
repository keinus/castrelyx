import {
  Activity,
  Bell,
  Boxes,
  Gauge,
  LogOut,
  Network,
  Router,
  ScrollText,
  Server,
  ShieldCheck,
  Settings
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { api, bootstrap as loadBootstrap } from './lib/api';
import type { AlertRow, Asset, BootstrapState, DashboardSummary, Role } from './lib/types';
import { menuItemsForRole, nextSurface } from './lib/uiModel';
import { AgentDashboardView } from './views/AgentDashboardView';
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

const icons = {
  overview: Gauge,
  assets: Boxes,
  traffic: Activity,
  agent: Server,
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

  async function createAsset(payload: { name: string; assetType: string; managementIp?: string; description?: string }) {
    const created = await api.createAsset(payload);
    setAssets((current) => [created, ...current]);
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
    } catch {
      popup?.close();
      setActive('logparser');
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
  const activeView = menu.some((item) => item.id === active) ? active : 'overview';

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <Network aria-hidden="true" />
          <div>
            <h1>Castrelyx Manager</h1>
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
      <section className={`content ${activeView === 'overview' ? 'content-command-center' : ''}`}>
        <TopBar username={boot.user?.username ?? 'viewer'} />
        <ViewSwitch
          active={activeView}
          role={role}
          summary={summary}
          assets={assets}
          alerts={alerts}
          onCreateAsset={createAsset}
          onAcknowledgeAlert={(id) => updateAlert(id, 'acknowledge')}
          onResolveAlert={(id) => updateAlert(id, 'resolve')}
        />
      </section>
    </main>
  );
}

function TopBar({ username }: { username: string }) {
  return (
    <header className="topbar">
      <div>
        <span>NMS Control Plane</span>
        <strong>{username}</strong>
      </div>
    </header>
  );
}

function ViewSwitch({
  active,
  role,
  summary,
  assets,
  alerts,
  onCreateAsset,
  onAcknowledgeAlert,
  onResolveAlert
}: {
  active: string;
  role: Role;
  summary: DashboardSummary;
  assets: Asset[];
  alerts: AlertRow[];
  onCreateAsset: (payload: { name: string; assetType: string; managementIp?: string; description?: string }) => Promise<void>;
  onAcknowledgeAlert: (id: number) => Promise<void>;
  onResolveAlert: (id: number) => Promise<void>;
}) {
  const props = useMemo(() => ({ role }), [role]);
  switch (active) {
    case 'assets':
      return <AssetsView role={props.role} assets={assets} onCreate={onCreateAsset} />;
    case 'traffic':
      return <TrafficView />;
    case 'agent':
      return <AgentDashboardView />;
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
      return <OverviewView summary={summary} alerts={alerts} />;
  }
}
