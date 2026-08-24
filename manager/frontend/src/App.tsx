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
  X,
  RefreshCw,
  Search
} from 'lucide-react';
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
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Input } from './components/ui/input';
import { Skeleton } from './components/ui/skeleton';
import './styles.css';

type AppProps = {
  bootstrap?: BootstrapState;
};

type IconType = typeof Gauge;

type NavVisual = {
  icon: IconType;
  hint: string;
};

const navVisuals: Record<string, NavVisual> = {
  overview: { icon: Gauge, hint: '운영 대시보드' },
  assets: { icon: Boxes, hint: '자산 관리' },
  traffic: { icon: Activity, hint: '트래픽' },
  agent: { icon: Server, hint: '수집 상태' },
  agentLogs: { icon: FileText, hint: 'Hunt / 로그 분석' },
  snmp: { icon: Router, hint: 'SNMP 상태' },
  alerts: { icon: Bell, hint: '이벤트 처리' },
  castrelsign: { icon: ShieldCheck, hint: '보안 자동화' },
  logparser: { icon: ScrollText, hint: '로그 파이프라인' },
  settings: { icon: Settings, hint: '통합 설정' }
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

  const role = boot.user?.role ?? 'VIEWER';
  const menu = menuItemsForRole(role);
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

  const activeAlerts = summary.criticalAlerts + alerts.filter((alert) => alert.status === 'ACTIVE').length;
  const isInitialLoad = !lastUpdatedAt;
  const filteredHint = normalizedSearch.length === 0 ? null : `메뉴 검색: ${normalizedSearch}`;
  

  return (
    <div className="min-h-screen bg-muted/20 text-foreground">
      <div className="mx-auto flex min-h-screen w-full max-w-[1600px]">
        <aside
          className={`fixed inset-y-0 left-0 z-40 w-72 border-r border-border bg-background px-3 py-4 transition-transform md:static md:translate-x-0 ${mobileMenuOpen ? 'translate-x-0' : '-translate-x-full'} `}
          aria-label="주요 메뉴"
        >
          <nav aria-label="사이드바 메뉴" className="flex flex-1 flex-col">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div className="grid size-8 place-items-center rounded-md border border-border bg-background">
                <Network className="text-primary" />
              </div>
              <div>
                <h1 className="text-sm font-semibold leading-tight">Castrelyx</h1>
                <p className="text-[11px] text-muted-foreground">NMS Command Hub</p>
              </div>
            </div>
            <Button
              aria-label="사이드바 닫기"
              className="md:hidden"
              variant="ghost"
              size="icon"
              onClick={() => setMobileMenuOpen(false)}
            >
              <X />
            </Button>
          </div>
          <p className="sr-only" id="navigation-hint">
            사이드 메뉴에서 운용 모듈을 선택하세요.
          </p>
          <div className="mt-4 flex flex-col gap-2">
            <label htmlFor="command-menu-search" className="sr-only">
              메뉴 검색
            </label>
            <div className="relative">
              <Input
                id="command-menu-search"
                value={menuSearch}
                onChange={(event) => setMenuSearch(event.target.value)}
                placeholder="메뉴 검색 (작업/자산/알림)"
                aria-describedby="navigation-hint"
              />
              {menuSearch ? (
                <Button
                  type="button"
                  className="absolute right-1.5 top-1/2 h-6 w-6 -translate-y-1/2"
                  size="icon"
                  variant="ghost"
                  aria-label="메뉴 검색 초기화"
                  onClick={() => setMenuSearch('')}
                >
                  <X />
                </Button>
              ) : null}
            </div>
            {filteredHint ? <p className="text-[11px] text-muted-foreground">검색 기준: {filteredHint}</p> : null}
          </div>
          <div className="mt-6 flex flex-col gap-2">
            {availableMenu.length > 0 ? availableMenu.map((item) => {
              const entry = navVisuals[item.id] ?? { icon: Network, hint: item.label };
              const Icon = entry.icon;
              return (
                <Button
                  aria-current={activeView === item.id ? 'page' : undefined}
                  data-slot="nav-button"
                  key={item.id}
                  onClick={() => openMenuItem(item.id)}
                  size="sm"
                  variant={activeView === item.id ? 'secondary' : 'ghost'}
                  className="justify-start gap-2"
                >
                  <Icon data-icon="inline-start" aria-hidden="true" />
                  <span className="truncate">{item.label}</span>
                  <span className="ml-auto text-[10px] text-muted-foreground">{entry.hint}</span>
                </Button>
              );
            }) : (
              <Card className="border-dashed">
                <CardContent className="text-sm text-muted-foreground">조건에 맞는 메뉴가 없습니다.</CardContent>
              </Card>
            )}
          </div>
          {workspaceError ? (
            <Card className="mt-4 border-destructive/30 bg-destructive/10">
              <CardHeader className="pb-2">
                <CardTitle className="text-sm">작업 오류</CardTitle>
              </CardHeader>
              <CardContent className="text-xs leading-relaxed text-destructive">{workspaceError}</CardContent>
            </Card>
          ) : null}
          <Card className="mt-5">
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">운영 상태 요약</CardTitle>
              <CardDescription className="text-xs">실시간 세션: {boot.user?.username ?? 'viewer'}</CardDescription>
            </CardHeader>
            <CardContent className="grid gap-2 pt-0 text-xs">
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">활성 에셋</span>
                <span>{summary.activeAssets}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Critical 알림</span>
                <span>{summary.criticalAlerts}</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Agent</span>
                <span>{summary.agentHealth.healthy} / {summary.agentHealth.healthy + summary.agentHealth.stale}</span>
              </div>
            </CardContent>
          </Card>
          <div className="mt-4 flex flex-col gap-2">
            <Button
              className="w-full justify-start"
              variant={role === 'ADMIN' ? 'outline' : 'ghost'}
              onClick={logout}
            >
              <LogOut data-icon="inline-start" aria-hidden="true" />
              로그아웃
            </Button>
          </div>
          </nav>
        </aside>

        {mobileMenuOpen ? (
          <button
            type="button"
            className="fixed inset-0 z-30 bg-background/70 backdrop-blur-sm md:hidden"
            aria-label="사이드바 닫기"
            onClick={() => setMobileMenuOpen(false)}
          />
        ) : null}

          <div className="flex min-h-screen flex-1 flex-col">
          <header className="sticky top-0 z-20 border-b border-border bg-background/85 backdrop-blur">
            <div className="flex flex-wrap items-center gap-2 px-3 py-3 md:px-5">
              <Button
                aria-expanded={mobileMenuOpen}
                aria-label="모바일 메뉴 열기"
                className="md:hidden"
                size="sm"
                variant="outline"
                onClick={() => setMobileMenuOpen((open) => !open)}
              >
                <Menu data-icon="inline-start" aria-hidden="true" />
                메뉴
              </Button>
              <div className="min-w-0">
                <p className="text-xs text-muted-foreground">Castrelyx Manager</p>
                <h1 className="truncate text-sm font-semibold leading-tight md:text-base">
                  {activeItem?.label ?? 'Operations'} 커맨드 센터
                </h1>
              </div>
              <div className="ml-auto flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => void loadWorkspace()}
                  disabled={refreshing}
                >
                  <RefreshCw data-icon="inline-start" />
                  {refreshing ? '새로고침 중' : '새로고침'}
                </Button>
                <Badge variant={activeAlerts > 0 ? 'critical' : 'success'}>
                  {activeAlerts > 0 ? `요청 알림 ${activeAlerts}건` : '정상'}
                </Badge>
                <Button
                  size="icon"
                  variant="ghost"
                  aria-label="로그아웃"
                  onClick={logout}
                >
                  <LogOut data-icon="inline-start" />
                </Button>
              </div>
            </div>
            <div className="flex items-center gap-2 border-t border-border px-3 py-2 md:px-5">
              <div className="relative flex-1">
                <Search className="text-muted-foreground absolute left-2 top-1/2 size-4 -translate-y-1/2" />
                <Input
                  className="h-8 w-full pl-8 md:w-72"
                  value={menuSearch}
                  onChange={(event) => setMenuSearch(event.target.value)}
                  placeholder="작업/자산/이벤트 검색"
                  aria-label="명령 검색"
                />
              </div>
              <span className="text-xs text-muted-foreground">{role}</span>
              <span className="ml-auto text-xs text-muted-foreground">세션: {boot.user?.username ?? 'viewer'}</span>
            </div>
            <div className="border-t border-border px-3 py-2 md:px-5">
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span aria-live="polite">
                  마지막 동기화: <strong className="text-foreground">{toSyncText(lastUpdatedAt)}</strong>
                </span>
                {refreshing ? <span className="text-primary">데이터 갱신 중</span> : null}
              </div>
            </div>
            <div className="overflow-x-auto border-t border-border px-3 py-2 md:px-5">
              <nav aria-label="빠른 이동" className="flex min-w-max gap-2">
                {availableMenu.length > 0 ? availableMenu.map((item) => {
                  const activeNav = item.id === activeView;
                  return (
                    <Button
                      key={item.id}
                      variant={activeNav ? 'secondary' : 'outline'}
                      size="sm"
                      onClick={() => openMenuItem(item.id)}
                      className="h-7 px-2.5 text-[11px]"
                    >
                      {item.label}
                    </Button>
                  );
                }) : (
                  <span className="text-xs text-muted-foreground">표시할 메뉴가 없습니다.</span>
                )}
              </nav>
            </div>
          </header>

          <main className="min-h-0 flex-1 overflow-auto px-3 py-3 md:px-5">
            <section className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
              <ShellStat
                label="총 자산"
                value={String(summary.activeAssets)}
                tone="secondary"
                hint="관측 가능한 자산 수"
                loading={isInitialLoad && refreshing}
              />
              <ShellStat
                label="Critical"
                value={String(summary.criticalAlerts)}
                tone={summary.criticalAlerts > 0 ? 'critical' : 'muted'}
                hint="진행 중 경고"
                loading={isInitialLoad && refreshing}
              />
              <ShellStat
                label="Agent 생존"
                value={`${summary.agentHealth.healthy}/${summary.agentHealth.stale}`}
                tone="secondary"
                hint="활성 / 정지"
                loading={isInitialLoad && refreshing}
              />
              <ShellStat
                label="SNMP 폴링"
                value={`${summary.snmpPollHealth.success}/${summary.snmpPollHealth.failure}`}
                tone="secondary"
                hint="성공 / 실패"
                loading={isInitialLoad && refreshing}
              />
              <ShellStat
                label="연결 동기화"
                value={refreshing ? '동기화 중' : '완료'}
                tone={refreshing ? 'warning' : workspaceError ? 'critical' : 'muted'}
                hint={lastUpdatedAt ? toSyncText(lastUpdatedAt) : '요약 데이터 상태'}
                loading={isInitialLoad && refreshing}
              />
            </section>
            <section className="mt-3 min-h-0">
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
        </div>
      </div>
    </div>
  );
}

function ShellStat({
  label,
  value,
  hint,
  tone,
  loading = false
}: {
  label: string;
  value: string;
  hint: string;
  tone: 'critical' | 'warning' | 'secondary' | 'muted';
  loading?: boolean;
}) {
  const toneClass = tone === 'critical'
    ? 'border-destructive/40 text-destructive'
  : tone === 'warning'
      ? 'border-[var(--status-amber)]/30 text-[var(--status-amber)]'
      : tone === 'secondary'
        ? 'border-border'
        : 'border-dashed border-muted';

  return (
    <Card className={`border ${toneClass}`}>
      <CardHeader className="pb-2">
        <CardTitle className="text-xs">{label}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        {loading ? <Skeleton className="h-6 w-20" /> : <p className="text-xl font-semibold">{value}</p>}
        <p className="text-[11px] text-muted-foreground">{hint}</p>
      </CardContent>
    </Card>
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
