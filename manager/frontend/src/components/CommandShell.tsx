import { Activity, Bell, Boxes, FileText, Gauge, LogOut, Menu, Network, RefreshCw, Router, ScrollText, Search, Server, Settings, ShieldCheck, X } from 'lucide-react';
import { useEffect, useRef, type ReactNode } from 'react';
import type { DashboardSummary, Role } from '../lib/types';
import type { MenuItem } from '../lib/uiModel';

const icons = { overview: Gauge, alerts: Bell, traffic: Activity, assets: Boxes, agentLogs: FileText, agent: Server, snmp: Router, castrelsign: ShieldCheck, logparser: ScrollText, settings: Settings };
const groups = [
  { label: 'WORKSPACE', ids: ['overview', 'alerts', 'traffic', 'assets', 'agentLogs'] },
  { label: 'PLATFORM', ids: ['agent', 'snmp', 'castrelsign', 'logparser', 'settings'] }
];

type CommandShellProps = {
  children: ReactNode;
  active: string;
  menu: MenuItem[];
  query: string;
  onSearch: (query: string) => void;
  onNavigate: (id: string) => void;
  mobileOpen: boolean;
  onMobileOpen: (open: boolean) => void;
  username: string;
  role: Role;
  summary: DashboardSummary;
  activeAlerts: number;
  refreshing: boolean;
  updatedAt: string;
  error: string | null;
  onRefresh: () => void;
  onLogout: () => void;
};

export function CommandShell({ children, active, menu, query, onSearch, onNavigate, mobileOpen, onMobileOpen, username, role, summary, activeAlerts, refreshing, updatedAt, error, onRefresh, onLogout }: CommandShellProps) {
  const sidebarRef = useRef<HTMLElement>(null);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!mobileOpen) return;
    const sidebar = sidebarRef.current;
    sidebar?.querySelector<HTMLElement>('button, input')?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        onMobileOpen(false);
        menuButtonRef.current?.focus();
      }
      if (event.key !== 'Tab') return;
      const focusable = Array.from(sidebar?.querySelectorAll<HTMLElement>('button, input') ?? []).filter((element) => element.getClientRects().length > 0);
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => { document.body.style.overflow = previousOverflow; document.removeEventListener('keydown', onKeyDown); };
  }, [mobileOpen, onMobileOpen]);

  return (
    <div className="command-shell">
      <a className="cc-skip-link" href="#command-content">본문으로 이동</a>
      <aside ref={sidebarRef} className={`cc-sidebar ${mobileOpen ? 'is-open' : ''}`} aria-label="주요 메뉴">
        <div className="cc-brand">
          <Network size={30} strokeWidth={1.8} aria-hidden="true" />
          <div><h1>Castrelyx</h1><span>NMS Command Hub</span></div>
          <button className="cc-icon-button cc-mobile-only" aria-label="사이드바 닫기" onClick={() => { onMobileOpen(false); menuButtonRef.current?.focus(); }}><X size={18} /></button>
        </div>
        <label className="cc-menu-search"><Search size={16} aria-hidden="true" /><span className="sr-only">메뉴 검색</span><input value={query} onChange={(event) => onSearch(event.target.value)} placeholder="메뉴 검색..." />{query ? <button onClick={() => onSearch('')} aria-label="메뉴 검색 초기화"><X size={14} /></button> : null}</label>
        <nav aria-label="사이드바 메뉴" className="cc-navigation">
          {groups.map((group) => {
            const items = menu.filter((item) => group.ids.includes(item.id));
            return items.length ? <div className="cc-nav-group" key={group.label}>
              <p>{group.label}</p>
              {items.map((item) => {
                const Icon = icons[item.id as keyof typeof icons] ?? Network;
                return <button key={item.id} type="button" aria-current={active === item.id ? 'page' : undefined} onClick={() => onNavigate(item.id)}>
                  <Icon size={18} strokeWidth={1.7} aria-hidden="true" /><span>{item.label}</span>
                  {item.id === 'alerts' && activeAlerts > 0 ? <i className="cc-alert-dot" aria-hidden="true" /> : null}
                </button>;
              })}
            </div> : null;
          })}
        </nav>
        <div className="cc-sidebar-bottom">
          <div className="cc-status-summary">
            <h2>운영 상태</h2>
            <div><span><i className="cc-dot" />활성 자산</span><strong>{summary.activeAssets}</strong></div>
            <div><span><i className="cc-dot is-critical" />Critical 알림</span><strong>{summary.criticalAlerts}</strong></div>
            <div><span><i className="cc-dot is-muted" />에이전트</span><strong>{summary.agentHealth.healthy} / {summary.agentHealth.healthy + summary.agentHealth.stale}</strong></div>
          </div>
          <div className="cc-profile"><span className="cc-avatar" aria-hidden="true">{username.slice(0, 1).toUpperCase()}</span><div><strong>{username}</strong><span>{role === 'ADMIN' ? '시스템 관리자' : role === 'OPERATOR' ? '운영 담당자' : '조회 사용자'}</span></div><button className="cc-icon-button" onClick={onLogout} aria-label="로그아웃" title="로그아웃"><LogOut size={18} /></button></div>
        </div>
      </aside>
      {mobileOpen ? <button className="cc-menu-backdrop" aria-label="메뉴 닫기" onClick={() => { onMobileOpen(false); menuButtonRef.current?.focus(); }} /> : null}
      <div className="cc-content">
        <header className="cc-topbar">
          <button ref={menuButtonRef} className="cc-icon-button cc-mobile-only" aria-label="모바일 메뉴 열기" aria-expanded={mobileOpen} onClick={() => onMobileOpen(!mobileOpen)}><Menu size={20} /></button>
          <nav className="cc-breadcrumb" aria-label="탐색 경로"><span>{menu.find((item) => item.id === active)?.label ?? 'Operations'}</span><span aria-hidden="true">/</span><strong>커맨드 센터</strong></nav>
          <div className="cc-topbar-actions"><span className="cc-sync">마지막 동기화 <time>{updatedAt}</time></span><button className="cc-icon-button" aria-label={refreshing ? '새로고침 중' : '새로고침'} title="요약 새로고침" disabled={refreshing} onClick={onRefresh}><RefreshCw size={17} className={refreshing ? 'cc-spinning' : ''} /></button><span className="cc-topbar-divider" /><button className="cc-icon-button cc-alert-button" aria-label={`알림 센터, 활성 알림 ${activeAlerts}건`} onClick={() => onNavigate('alerts')}><Bell size={19} />{activeAlerts > 0 ? <span>{activeAlerts > 99 ? '99+' : activeAlerts}</span> : null}</button></div>
        </header>
        <main id="command-content" className="cc-main" tabIndex={-1}>
          {error ? <div className="cc-workspace-error" role="status"><strong>일부 정보를 불러오지 못했습니다.</strong><span>{error}</span></div> : null}
          {children}
        </main>
      </div>
    </div>
  );
}
