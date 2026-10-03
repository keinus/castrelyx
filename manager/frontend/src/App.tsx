import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, bootstrap as loadBootstrap } from './lib/api';
import type { AlertRow, Asset, BootstrapState, DashboardSummary, Role } from './lib/types';
import { menuItemsForRole, type MenuItem } from './lib/uiModel';
import { AgentDashboardView } from './views/AgentDashboardView';
import { AgentLogsView } from './views/AgentLogsView';
import { AlertsView } from './views/AlertsView';
import { AssetsView } from './views/AssetsView';
import { CastrelSignView } from './views/CastrelSignView';
import { LoginView } from './views/LoginView';
import { LogparserView } from './views/LogparserView';
import { OverviewView } from './views/OverviewView';
import { SetupView } from './views/SetupView';
import { SettingsView } from './views/SettingsView';
import { SnmpDashboardView } from './views/SnmpDashboardView';
import { TrafficView } from './views/TrafficView';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from './components/ui/card';
import { Button } from './components/ui/button';
import { CommandShell } from './components/CommandShell';
import './styles.css';
import './command-center.css';
import './command-details.css';

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
const kst = 'ko-KR';

function getErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return '요청 처리 중 오류가 발생했습니다.';
}

function toSyncText(value: Date | null) {
  if (!value) {
    return '미동기화';
  }
  return new Intl.DateTimeFormat(kst, {
    dateStyle: 'short',
    timeStyle: 'short'
  }).format(value);
}

function focusViewHeadingAfterCommit() {
  window.requestAnimationFrame(() => {
    window.requestAnimationFrame(() => {
      document.querySelector<HTMLElement>('.command-view-header h2, .overview-action-rail h2')?.focus();
    });
  });
}

