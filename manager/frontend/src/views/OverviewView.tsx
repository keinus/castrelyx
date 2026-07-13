import {
  ArrowDownUp,
  Check,
  CheckCircle2,
  ChevronDown,
  CircleAlert,
  List,
  ListFilter,
  Search,
  ShieldAlert,
  UserCircle,
  UserPlus,
  XCircle
} from 'lucide-react';
import {
  useCallback,
  useDeferredValue,
  useEffect,
  useMemo,
  useRef,
  useState
} from 'react';
import { Line, LineChart } from 'recharts';
import { api } from '../lib/api';
import type {
  AgentDashboard,
  AgentLogEvent,
  AlertRow,
  Asset,
  AssetMetricDetail,
  AssetMetricSummary,
  AssetMetricsOverview,
  DashboardSummary,
  InterfaceTraffic
} from '../lib/types';
import { formatBps } from '../lib/uiModel';

type OverviewViewProps = {
  summary: DashboardSummary;
  alerts: AlertRow[];
  assets?: Asset[];
  username?: string;
  navigationItems?: Array<{ id: string; label: string }>;
  onNavigate?: (id: string) => void;
  onAcknowledgeAlert?: (id: number) => Promise<void>;
};

type RangeValue = '15m' | '1h' | '24h';
type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM';
type ResourceSortKey = 'health' | 'name' | 'cpu' | 'memory' | 'diskIo' | 'network' | 'freshness';
type SortDirection = 'asc' | 'desc';

type ResourceSort = {
  key: ResourceSortKey;
  direction: SortDirection;
};

type LoadResult<T> = {
  ok: boolean;
  value: T;
};

type PriorityRow = {
  asset: AssetMetricSummary;
  severity: Severity;
  signals: string[];
  actionable: boolean;
  ageSeconds: number;
};

const REQUEST_TIMEOUT_MS = 5000;
const REFRESH_MS = 30_000;
const severityRank: Record<Severity, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2 };

const emptyAssetMetrics: AssetMetricsOverview = {
  range: '1h',
  summary: {
    totalAssets: 0,
    observedAssets: 0,
    staleAssets: 0,
    criticalAssets: 0,
    warningAssets: 0
  },
  assets: []
};

export function OverviewView({
  summary,
  alerts,
  assets = [],
  username = 'operator',
  navigationItems = [],
  onNavigate,
  onAcknowledgeAlert
}: OverviewViewProps) {
  const [range, setRange] = useState<RangeValue>('15m');
  const [assetMetrics, setAssetMetrics] = useState<AssetMetricsOverview>(emptyAssetMetrics);
  const [agentDashboard, setAgentDashboard] = useState<AgentDashboard>({});
  const [trafficRows, setTrafficRows] = useState<InterfaceTraffic[]>(summary.trafficTopInterfaces ?? []);
  const [events, setEvents] = useState<AgentLogEvent[]>([]);
  const [selectedAssetUid, setSelectedAssetUid] = useState<string | null>(null);
  const [detail, setDetail] = useState<AssetMetricDetail | null>(null);
  const [query, setQuery] = useState('');
  const [resourceSort, setResourceSort] = useState<ResourceSort>({ key: 'health', direction: 'desc' });
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');
  const [lastUpdatedAt, setLastUpdatedAt] = useState('');
  const [detailRefreshToken, setDetailRefreshToken] = useState(0);
  const [acknowledged, setAcknowledged] = useState(() => new Set<string>());
  const [toast, setToast] = useState('');
  const [mobileSearchOpen, setMobileSearchOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const metricsRef = useRef(assetMetrics);
  const agentRef = useRef(agentDashboard);
  const eventsRef = useRef(events);
  const trafficRef = useRef(trafficRows);
  const rangeRef = useRef(range);
  const deferredQuery = useDeferredValue(query.trim().toLowerCase());

  useEffect(() => {
    metricsRef.current = assetMetrics;
  }, [assetMetrics]);

  useEffect(() => {
    agentRef.current = agentDashboard;
  }, [agentDashboard]);

  useEffect(() => {
    eventsRef.current = events;
  }, [events]);

  useEffect(() => {
    trafficRef.current = trafficRows;
  }, [trafficRows]);

  useEffect(() => {
    rangeRef.current = range;
  }, [range]);

  const loadOverview = useCallback(async (mode: 'initial' | 'refresh' = 'initial') => {
    const hasKnownData = metricsRef.current.assets.length > 0;
    if (mode === 'refresh' && hasKnownData) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError('');

    const [metricResult, agentResult, eventResult, trafficResult] = await Promise.all([
      loadWithTimeout(api.assetMetrics(range), metricsRef.current),
      loadWithTimeout(api.agentDashboard(), agentRef.current),
      loadWithTimeout(api.agentLogs(range, 'ALL', 200), eventsRef.current),
      loadWithTimeout(api.trafficInterfaces(range), trafficRef.current)
    ]);

    if (metricResult.ok || !hasKnownData) {
      setAssetMetrics(metricResult.value);
      setDetailRefreshToken((value) => value + 1);
    }
    if (agentResult.ok) {
      setAgentDashboard(agentResult.value);
    }
    if (eventResult.ok) {
      setEvents(eventResult.value);
    }
    if (trafficResult.ok) {
      setTrafficRows(trafficResult.value);
    }
    if ([metricResult, agentResult, eventResult, trafficResult].some((result) => !result.ok)) {
      setError('일부 관제 정보가 지연되고 있습니다. 마지막 정상 수집값을 유지합니다.');
    }
    setLastUpdatedAt(new Date().toISOString());
    setLoading(false);
    setRefreshing(false);
  }, [range]);

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    const timer = window.setInterval(() => void loadOverview('refresh'), REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [loadOverview]);

  useEffect(() => {
    if (!toast) {
      return undefined;
    }
    const timer = window.setTimeout(() => setToast(''), 2400);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const managedAssets = useMemo(
    () => buildManagedAssets(assetMetrics.assets, assets),
    [assetMetrics.assets, assets]
  );
  const activeAlerts = useMemo(
    () => alerts.filter((alert) => alert.status === 'ACTIVE'),
    [alerts]
  );
  const priorityRows = useMemo(
    () => managedAssets.map((asset) => buildPriorityRow(asset)),
    [managedAssets]
  );
  const actionableCount = useMemo(
    () => priorityRows.filter((row) => row.actionable).length,
    [priorityRows]
  );
  const resourceRows = useMemo(() => {
    const rows = priorityRows.filter((row) => {
      const searchable = [
        row.asset.name,
        row.asset.assetUid,
        row.asset.managementIp,
        row.asset.assetType,
        row.asset.location,
        ...row.signals
      ].filter(Boolean).join(' ').toLowerCase();
      return !deferredQuery || searchable.includes(deferredQuery);
    });
    return rows.sort((left, right) => compareResourceRows(left, right, resourceSort));
  }, [deferredQuery, priorityRows, resourceSort]);

  const fleetSummary = useMemo(
    () => buildFleetResourceSummary(managedAssets),
    [managedAssets]
  );

  const selectedAsset = useMemo(() => {
    const visible = resourceRows.find((row) => row.asset.assetUid === selectedAssetUid)?.asset;
    if (visible) {
      return visible;
    }
    if (resourceRows[0]) {
      return resourceRows[0].asset;
    }
    return managedAssets.find((asset) => asset.assetUid === selectedAssetUid) ?? managedAssets[0] ?? null;
  }, [managedAssets, resourceRows, selectedAssetUid]);
  const selectedRow = selectedAsset ? buildPriorityRow(selectedAsset) : null;
  const selectedDetail = detail?.asset.assetUid === selectedAsset?.assetUid ? detail : null;

  useEffect(() => {
    if (!selectedAsset) {
      setSelectedAssetUid(null);
      setDetail(null);
      return;
    }
    if (selectedAssetUid !== selectedAsset.assetUid) {
      setSelectedAssetUid(selectedAsset.assetUid);
    }
  }, [selectedAsset, selectedAssetUid]);

  useEffect(() => {
    if (!selectedAsset?.assetUid) {
      return;
    }
    let cancelled = false;
    api.assetMetricDetail(selectedAsset.assetUid, rangeRef.current)
      .then((response) => {
        if (!cancelled) {
          setDetail(response);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDetail((current) => current?.asset.assetUid === selectedAsset.assetUid ? current : null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [detailRefreshToken, selectedAsset?.assetUid]);

  function sortResources(key: ResourceSortKey) {
    setResourceSort((current) => ({
      key,
      direction: current.key === key && current.direction === 'desc' ? 'asc' : 'desc'
    }));
  }

  function investigateSelected() {
    if (!selectedAsset) {
      return;
    }
    setToast(`${selectedAsset.name}의 로그·프로세스 증거 화면으로 이동합니다.`);
    onNavigate?.('agentLogs');
  }

  async function acknowledgeSelected() {
    if (!selectedAsset) {
      return;
    }
    const matchingAlert = activeAlerts.find((alert) => {
      const alertText = `${alert.title} ${alert.detail ?? ''}`.toLowerCase();
      return [selectedAsset.name, selectedAsset.assetUid, selectedAsset.managementIp]
        .filter(Boolean)
        .some((needle) => alertText.includes(String(needle).toLowerCase()));
    });
    if (!matchingAlert || !onAcknowledgeAlert) {
      setToast(`${selectedAsset.name}에 연결된 활성 알림을 알림 센터에서 확인합니다.`);
      onNavigate?.('alerts');
      return;
    }
    try {
      await onAcknowledgeAlert(matchingAlert.id);
      setAcknowledged((current) => new Set(current).add(selectedAsset.assetUid));
      setToast(`${selectedAsset.name} 알림을 확인 처리했습니다.`);
    } catch {
      setToast('알림 확인 처리에 실패했습니다. 잠시 후 다시 시도하세요.');
    }
  }

  const coverage = buildCoverage(managedAssets, events);
  const mergedTraffic = mergeTrafficRows([trafficRows, summary.trafficTopInterfaces ?? []]);
  const selectedEvents = events
    .filter((event) => event.assetUid === selectedAsset?.assetUid)
    .sort(sortEventsNewestFirst)
    .slice(0, 5);

  return (
    <section className="overview-action-rail" aria-label="SOC 및 NMS 운영 대시보드">
      <header className="ar-topbar">
        <div className="ar-title-group">
          <h2 tabIndex={-1}>Action Rail Command Center</h2>
          <span>SOC + NMS</span>
        </div>
        <div className="ar-topbar-controls">
          <span className="ar-live-control"><i aria-hidden="true" /> Live <ChevronDown size={13} aria-hidden="true" /></span>
          <label className="ar-range-control">
            <span className="sr-only">시간 범위</span>
            <select value={range} onChange={(event) => setRange(event.target.value as RangeValue)}>
              <option value="15m">Last 15m</option>
              <option value="1h">Last 1h</option>
              <option value="24h">Last 24h</option>
            </select>
            <ChevronDown size={13} aria-hidden="true" />
          </label>
          <label className="ar-global-search">
            <Search size={16} aria-hidden="true" />
            <span className="sr-only">자산, IP, 신호 검색</span>
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="검색 (자산, IP, 신호)"
            />
          </label>
        </div>
        <div className="ar-status-group">
          <span className="ar-updated"><i aria-hidden="true" /> 마지막 업데이트 {formatTime(lastUpdatedAt)} (30s)</span>
          <span className="ar-user-menu"><UserCircle size={18} aria-hidden="true" /> {username}</span>
        </div>
      </header>

      <header className="ar-mobile-header">
        <button type="button" aria-label="메뉴 열기" aria-expanded={mobileNavOpen} onClick={() => setMobileNavOpen((open) => !open)}>
          <List size={21} aria-hidden="true" />
        </button>
        <div><strong>Action Rail Command Center</strong><span>SOC + NMS</span></div>
        <button type="button" aria-label="검색 열기" aria-expanded={mobileSearchOpen} onClick={() => setMobileSearchOpen((open) => !open)}>
          <Search size={20} aria-hidden="true" />
        </button>
      </header>
      <div className="ar-mobile-status"><span><i aria-hidden="true" /> Live</span><time>{formatTime(lastUpdatedAt)}</time><b>(30s)</b></div>
      {mobileNavOpen ? (
        <nav className="ar-mobile-nav" aria-label="모바일 메뉴">
          {navigationItems.map((item) => (
            <button key={item.id} type="button" onClick={() => { setMobileNavOpen(false); onNavigate?.(item.id); }}>
              {item.label}
            </button>
          ))}
        </nav>
      ) : null}
      {mobileSearchOpen ? (
        <div className="ar-mobile-search">
          <Search size={17} aria-hidden="true" />
          <label className="sr-only" htmlFor="mobile-operations-search">자산, IP, 신호 검색</label>
          <input
            id="mobile-operations-search"
            autoFocus
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="자산, IP, 신호 검색"
          />
          <button type="button" onClick={() => setMobileSearchOpen(false)}>닫기</button>
        </div>
      ) : null}

      {error ? <div className="ar-notice" role="status">{error}</div> : null}

      <div className="ar-workspace ar-resource-workspace">
        <FleetResourceSummary
          summary={fleetSummary}
          range={range}
          activeAlerts={activeAlerts.length}
        />
        <FleetResourceMatrix
          rows={resourceRows}
          total={managedAssets.length}
          reporting={fleetSummary.reporting}
          attention={actionableCount}
          selectedUid={selectedAsset?.assetUid ?? null}
          sort={resourceSort}
          loading={loading}
          onSort={sortResources}
          onSelect={setSelectedAssetUid}
        />
        <div className="ar-resource-lower-grid">
          <Inspector
            asset={selectedAsset}
            row={selectedRow}
            detail={selectedDetail}
            recentEvents={selectedEvents}
            traffic={mergedTraffic}
            acknowledged={selectedAsset ? acknowledged.has(selectedAsset.assetUid) : false}
            onInvestigate={investigateSelected}
            onAcknowledge={() => void acknowledgeSelected()}
          />
          <EventPanel events={events} onOpenAll={() => onNavigate?.('agentLogs')} />
        </div>
        <CoveragePanel coverage={coverage} total={managedAssets.length} />
      </div>

      {refreshing ? <div className="ar-refresh-state" role="status">수집값 갱신 중</div> : null}
      {toast ? <div className="ar-toast" role="status"><Check size={17} aria-hidden="true" />{toast}</div> : null}
    </section>
  );
}

function FleetResourceSummary({
  summary,
  range,
  activeAlerts
}: {
  summary: ReturnType<typeof buildFleetResourceSummary>;
  range: RangeValue;
  activeAlerts: number;
}) {
  const totalDiskIops = summary.diskReadIops == null && summary.diskWriteIops == null
    ? null
    : safeNumber(summary.diskReadIops) + safeNumber(summary.diskWriteIops);
  return (
    <section className="ar-panel ar-fleet-summary" aria-labelledby="ar-fleet-summary-title">
      <div className="ar-fleet-summary-heading">
        <div>
          <h3 id="ar-fleet-summary-title">전체 장비 리소스 상태</h3>
          <p>
            CPU·RAM은 선택 범위의 최신 수집값, Disk·Network I/O는 선택 범위에서 계산한 장비별 평균 처리량의 합계입니다.
          </p>
        </div>
        <span className="ar-fleet-range">{rangeLabel(range)} · 30초 갱신</span>
      </div>
      <div className="ar-fleet-summary-grid">
        <div className="ar-fleet-summary-card" aria-label={`수집 장비 ${summary.reporting}대, 전체 ${summary.total}대`}>
          <span>장비 수집</span>
          <strong>{summary.reporting}<small> / {summary.total}</small></strong>
          <em>지연 {summary.stale} · 확인 필요 {summary.attention}</em>
        </div>
        <div className={`ar-fleet-summary-card ${metricTone(summary.avgCpuUsagePct)}`} aria-label={`전체 장비 평균 CPU ${formatPercent(summary.avgCpuUsagePct)}`}>
          <span>평균 CPU</span>
          <strong>{formatPercent(summary.avgCpuUsagePct)}</strong>
          <em>{summary.cpuReporting}대 수집</em>
        </div>
        <div className={`ar-fleet-summary-card ${metricTone(summary.avgMemoryUsagePct)}`} aria-label={`전체 장비 평균 RAM ${formatPercent(summary.avgMemoryUsagePct)}`}>
          <span>평균 RAM</span>
          <strong>{formatPercent(summary.avgMemoryUsagePct)}</strong>
          <em>{summary.memoryReporting}대 수집</em>
        </div>
        <div className="ar-fleet-summary-card ar-fleet-summary-io" aria-label={`Disk I/O 읽기 ${formatBytesPerSecond(summary.diskReadBps)}, 쓰기 ${formatBytesPerSecond(summary.diskWriteBps)}`}>
          <span>Disk I/O</span>
          <strong><b>R</b> {formatBytesPerSecond(summary.diskReadBps)}</strong>
          <strong><b>W</b> {formatBytesPerSecond(summary.diskWriteBps)}</strong>
          <em>{formatIops(totalDiskIops)} · {summary.diskReporting}대</em>
        </div>
        <div className="ar-fleet-summary-card ar-fleet-summary-io" aria-label={`Network I/O 수신 ${formatOptionalBps(summary.networkInBps)}, 송신 ${formatOptionalBps(summary.networkOutBps)}`}>
          <span>Network I/O</span>
          <strong><b>RX</b> {formatOptionalBps(summary.networkInBps)}</strong>
          <strong><b>TX</b> {formatOptionalBps(summary.networkOutBps)}</strong>
          <em>{summary.networkReporting}대 수집 · 활성 알림 {activeAlerts}</em>
        </div>
      </div>
    </section>
  );
}

function FleetResourceMatrix({
  rows,
  total,
  reporting,
  attention,
  selectedUid,
  sort,
  loading,
  onSort,
  onSelect
}: {
  rows: PriorityRow[];
  total: number;
  reporting: number;
  attention: number;
  selectedUid: string | null;
  sort: ResourceSort;
  loading: boolean;
  onSort: (key: ResourceSortKey) => void;
  onSelect: (assetUid: string) => void;
}) {
  return (
    <section className="ar-panel ar-resource-matrix" aria-labelledby="ar-resource-matrix-title">
      <div className="ar-resource-matrix-heading">
        <div>
          <h3 id="ar-resource-matrix-title">전체 장비 리소스 매트릭스</h3>
          <p>모든 등록·관측 장비의 CPU, RAM, Disk I/O, Network I/O를 비교합니다.</p>
        </div>
        <div className="ar-resource-matrix-counts">
          <span>표시 <strong>{rows.length}</strong> / {total}</span>
          <span>수집 <strong>{reporting}</strong></span>
          <span>확인 필요 <strong>{attention}</strong></span>
        </div>
      </div>

      {loading && total === 0 ? (
        <div className="ar-empty ar-resource-empty"><ListFilter size={28} aria-hidden="true" /><strong>리소스 데이터를 불러오는 중입니다.</strong></div>
      ) : rows.length === 0 ? (
        <div className="ar-empty ar-resource-empty"><Search size={28} aria-hidden="true" /><strong>검색 조건에 맞는 장비가 없습니다.</strong><span>장비 이름, IP 또는 위치로 다시 검색하세요.</span></div>
      ) : (
        <>
          <div className="ar-resource-table-scroll">
            <table className="ar-resource-table">
              <thead>
                <tr>
                  <SortableResourceHeader label="장비" sortKey="name" sort={sort} onSort={onSort} />
                  <SortableResourceHeader label="상태" sortKey="health" sort={sort} onSort={onSort} />
                  <SortableResourceHeader label="CPU" sortKey="cpu" sort={sort} onSort={onSort} />
                  <SortableResourceHeader label="RAM" sortKey="memory" sort={sort} onSort={onSort} />
                  <SortableResourceHeader label="Disk I/O" sortKey="diskIo" sort={sort} onSort={onSort} />
                  <SortableResourceHeader label="Network I/O" sortKey="network" sort={sort} onSort={onSort} />
                  <SortableResourceHeader label="최근 수집" sortKey="freshness" sort={sort} onSort={onSort} />
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const selected = selectedUid === row.asset.assetUid;
                  return (
                    <tr
                      key={row.asset.assetUid}
                      className={`${resourceHealthTone(row.asset)} ${selected ? 'selected' : ''}`}
                      aria-label={`${row.asset.name} 리소스 상태`}
                    >
                      <td>
                        <button
                          className="ar-resource-asset-button"
                          type="button"
                          aria-pressed={selected}
                          onClick={() => onSelect(row.asset.assetUid)}
                        >
                          <strong>{row.asset.name}</strong>
                          <span>{row.asset.managementIp ?? row.asset.assetUid}</span>
                          <small>{row.asset.assetType} · {row.asset.location ?? '위치 미지정'}</small>
                        </button>
                      </td>
                      <td><ResourceHealth asset={row.asset} /></td>
                      <td><ResourcePercent assetName={row.asset.name} label="CPU" value={row.asset.metrics.cpuUsagePct} /></td>
                      <td><ResourcePercent assetName={row.asset.name} label="RAM" value={row.asset.metrics.memoryUsagePct} /></td>
                      <td><DiskIoResource asset={row.asset} /></td>
                      <td><NetworkIoResource asset={row.asset} /></td>
                      <td><ResourceFreshness asset={row.asset} /></td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div className="ar-resource-mobile-cards" role="list" aria-label="장비별 리소스 상태">
            {rows.map((row) => {
              const selected = selectedUid === row.asset.assetUid;
              return (
                <article
                  className={`ar-resource-mobile-card ${resourceHealthTone(row.asset)} ${selected ? 'selected' : ''}`}
                  key={row.asset.assetUid}
                  role="listitem"
                  aria-label={`${row.asset.name} 리소스 상태`}
                >
                  <header className="ar-resource-mobile-card-header">
                    <button type="button" aria-pressed={selected} onClick={() => onSelect(row.asset.assetUid)}>
                      <strong>{row.asset.name}</strong>
                      <span>{row.asset.managementIp ?? row.asset.assetUid}</span>
                    </button>
                    <ResourceHealth asset={row.asset} />
                  </header>
                  <div className="ar-resource-mobile-card-grid">
                    <section><h4>CPU</h4><ResourcePercent assetName={row.asset.name} label="CPU" value={row.asset.metrics.cpuUsagePct} /></section>
                    <section><h4>RAM</h4><ResourcePercent assetName={row.asset.name} label="RAM" value={row.asset.metrics.memoryUsagePct} /></section>
                    <section><h4>Disk I/O</h4><DiskIoResource asset={row.asset} /></section>
                    <section><h4>Network I/O</h4><NetworkIoResource asset={row.asset} /></section>
                  </div>
                  <footer><ResourceFreshness asset={row.asset} /></footer>
                </article>
              );
            })}
          </div>
        </>
      )}
      <footer className="ar-panel-footer ar-resource-matrix-footer">
        응답에 포함된 0은 그대로 표시하고, 수집 소스를 확인할 수 없는 값은 대시(—)로 구분합니다.
      </footer>
    </section>
  );
}

function SortableResourceHeader({
  label,
  sortKey,
  sort,
  onSort
}: {
  label: string;
  sortKey: ResourceSortKey;
  sort: ResourceSort;
  onSort: (key: ResourceSortKey) => void;
}) {
  const active = sort.key === sortKey;
  const ariaSort = active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none';
  return (
    <th scope="col" aria-sort={ariaSort}>
      <button type="button" onClick={() => onSort(sortKey)} aria-label={`${label} 기준 ${active && sort.direction === 'desc' ? '오름차순' : '내림차순'} 정렬`}>
        {label}<ArrowDownUp size={13} aria-hidden="true" />
      </button>
    </th>
  );
}

function ResourceHealth({ asset }: { asset: AssetMetricSummary }) {
  const label = asset.sources.observed !== true
    ? '미수집'
    : asset.health === 'critical'
      ? asset.stale ? '위험 · 수집 지연' : '위험'
      : asset.health === 'warning'
        ? asset.stale ? '주의 · 수집 지연' : '주의'
        : asset.stale
          ? '수집 지연'
        : asset.health === 'healthy'
          ? '정상'
          : '미수집';
  return (
    <span className={`ar-resource-health ${resourceHealthTone(asset)}`}>
      <i aria-hidden="true" />{label}
    </span>
  );
}

function ResourcePercent({
  assetName,
  label,
  value
}: {
  assetName: string;
  label: string;
  value?: number | null;
}) {
  const number = finiteNumber(value);
  if (number == null) {
    return <span className="ar-resource-missing" aria-label={`${assetName} ${label} 미수집`}>—<small>미수집</small></span>;
  }
  const bounded = Math.min(Math.max(number, 0), 100);
  return (
    <div className={`ar-resource-percent ${metricTone(number)}`} aria-label={`${assetName} ${label} ${number.toFixed(1)} 퍼센트`}>
      <strong>{number.toFixed(1)}%</strong>
      <span
        className="ar-resource-percent-track"
        role="meter"
        aria-label={`${label} 사용률`}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={bounded}
      >
        <i style={{ width: `${bounded}%` }} />
      </span>
    </div>
  );
}

function DiskIoResource({ asset }: { asset: AssetMetricSummary }) {
  const metrics = asset.metrics;
  const readBps = finiteNumber(metrics.diskReadBps);
  const writeBps = finiteNumber(metrics.diskWriteBps);
  const readIops = finiteNumber(metrics.diskReadIops);
  const writeIops = finiteNumber(metrics.diskWriteIops);
  const utilization = finiteNumber(metrics.diskIoUtilizationPct);
  const available = asset.sources.diskIo === true && [readBps, writeBps, readIops, writeIops, utilization].some((value) => value != null);
  if (!available) {
    return <span className="ar-resource-missing" aria-label={`${asset.name} Disk I/O 미수집`}>—<small>미수집</small></span>;
  }
  return (
    <div className="ar-resource-io" aria-label={`${asset.name} Disk I/O 읽기 ${formatBytesPerSecond(readBps)}, 쓰기 ${formatBytesPerSecond(writeBps)}`}>
      <span><b>R</b><strong>{formatBytesPerSecond(readBps)}</strong></span>
      <span><b>W</b><strong>{formatBytesPerSecond(writeBps)}</strong></span>
      <small>R {formatIops(readIops)} · W {formatIops(writeIops)} · util {formatPercent(utilization)}</small>
    </div>
  );
}

function NetworkIoResource({ asset }: { asset: AssetMetricSummary }) {
  const inBps = finiteNumber(asset.metrics.networkInBps);
  const outBps = finiteNumber(asset.metrics.networkOutBps);
  const available = asset.sources.traffic === true && (inBps != null || outBps != null);
  if (!available) {
    return <span className="ar-resource-missing" aria-label={`${asset.name} Network I/O 미수집`}>—<small>미수집</small></span>;
  }
  return (
    <div className="ar-resource-io" aria-label={`${asset.name} Network I/O 수신 ${formatOptionalBps(inBps)}, 송신 ${formatOptionalBps(outBps)}`}>
      <span><b>RX</b><strong>{formatOptionalBps(inBps)}</strong></span>
      <span><b>TX</b><strong>{formatOptionalBps(outBps)}</strong></span>
      <small>interface errors {formatCount(asset.metrics.interfaceErrorCount)}</small>
    </div>
  );
}

function ResourceFreshness({ asset }: { asset: AssetMetricSummary }) {
  return (
    <span className={`ar-resource-freshness ${asset.stale ? 'stale' : ''}`}>
      <strong>{formatAge(asset.lastSeenAt)}</strong>
      <small>{formatTime(asset.lastSeenAt)}</small>
    </span>
  );
}

function Inspector({
  asset,
  row,
  detail,
  recentEvents,
  traffic,
  acknowledged,
  onInvestigate,
  onAcknowledge
}: {
  asset: AssetMetricSummary | null;
  row: PriorityRow | null;
  detail: AssetMetricDetail | null;
  recentEvents: AgentLogEvent[];
  traffic: InterfaceTraffic[];
  acknowledged: boolean;
  onInvestigate: () => void;
  onAcknowledge: () => void;
}) {
  if (!asset || !row) {
    return (
      <aside className="ar-panel ar-inspector ar-resource-detail ar-inspector-empty">
        <CheckCircle2 size={32} aria-hidden="true" />
        <strong>표시할 자산이 없습니다.</strong>
        <span>자산을 등록하거나 수집 연결을 확인하세요.</span>
      </aside>
    );
  }

  const assetTrafficRows = [...(detail?.interfaces?.length ? detail.interfaces : traffic.filter((item) => item.assetUid === asset.assetUid))]
    .sort((left, right) => totalTraffic(right) - totalTraffic(left));
  const primaryInterface = assetTrafficRows[0];
  const failedServices = detail?.services?.filter((service) => !isHealthyService(service.status)).length
    ?? safeNumber(asset.security?.failedServices);
  const downInterfaces = detail?.interfaceStates?.filter((item) => !isHealthyInterface(item.operStatus ?? item.status)).length
    ?? safeNumber(asset.security?.interfacesDown ?? asset.signals?.interfacesDown);
  const processes = detail?.processes?.length ?? 0;
  const sockets = detail?.sockets?.length ?? 0;
  const listening = detail?.sockets?.filter((socket) => socket.state?.toUpperCase() === 'LISTEN').length ?? 0;
  const networkInPoints = detail?.series.network?.map((point) => safeNumber(point.inBps)) ?? [];
  const networkOutPoints = detail?.series.network?.map((point) => safeNumber(point.outBps)) ?? [];
  const metrics = detail?.asset.metrics ?? asset.metrics;

  return (
    <aside className={`ar-panel ar-inspector ar-resource-detail ${row.severity.toLowerCase()}`} aria-label={`${asset.name} 자산 인스펙터`}>
      <div className="ar-drawer-handle" aria-hidden="true" />
      <div className="ar-panel-heading ar-inspector-heading"><h3>자산 인스펙터</h3><span><i aria-hidden="true" /> Live</span></div>
      <div className="ar-inspector-identity">
        <span className="ar-inspector-shield"><SeverityIcon severity={row.severity} size={34} /></span>
        <div><strong>{asset.name}</strong><span>IP {asset.managementIp ?? '-'}</span><small>Agent 수집 <i aria-hidden="true" /> {asset.stale ? 'Delayed' : 'Live'} (30s)</small></div>
        <div className="ar-inspector-severity"><span>{formatAge(asset.lastSeenAt)}</span><strong>{acknowledged ? 'ACK' : row.severity}</strong></div>
      </div>
      <div className="ar-metric-grid">
        <Metric label="CPU" value={metrics.cpuUsagePct} />
        <Metric label="MEM" value={metrics.memoryUsagePct} />
        <Metric label="DISK CAP" value={metrics.diskUsagePct} />
        <Metric label="DISK I/O" value={asset.sources.diskIo ? metrics.diskIoUtilizationPct : null} />
        <Metric label="TEMP" value={metrics.temperatureCelsius} suffix="°C" />
      </div>
      <div className="ar-state-grid">
        <StateValue label="서비스 실패" value={failedServices} danger={failedServices > 0} />
        <StateValue label="인터페이스 다운" value={downInterfaces} danger={downInterfaces > 0} />
        <StateValue label="프로세스" value={processes} />
        <StateValue label="소켓" value={sockets} />
        <StateValue label="리슨닝" value={listening} />
      </div>
      <div className="ar-inspector-section">
        <div className="ar-section-title"><h4>네트워크 인터페이스 ({assetTrafficRows.length})</h4></div>
        {primaryInterface ? (
          <div className="ar-interface-row">
            <strong>{primaryInterface.interfaceName}</strong>
            <span>RX</span><b>{formatBps(primaryInterface.inBps)}</b><MiniSparkline values={networkInPoints.length ? networkInPoints : [primaryInterface.inBps]} />
            <span>TX</span><b>{formatBps(primaryInterface.outBps)}</b><MiniSparkline values={networkOutPoints.length ? networkOutPoints : [primaryInterface.outBps]} color="#74a7d9" />
            <em><i aria-hidden="true" /> {primaryInterface.status || 'up'}</em>
          </div>
        ) : <div className="ar-inline-empty">수집된 인터페이스 트래픽이 없습니다.</div>}
      </div>
      <div className="ar-inspector-section ar-resource-detail-io">
        <div className="ar-section-title"><h4>선택 장비 I/O</h4><span>{detail?.range ?? '선택 범위'}</span></div>
        <div className="ar-resource-detail-io-grid">
          <div><span>Disk I/O</span><DiskIoResource asset={detail?.asset ?? asset} /></div>
          <div><span>Network I/O</span><NetworkIoResource asset={detail?.asset ?? asset} /></div>
        </div>
      </div>
      <div className="ar-inspector-section ar-recent-events">
        <div className="ar-section-title"><h4>최근 이벤트</h4><span>최신순</span></div>
        {recentEvents.length ? recentEvents.map((event, index) => (
          <div className="ar-mini-event" key={`${event.observedAt ?? event.eventTime}-${event.eventType}-${index}`}>
            <time>{formatTime(event.observedAt ?? event.eventTime)}</time>
            <SeverityIcon severity={eventSeverity(event)} size={16} />
            <span>{event.eventType ?? event.eventCategory ?? 'event'}</span>
          </div>
        )) : <div className="ar-inline-empty">최근 경고 이벤트가 없습니다.</div>}
      </div>
      <div className="ar-inspector-actions">
        <button className="ar-primary-action" type="button" onClick={onInvestigate}><ListFilter size={19} aria-hidden="true" /> 조사</button>
        <button className="ar-secondary-action" type="button" disabled={acknowledged} onClick={onAcknowledge}><UserPlus size={19} aria-hidden="true" /> {acknowledged ? '확인됨' : '확인/할당'}</button>
      </div>
    </aside>
  );
}

function Metric({ label, value, suffix = '%' }: { label: string; value?: number | null; suffix?: string }) {
  const number = finiteNumber(value);
  return (
    <div className="ar-metric"><span>{label}</span><strong>{number == null ? '-' : `${number.toFixed(1)}${suffix}`}</strong><i aria-hidden="true"><b style={{ width: `${Math.min(Math.max(number ?? 0, 0), 100)}%` }} /></i></div>
  );
}

function StateValue({ label, value, danger = false }: { label: string; value: number; danger?: boolean }) {
  return <div className={`ar-state-value ${danger ? 'danger' : ''}`}><span>{label}</span><strong>{value}</strong></div>;
}

function EventPanel({ events, onOpenAll }: { events: AgentLogEvent[]; onOpenAll: () => void }) {
  const visible = events.filter(isImportantEvent).sort(sortEventsNewestFirst).slice(0, 6);
  return (
    <section className="ar-panel ar-event-panel" aria-labelledby="ar-events-title">
      <div className="ar-panel-heading"><h3 id="ar-events-title">이벤트 스트림</h3><span>최신순</span></div>
      <div className="ar-event-list">
        {visible.length ? visible.map((event, index) => (
          <div className="ar-event-row" key={`${event.observedAt ?? event.eventTime}-${event.assetUid}-${index}`}>
            <time>{formatTime(event.observedAt ?? event.eventTime)}</time><SeverityIcon severity={eventSeverity(event)} size={16} /><strong>{event.assetUid ?? '-'}</strong><span>{event.eventType ?? event.message ?? 'event'}</span>
          </div>
        )) : <div className="ar-inline-empty">최근 경고 이벤트가 없습니다.</div>}
      </div>
      <button className="ar-all-events" type="button" onClick={onOpenAll}>모든 이벤트 보기 <ChevronDown size={13} aria-hidden="true" /></button>
    </section>
  );
}

function CoveragePanel({ coverage, total }: { coverage: ReturnType<typeof buildCoverage>; total: number }) {
  return (
    <section className="ar-panel ar-coverage-panel" aria-labelledby="ar-coverage-title">
      <div className="ar-panel-heading"><h3 id="ar-coverage-title">수집 커버리지 <small>(현재 자산 {total})</small></h3></div>
      <div className="ar-coverage-grid">
        {coverage.map((source) => (
          <div className={`ar-coverage-source ${source.count > 0 ? 'healthy' : ''}`} key={source.label}>
            {source.count > 0 ? <CheckCircle2 size={30} aria-hidden="true" /> : <XCircle size={30} aria-hidden="true" />}
            <div><strong>{source.label}</strong><span>{source.count > 0 ? '수집 중' : '미수집'}</span></div>
            <b>{source.count} / {total}</b>
          </div>
        ))}
      </div>
      <footer className="ar-coverage-footer">수집 상태는 30초 간격으로 갱신됩니다.</footer>
    </section>
  );
}

function SeverityIcon({ severity, size = 22 }: { severity: Severity; size?: number }) {
  return severity === 'CRITICAL'
    ? <CircleAlert size={size} fill="currentColor" strokeWidth={1.8} aria-hidden="true" />
    : <ShieldAlert size={size} fill="currentColor" strokeWidth={1.8} aria-hidden="true" />;
}

function MiniSparkline({ values, color = '#47d8d2', max }: { values: number[]; color?: string; max?: number }) {
  const data = normalizeSparkline(values, max).map((value, index) => ({ index, value }));
  return (
    <LineChart className="ar-sparkline" width={52} height={18} data={data} margin={{ top: 2, right: 1, bottom: 2, left: 1 }} aria-hidden="true">
      <Line type="monotone" dataKey="value" stroke={color} strokeWidth={1.3} dot={false} isAnimationActive={false} />
    </LineChart>
  );
}

function buildFleetResourceSummary(assets: AssetMetricSummary[]) {
  const cpuValues = assets.map((asset) => finiteNumber(asset.metrics.cpuUsagePct)).filter((value): value is number => value != null);
  const memoryValues = assets.map((asset) => finiteNumber(asset.metrics.memoryUsagePct)).filter((value): value is number => value != null);
  const diskAssets = assets.filter((asset) => asset.sources.diskIo === true);
  const networkAssets = assets.filter((asset) => asset.sources.traffic === true);
  return {
    total: assets.length,
    reporting: assets.filter((asset) => asset.sources.observed === true).length,
    stale: assets.filter((asset) => asset.sources.observed === true && asset.stale).length,
    attention: assets.filter((asset) => asset.stale || asset.health === 'critical' || asset.health === 'warning' || asset.health === 'unknown').length,
    cpuReporting: cpuValues.length,
    memoryReporting: memoryValues.length,
    diskReporting: diskAssets.length,
    networkReporting: networkAssets.length,
    avgCpuUsagePct: average(cpuValues),
    avgMemoryUsagePct: average(memoryValues),
    diskReadBps: sumPresentMetrics(diskAssets, (asset) => asset.metrics.diskReadBps),
    diskWriteBps: sumPresentMetrics(diskAssets, (asset) => asset.metrics.diskWriteBps),
    diskReadIops: sumPresentMetrics(diskAssets, (asset) => asset.metrics.diskReadIops),
    diskWriteIops: sumPresentMetrics(diskAssets, (asset) => asset.metrics.diskWriteIops),
    networkInBps: sumPresentMetrics(networkAssets, (asset) => asset.metrics.networkInBps),
    networkOutBps: sumPresentMetrics(networkAssets, (asset) => asset.metrics.networkOutBps)
  };
}

function compareResourceRows(left: PriorityRow, right: PriorityRow, sort: ResourceSort) {
  if (sort.key === 'name') {
    const comparison = left.asset.name.localeCompare(right.asset.name, 'ko', { numeric: true, sensitivity: 'base' });
    return (sort.direction === 'asc' ? comparison : -comparison) || left.asset.assetUid.localeCompare(right.asset.assetUid);
  }
  const leftValue = resourceSortValue(left.asset, sort.key);
  const rightValue = resourceSortValue(right.asset, sort.key);
  const comparison = compareOptionalNumbers(leftValue, rightValue, sort.direction);
  return comparison || left.asset.name.localeCompare(right.asset.name, 'ko', { numeric: true, sensitivity: 'base' });
}

function resourceSortValue(asset: AssetMetricSummary, key: ResourceSortKey) {
  switch (key) {
    case 'health':
      return asset.health === 'critical' ? 5 : asset.health === 'warning' ? 4 : asset.sources.observed === true && asset.stale ? 3 : asset.health === 'unknown' ? 2 : 0;
    case 'cpu':
      return finiteNumber(asset.metrics.cpuUsagePct);
    case 'memory':
      return finiteNumber(asset.metrics.memoryUsagePct);
    case 'diskIo':
      return asset.sources.diskIo === true
        ? safeNumber(asset.metrics.diskReadBps) + safeNumber(asset.metrics.diskWriteBps)
        : null;
    case 'network':
      return asset.sources.traffic === true
        ? safeNumber(asset.metrics.networkInBps) + safeNumber(asset.metrics.networkOutBps)
        : null;
    case 'freshness': {
      const timestamp = Date.parse(asset.lastSeenAt ?? '');
      return Number.isFinite(timestamp) ? timestamp : null;
    }
    default:
      return null;
  }
}

function compareOptionalNumbers(left: number | null, right: number | null, direction: SortDirection) {
  if (left == null && right == null) {
    return 0;
  }
  if (left == null) {
    return 1;
  }
  if (right == null) {
    return -1;
  }
  return direction === 'asc' ? left - right : right - left;
}

function sumPresentMetrics(assets: AssetMetricSummary[], select: (asset: AssetMetricSummary) => number | null | undefined) {
  const values = assets.map((asset) => finiteNumber(select(asset))).filter((value): value is number => value != null);
  return values.length ? values.reduce((total, value) => total + value, 0) : null;
}

function average(values: number[]) {
  return values.length ? values.reduce((total, value) => total + value, 0) / values.length : null;
}

function resourceHealthTone(asset: AssetMetricSummary) {
  if (asset.sources.observed !== true) {
    return 'unknown';
  }
  if (asset.health === 'critical' || asset.health === 'warning') {
    return asset.health;
  }
  return asset.stale ? 'stale' : asset.health;
}

function metricTone(value?: number | null) {
  const number = finiteNumber(value);
  if (number == null) {
    return 'missing';
  }
  if (number >= 90) {
    return 'critical';
  }
  if (number >= 80) {
    return 'warning';
  }
  return 'healthy';
}

function rangeLabel(range: RangeValue) {
  return range === '15m' ? '최근 15분' : range === '1h' ? '최근 1시간' : '최근 24시간';
}

function formatPercent(value?: number | null) {
  const number = finiteNumber(value);
  return number == null ? '—' : `${number.toFixed(1)}%`;
}

function formatBytesPerSecond(value?: number | null) {
  const number = finiteNumber(value);
  if (number == null) {
    return '—';
  }
  const absolute = Math.abs(number);
  const units = ['B/s', 'KB/s', 'MB/s', 'GB/s', 'TB/s'];
  let unitIndex = 0;
  let scaled = absolute;
  while (scaled >= 1024 && unitIndex < units.length - 1) {
    scaled /= 1024;
    unitIndex += 1;
  }
  const signed = number < 0 ? -scaled : scaled;
  const digits = unitIndex === 0 || scaled >= 100 ? 0 : scaled >= 10 ? 1 : 2;
  return `${signed.toFixed(digits)} ${units[unitIndex]}`;
}

function formatIops(value?: number | null) {
  const number = finiteNumber(value);
  if (number == null) {
    return '—';
  }
  if (Math.abs(number) >= 1000) {
    return `${(number / 1000).toFixed(Math.abs(number) >= 10000 ? 0 : 1)}K IOPS`;
  }
  return `${number.toFixed(number >= 100 ? 0 : 1)} IOPS`;
}

function formatOptionalBps(value?: number | null) {
  const number = finiteNumber(value);
  return number == null ? '—' : formatBps(number);
}

function formatCount(value?: number | null) {
  const number = finiteNumber(value);
  return number == null ? '—' : Math.round(number).toLocaleString('ko-KR');
}

function buildManagedAssets(metricAssets: AssetMetricSummary[], registeredAssets: Asset[]) {
  const rows = new Map(metricAssets.map((asset) => [asset.assetUid, asset]));
  for (const asset of registeredAssets) {
    if (!rows.has(asset.assetUid)) {
      rows.set(asset.assetUid, {
        id: asset.id,
        assetUid: asset.assetUid,
        name: asset.name,
        assetType: asset.assetType,
        managementIp: asset.managementIp,
        location: asset.location,
        description: asset.description,
        status: asset.status,
        lastSeenAt: asset.lastSeenAt,
        stale: true,
        health: 'unknown',
        sources: { registered: true, observed: false },
        metrics: {},
        signals: { reasons: [{ code: 'not_observed', label: '수집 데이터 없음', severity: 'warning' }] }
      });
    }
  }
  return [...rows.values()];
}

function buildPriorityRow(asset: AssetMetricSummary): PriorityRow {
  const reasons = asset.signals?.reasons ?? [];
  const critical = asset.health === 'critical' || reasons.some((reason) => reason.severity === 'critical');
  const high = Boolean(asset.stale)
    || safeNumber(asset.security?.failedServices) > 0
    || safeNumber(asset.security?.interfacesDown ?? asset.signals?.interfacesDown) > 0
    || reasons.some((reason) => /stale|sudo|service|interface_down|event_error/i.test(reason.code ?? ''));
  const securityCount = safeNumber(asset.security?.failedServices)
    + safeNumber(asset.security?.firewallDisabled)
    + safeNumber(asset.security?.interfacesDown)
    + safeNumber(asset.security?.securityEvents);
  const severity: Severity = critical ? 'CRITICAL' : high ? 'HIGH' : 'MEDIUM';
  const signals = reasons.map((reason) => [reason.label, reason.detail].filter(Boolean).join(' · '));
  if (signals.length === 0 && securityCount > 0) {
    signals.push(`보안·운영 신호 ${securityCount}건`);
  }
  if (signals.length === 0 && asset.stale) {
    signals.push('수집 지연');
  }
  if (signals.length === 0) {
    signals.push('활성 위험 신호 없음');
  }
  return {
    asset,
    severity,
    signals,
    actionable: critical || high || asset.health === 'warning' || reasons.length > 0 || securityCount > 0,
    ageSeconds: ageSeconds(asset.lastSeenAt)
  };
}

function buildCoverage(assets: AssetMetricSummary[], events: AgentLogEvent[]) {
  const eventAssets = (pattern: RegExp) => new Set(
    events.filter((event) => pattern.test(`${event.sourceName ?? ''} ${event.program ?? ''} ${event.eventType ?? ''}`))
      .map((event) => event.assetUid)
      .filter((value): value is string => Boolean(value))
  ).size;
  return [
    { label: 'AGENT', count: assets.filter((asset) => asset.sources.agent).length },
    { label: 'SNMP', count: assets.filter((asset) => asset.sources.snmp).length },
    { label: 'Suricata', count: eventAssets(/suricata/i) },
    { label: 'Zeek', count: eventAssets(/zeek/i) }
  ];
}

function mergeTrafficRows(groups: InterfaceTraffic[][]) {
  const rows = new Map<string, InterfaceTraffic>();
  for (const group of groups) {
    for (const row of group) {
      const key = `${row.assetUid}-${row.interfaceName}`;
      const previous = rows.get(key);
      rows.set(key, previous ? {
        ...previous,
        inBps: Math.max(previous.inBps, row.inBps),
        outBps: Math.max(previous.outBps, row.outBps),
        utilizationPct: Math.max(previous.utilizationPct, row.utilizationPct),
        errors: Math.max(previous.errors, row.errors),
        discards: Math.max(previous.discards, row.discards),
        status: row.status || previous.status
      } : row);
    }
  }
  return [...rows.values()];
}

function normalizeSparkline(values: number[], explicitMax?: number) {
  const finite = values.filter(Number.isFinite);
  const source = finite.length > 1 ? finite : [finite[0] ?? 0, finite[0] ?? 0];
  const max = explicitMax ?? Math.max(...source, 1);
  return source.map((value) => Math.max(0, Math.min(100, (value / max) * 100)));
}

function eventSeverity(event: AgentLogEvent): Severity {
  const severity = (event.severity ?? '').toUpperCase();
  if (severity === 'CRITICAL' || severity === 'ERROR') {
    return 'CRITICAL';
  }
  return severity === 'WARNING' || severity === 'WARN' ? 'HIGH' : 'MEDIUM';
}

function isImportantEvent(event: AgentLogEvent) {
  return ['CRITICAL', 'ERROR', 'WARNING', 'WARN'].includes((event.severity ?? '').toUpperCase());
}

function sortEventsNewestFirst(left: AgentLogEvent, right: AgentLogEvent) {
  return Date.parse(right.observedAt ?? right.eventTime ?? '') - Date.parse(left.observedAt ?? left.eventTime ?? '');
}

function isHealthyService(status?: string) {
  return ['active', 'running', 'started', 'up', 'ok'].includes((status ?? '').toLowerCase());
}

function isHealthyInterface(status?: string) {
  return ['up', 'active', 'running', 'unknown'].includes((status ?? '').toLowerCase());
}

function totalTraffic(row: InterfaceTraffic) {
  return safeNumber(row.inBps) + safeNumber(row.outBps);
}

function finiteNumber(value?: number | null) {
  return value == null || !Number.isFinite(value) ? null : Number(value);
}

function safeNumber(value?: number | null) {
  return finiteNumber(value) ?? 0;
}

function ageSeconds(value?: string | null) {
  if (!value) {
    return Number.MAX_SAFE_INTEGER;
  }
  const age = Date.now() - Date.parse(value);
  return Number.isFinite(age) ? Math.max(0, Math.round(age / 1000)) : Number.MAX_SAFE_INTEGER;
}

function formatAge(value?: string | null) {
  const seconds = ageSeconds(value);
  if (!Number.isFinite(seconds) || seconds === Number.MAX_SAFE_INTEGER) {
    return '-';
  }
  if (seconds < 60) {
    return `${seconds}초 전`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}분 전`;
  }
  return `${Math.floor(seconds / 3600)}시간 전`;
}

function formatTime(value?: string | null) {
  if (!value) {
    return '--:--:-- KST';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '--:--:-- KST';
  }
  return `${date.toLocaleTimeString('en-GB', { hour12: false, timeZone: 'Asia/Seoul' })} KST`;
}

function loadWithTimeout<T>(request: Promise<T>, fallback: T): Promise<LoadResult<T>> {
  return Promise.race([
    request.then((value) => ({ ok: true, value })).catch(() => ({ ok: false, value: fallback })),
    new Promise<LoadResult<T>>((resolve) => {
      window.setTimeout(() => resolve({ ok: false, value: fallback }), REQUEST_TIMEOUT_MS);
    })
  ]);
}