export default function App({ bootstrap }: AppProps) {
  const [boot, setBoot] = useState<BootstrapState | null>(bootstrap ?? null);
  const [active, setActive] = useState('overview');
  const [summary, setSummary] = useState<DashboardSummary>(fallbackSummary);
  const [assets, setAssets] = useState<Asset[]>(fallbackAssets);
  const [alerts, setAlerts] = useState<AlertRow[]>(fallbackAlerts);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [bootLoading, setBootLoading] = useState(!bootstrap);
  const [bootError, setBootError] = useState<string | null>(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [workspaceError, setWorkspaceError] = useState<string | null>(null);
  const [authError, setAuthError] = useState<string | null>(null);
  const [menuSearch, setMenuSearch] = useState('');
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);

  const loadBootstrapState = useCallback(async () => {
    if (bootstrap) {
      setBoot(bootstrap);
      setBootError(null);
      setBootLoading(false);
      return;
    }
    setBootLoading(true);
    setBootError(null);
    try {
      const next = await loadBootstrap();
      setBoot(next);
    } catch (error) {
      setBoot(null);
      setBootError(getErrorMessage(error));
    } finally {
      setBootLoading(false);
    }
  }, [bootstrap]);

  useEffect(() => {
    void loadBootstrapState();
  }, [loadBootstrapState]);

  const loadWorkspace = useCallback(async () => {
    if (!boot?.authenticated) {
      return;
    }
    setWorkspaceError(null);
    setRefreshing(true);
    const [summaryResult, assetsResult, alertsResult] = await Promise.allSettled([
      api.overview(),
      api.assets(),
      api.alerts()
    ]);
    const failedMessages: string[] = [];
    if (summaryResult.status === 'fulfilled') {
      setSummary(summaryResult.value);
    } else {
      failedMessages.push(`요약: ${getErrorMessage(summaryResult.reason)}`);
      setSummary(fallbackSummary);
    }
    if (assetsResult.status === 'fulfilled') {
      setAssets(assetsResult.value);
    } else {
      failedMessages.push(`자산: ${getErrorMessage(assetsResult.reason)}`);
      setAssets(fallbackAssets);
    }
    if (alertsResult.status === 'fulfilled') {
      setAlerts(alertsResult.value);
    } else {
      failedMessages.push(`알림: ${getErrorMessage(alertsResult.reason)}`);
      setAlerts(fallbackAlerts);
    }
    setWorkspaceError(failedMessages.length ? failedMessages.join(' / ') : null);
    setLastUpdatedAt(new Date());
    setRefreshing(false);
  }, [boot?.authenticated]);

  useEffect(() => {
    void loadWorkspace();
  }, [loadWorkspace]);

  const surface = boot ? (boot.setupRequired ? 'setup' : boot.authenticated ? 'console' : 'login') : 'login';

  async function createAdmin(payload: { username: string; password: string; displayName?: string }) {
    setAuthBusy(true);
    setAuthError(null);
    try {
      await api.createAdmin(payload);
      setBoot({ setupRequired: false, authenticated: false });
    } catch (error) {
      setAuthError(getErrorMessage(error));
    } finally {
      setAuthBusy(false);
    }
  }

  async function login(payload: { username: string; password: string }) {
    setAuthBusy(true);
    setAuthError(null);
    try {
      const session = await api.login(payload);
      setBoot({ setupRequired: false, authenticated: true, user: session.user });
    } catch (error) {
      setAuthError(getErrorMessage(error));
      setBootError('인증에 실패했습니다.');
    } finally {
      setAuthBusy(false);
    }
  }

  async function logout() {
    try {
      await api.logout();
      setBoot({ setupRequired: false, authenticated: false });
      setWorkspaceError(null);
      setAuthError(null);
      setMenuSearch('');
      setActive('overview');
    } catch (error) {
      setWorkspaceError(getErrorMessage(error));
    }
  }

  async function createAsset(payload: { name: string; assetType: string; managementIp?: string; location?: string; description?: string }) {
    try {
      const created = await api.createAsset(payload);
      setAssets((current) => [created, ...current]);
      return created;
    } catch (error) {
      setWorkspaceError(getErrorMessage(error));
      return Promise.reject(error);
    }
  }

  async function updateAsset(id: number, payload: { name: string; location?: string; description?: string }) {
    try {
      const updated = await api.updateAsset(id, payload);
      setAssets((current) => current.map((asset) => (asset.id === id ? updated : asset)));
      return updated;
    } catch (error) {
      setWorkspaceError(getErrorMessage(error));
      return Promise.reject(error);
    }
  }

  async function deleteAsset(id: number) {
    try {
      await api.deleteAsset(id);
      setAssets((current) => current.filter((asset) => asset.id !== id));
    } catch (error) {
      setWorkspaceError(getErrorMessage(error));
      return Promise.reject(error);
    }
  }

  async function updateAlert(id: number, action: 'acknowledge' | 'resolve') {
    try {
      const updated = action === 'acknowledge'
        ? await api.acknowledgeAlert(id)
        : await api.resolveAlert(id);
      setAlerts((current) => current.map((alert) => (alert.id === id ? updated : alert)));
    } catch (error) {
      setWorkspaceError(getErrorMessage(error));
      return Promise.reject(error);
    }
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
    const menu = menuItemsForRole(boot?.user?.role ?? 'VIEWER');
    if (!menu.some((item) => item.id === itemId)) {
      return;
    }
    if (itemId === 'logparser') {
      void openLogparserUi();
      setMobileMenuOpen(false);
      return;
    }
    setActive(itemId);
    setMobileMenuOpen(false);
    focusViewHeadingAfterCommit();
  }

  // Hooks must run during bootstrap, login, and the authenticated console alike.
  const role = boot?.user?.role ?? 'VIEWER';
  const menu = useMemo(() => menuItemsForRole(role), [role]);
  const activeItem = menu.find((item) => item.id === active) ?? menu.find((item) => item.id === 'overview') ?? menu[0];
  const activeView = activeItem?.id ?? 'overview';
  const normalizedSearch = menuSearch.trim().toLowerCase();
  const availableMenu = useMemo(
    () => menu.filter((item) => (
      item.id === activeView ||
      item.label.toLowerCase().includes(normalizedSearch) ||
      item.id.toLowerCase().includes(normalizedSearch)
    )),
    [activeView, menu, normalizedSearch]
  );
  useEffect(() => {
    if (!menu.some((item) => item.id === active)) {
      setActive(menu[0]?.id ?? 'overview');
    }
  }, [menu, active]);

  if (bootLoading || !boot) {
    return (
      <main className="grid min-h-screen place-items-center bg-muted/30 px-4">
        <Card className="mx-auto w-full max-w-md">
          <CardHeader>
            <CardTitle>Castrelyx Manager</CardTitle>
            <CardDescription>
              {bootLoading ? '콘솔 구성 요소와 인증 상태를 확인 중입니다.' : '서버 연결 확인에 실패했습니다.'}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {bootError ? <p className="text-sm text-destructive">{bootError}</p> : <p className="text-sm text-muted-foreground">잠시만 기다려 주세요.</p>}
            <Button
              className="w-full"
              onClick={() => void loadBootstrapState()}
              variant={bootError ? 'destructive' : 'outline'}
            >
              {bootLoading ? '재시도 중...' : '다시 시도'}
            </Button>
          </CardContent>
        </Card>
      </main>
    );
  }

  if (surface === 'setup') {
    return <SetupView onCreate={createAdmin} errorMessage={authError} disabled={authBusy} />;
  }

  if (surface === 'login') {
    return <LoginView onLogin={login} errorMessage={authError} disabled={authBusy} />;
  }

  const activeAlerts = alerts.filter((alert) => alert.status === 'ACTIVE').length;

  return (
    <CommandShell
      active={activeView}
      menu={availableMenu}
      query={menuSearch}
      onSearch={setMenuSearch}
      onNavigate={openMenuItem}
      mobileOpen={mobileMenuOpen}
      onMobileOpen={setMobileMenuOpen}
      username={boot.user?.username ?? 'viewer'}
      role={role}
      summary={summary}
      activeAlerts={activeAlerts}
      refreshing={refreshing}
      updatedAt={toSyncText(lastUpdatedAt)}
      error={workspaceError}
      onRefresh={() => void loadWorkspace()}
      onLogout={() => void logout()}
    >
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
    </CommandShell>
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
      return <AgentDashboardView role={role} />;
    case 'agentLogs':
      return <AgentLogsView assets={assets} />;
    case 'snmp':
      return <SnmpDashboardView role={role} />;
    case 'alerts':
      return <AlertsView role={props.role} alerts={alerts} onAcknowledge={onAcknowledgeAlert} onResolve={onResolveAlert} />;
    case 'castrelsign':
      return <CastrelSignView role={props.role} />;
    case 'logparser':
      return <LogparserView />;
    case 'settings':
      return <SettingsView role={role} username={username} />;
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
