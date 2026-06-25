import {
  ArrowLeft,
  Cpu,
  Database,
  Edit3,
  HardDrive,
  Network,
  Plus,
  RefreshCw,
  Save,
  Search,
  Server,
  ShieldAlert,
  Trash2,
  X
} from 'lucide-react';
import { type FormEvent, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  XAxis,
  YAxis
} from 'recharts';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { ChartContainer, ChartLegend, ChartLegendContent, ChartTooltip, ChartTooltipContent, type ChartConfig } from '@/components/ui/chart';
import { Input } from '@/components/ui/input';
import { NativeSelect } from '@/components/ui/native-select';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { AgentProcessState, AgentSocketState, Asset, AssetMetricDetail, AssetMetricSummary, AssetMetricsOverview, MetricPoint, Role } from '../lib/types';
import { canMutate, formatBps } from '../lib/uiModel';
import { cn } from '../lib/utils';

type AssetCreatePayload = {
  name: string;
  assetType: string;
  managementIp?: string;
  location?: string;
  description?: string;
};

type AssetUpdatePayload = {
  name: string;
  location?: string;
  description?: string;
};

type AssetsViewProps = {
  role: Role;
  assets: Asset[];
  onCreate: (payload: AssetCreatePayload) => Promise<Asset | void>;
  onUpdate: (id: number, payload: AssetUpdatePayload) => Promise<Asset | void>;
  onDelete: (id: number) => Promise<void>;
};

type HealthFilter = 'all' | AssetMetricSummary['health'];
type RangeOption = '15m' | '30m' | '1h' | '6h' | '24h';

type AssetDiskRow = NonNullable<AssetMetricDetail['disks']>[number];
type ProcessSocketGroup = {
  key: string;
  process?: AgentProcessState;
  processName: string;
  pid?: number;
  user?: string;
  executablePath?: string;
  sockets: AgentSocketState[];
};

const ASSET_METRICS_REFRESH_MS = 30_000;

const rangeOptions: { value: RangeOption; label: string }[] = [
  { value: '15m', label: '15분' },
  { value: '30m', label: '30분' },
  { value: '1h', label: '1시간' },
  { value: '6h', label: '6시간' },
  { value: '24h', label: '24시간' }
];

const healthFilters: { value: HealthFilter; label: string }[] = [
  { value: 'all', label: '전체 상태' },
  { value: 'critical', label: 'Critical' },
  { value: 'warning', label: 'Warning' },
  { value: 'healthy', label: 'Healthy' },
  { value: 'unknown', label: '미수집' }
];

const emptyOverview: AssetMetricsOverview = {
  range: '1h',
  summary: {
    totalAssets: 0,
    observedAssets: 0,
    staleAssets: 0,
    criticalAssets: 0,
    warningAssets: 0,
    totalNetworkInBps: 0,
    totalNetworkOutBps: 0
  },
  assets: []
};

export function AssetsView({ role, assets, onCreate, onUpdate, onDelete }: AssetsViewProps) {
  const [creating, setCreating] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createAssetType, setCreateAssetType] = useState('LINUX_SERVER');
  const [createManagementIp, setCreateManagementIp] = useState('');
  const [createLocation, setCreateLocation] = useState('');
  const [createDescription, setCreateDescription] = useState('');
  const [editing, setEditing] = useState(false);
  const [editName, setEditName] = useState('');
  const [editLocation, setEditLocation] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [range, setRange] = useState<RangeOption>('1h');
  const [query, setQuery] = useState('');
  const [healthFilter, setHealthFilter] = useState<HealthFilter>('all');
  const [overview, setOverview] = useState<AssetMetricsOverview>(emptyOverview);
  const [detail, setDetail] = useState<AssetMetricDetail | null>(null);
  const [selectedAssetUid, setSelectedAssetUid] = useState('');
  const [selectedAssetSnapshot, setSelectedAssetSnapshot] = useState<AssetMetricSummary | null>(null);
  const [detailRefreshToken, setDetailRefreshToken] = useState(0);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [mutationError, setMutationError] = useState('');
  const overviewRef = useRef(overview);
  const selectedAssetUidRef = useRef(selectedAssetUid);

  useEffect(() => {
    overviewRef.current = overview;
  }, [overview]);

  useEffect(() => {
    selectedAssetUidRef.current = selectedAssetUid;
  }, [selectedAssetUid]);

  async function loadMetrics(nextRange = range) {
    const hasStableOverview = overviewRef.current.assets.length > 0;
    if (hasStableOverview) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError('');
    try {
      const response = await api.assetMetrics(nextRange);
      setOverview(response);
      const activeAssetUid = selectedAssetUidRef.current;
      if (activeAssetUid) {
        const refreshedAsset = response.assets.find((asset) => asset.assetUid === activeAssetUid);
        if (refreshedAsset) {
          setSelectedAssetSnapshot(refreshedAsset);
        }
        setDetailRefreshToken((value) => value + 1);
      }
    } catch {
      const fallback = fallbackOverview(assets, nextRange);
      setOverview((current) => (current.assets.length > 0 ? current : fallback));
      const activeAssetUid = selectedAssetUidRef.current;
      if (activeAssetUid) {
        const fallbackAsset = fallback.assets.find((asset) => asset.assetUid === activeAssetUid);
        if (fallbackAsset) {
          setSelectedAssetSnapshot(fallbackAsset);
        }
        setDetailRefreshToken((value) => value + 1);
      }
      setError('자산 메트릭 정보를 불러오지 못했습니다. 등록된 자산 기본 정보만 표시합니다.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => {
    void loadMetrics(range);
  }, [range]);

  useEffect(() => {
    const refreshTimer = window.setInterval(() => {
      void loadMetrics(range);
    }, ASSET_METRICS_REFRESH_MS);
    return () => window.clearInterval(refreshTimer);
  }, [range, selectedAssetUid]);

  const filteredAssets = useMemo(() => {
    const text = query.trim().toLowerCase();
    return overview.assets.filter((asset) => {
      if (healthFilter !== 'all' && asset.health !== healthFilter) {
        return false;
      }
      if (!text) {
        return true;
      }
      return [
        asset.name,
        asset.assetUid,
        asset.assetType,
        asset.managementIp,
        asset.location,
        asset.description,
        asset.status
      ]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
        .includes(text);
    });
  }, [healthFilter, overview.assets, query]);

  const selectedAsset = useMemo(
    () => overview.assets.find((asset) => asset.assetUid === selectedAssetUid)
      ?? (selectedAssetSnapshot?.assetUid === selectedAssetUid ? selectedAssetSnapshot : undefined),
    [overview.assets, selectedAssetSnapshot, selectedAssetUid]
  );

  const scanAssets = useMemo(() => sortAssetsForScan(filteredAssets), [filteredAssets]);
  const summary = overview.summary;
  const canManageAssets = canMutate(role, 'asset:update');

  useEffect(() => {
    if (!selectedAssetUid) {
      setDetail(null);
      setDetailLoading(false);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    api.assetMetricDetail(selectedAssetUid, range)
      .then((response) => {
        if (!cancelled) {
          setDetail(response);
          setSelectedAssetSnapshot(response.asset);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDetail((current) => (current?.asset.assetUid === selectedAssetUid ? current : null));
        }
      })
      .finally(() => {
        if (!cancelled) {
          setDetailLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [detailRefreshToken, range, selectedAssetUid]);

  useEffect(() => {
    setEditing(false);
    setMutationError('');
    setEditName(selectedAsset?.name ?? '');
    setEditLocation(selectedAsset?.location ?? '');
    setEditDescription(selectedAsset?.description ?? '');
  }, [selectedAsset?.assetUid]);

  function openAssetDetail(assetUid: string) {
    if (assetUid === selectedAssetUid) {
      setDetailRefreshToken((value) => value + 1);
      return;
    }
    selectedAssetUidRef.current = assetUid;
    setSelectedAssetUid(assetUid);
    setSelectedAssetSnapshot(overviewRef.current.assets.find((asset) => asset.assetUid === assetUid) ?? null);
    setDetail(null);
    setDetailRefreshToken((value) => value + 1);
  }

  function returnToList() {
    selectedAssetUidRef.current = '';
    setSelectedAssetUid('');
    setSelectedAssetSnapshot(null);
    setDetail(null);
    setDetailLoading(false);
    setEditing(false);
    setMutationError('');
  }

  async function submitCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMutationError('');
    await onCreate({
      name: createName.trim(),
      assetType: createAssetType,
      managementIp: optionalText(createManagementIp),
      location: optionalText(createLocation),
      description: optionalText(createDescription)
    });
    setCreateName('');
    setCreateManagementIp('');
    setCreateLocation('');
    setCreateDescription('');
    setCreateAssetType('LINUX_SERVER');
    setCreating(false);
    await loadMetrics(range);
  }

  async function submitEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selectedAsset?.id) {
      setMutationError('등록되지 않은 관측 자산은 직접 수정할 수 없습니다.');
      return;
    }
    setMutationError('');
    await onUpdate(selectedAsset.id, {
      name: editName.trim(),
      location: optionalText(editLocation),
      description: optionalText(editDescription)
    });
    setEditing(false);
    await loadMetrics(range);
  }

  async function deleteSelectedAsset() {
    if (!selectedAsset?.id) {
      setMutationError('등록되지 않은 관측 자산은 직접 삭제할 수 없습니다.');
      return;
    }
    if (!window.confirm(`${selectedAsset.name} 자산을 완전히 삭제할까요? 계속 수집되는 자산은 동기화 후 다시 나타날 수 있습니다.`)) {
      return;
    }
    setMutationError('');
    await onDelete(selectedAsset.id);
    returnToList();
    await loadMetrics(range);
  }

  return (
    <TooltipProvider>
      <ViewFrame
        title="자산 관리"
        actions={(
          <>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button variant="outline" size="icon" aria-label="자산 새로고침" onClick={() => void loadMetrics(range)} disabled={loading || refreshing} type="button">
                  <RefreshCw className={cn(refreshing && 'asset-refresh-spin')} size={18} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>자산 새로고침</TooltipContent>
            </Tooltip>
            {canMutate(role, 'asset:create') && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="outline"
                    size="icon"
                    aria-label="자산 추가"
                    onClick={() => {
                      returnToList();
                      setCreating((value) => !value);
                    }}
                    type="button"
                  >
                    <Plus size={18} />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>자산 추가</TooltipContent>
              </Tooltip>
            )}
          </>
        )}
      >
        {error && <div className="notice asset-notice">{error}</div>}
        {loading && overview.assets.length === 0 && <div className="notice">자산 정보를 갱신하는 중입니다.</div>}
        {mutationError && <div className="notice asset-notice">{mutationError}</div>}

        {creating && (
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-base">자산 추가</CardTitle>
              <CardDescription>수동 관리할 자산 기본 정보를 등록합니다.</CardDescription>
            </CardHeader>
            <CardContent>
              <form className="asset-edit-form" onSubmit={submitCreate}>
                <label>
                  <span>자산명</span>
                  <Input aria-label="자산명" value={createName} onChange={(event) => setCreateName(event.target.value)} required />
                </label>
                <label>
                  <span>자산 유형</span>
                  <NativeSelect aria-label="자산 유형" value={createAssetType} onChange={(event) => setCreateAssetType(event.target.value)}>
                    <option value="LINUX_SERVER">Linux server</option>
                    <option value="WINDOWS_SERVER">Windows server</option>
                    <option value="ROUTER">Router</option>
                    <option value="FIREWALL">Firewall</option>
                    <option value="NETWORK_DEVICE">Network device</option>
                    <option value="UNKNOWN">Unknown</option>
                  </NativeSelect>
                </label>
                <label>
                  <span>관리 IP</span>
                  <Input aria-label="관리 IP" value={createManagementIp} onChange={(event) => setCreateManagementIp(event.target.value)} />
                </label>
                <label>
                  <span>위치</span>
                  <Input aria-label="위치" value={createLocation} onChange={(event) => setCreateLocation(event.target.value)} />
                </label>
                <label className="asset-form-wide">
                  <span>설명</span>
                  <textarea aria-label="설명" value={createDescription} onChange={(event) => setCreateDescription(event.target.value)} />
                </label>
                <div className="asset-form-actions">
                  <Button type="button" variant="outline" onClick={() => setCreating(false)}>
                    <X size={16} />취소
                  </Button>
                  <Button type="submit">
                    <Save size={16} />저장
                  </Button>
                </div>
              </form>
            </CardContent>
          </Card>
        )}

        {selectedAsset ? (
          <AssetDetailPanel
            asset={selectedAsset}
            detail={detail?.asset.assetUid === selectedAsset?.assetUid ? detail : null}
            loading={detailLoading}
            canManage={canManageAssets}
            editing={editing}
            editName={editName}
            editLocation={editLocation}
            editDescription={editDescription}
            onEditNameChange={setEditName}
            onEditLocationChange={setEditLocation}
            onEditDescriptionChange={setEditDescription}
            onStartEdit={() => setEditing(true)}
            onCancelEdit={() => {
              setEditing(false);
              setEditName(selectedAsset?.name ?? '');
              setEditLocation(selectedAsset?.location ?? '');
              setEditDescription(selectedAsset?.description ?? '');
            }}
            onSubmitEdit={submitEdit}
            onDelete={() => void deleteSelectedAsset()}
            onBack={returnToList}
          />
        ) : (
          <AssetFleetScan
            assets={scanAssets}
            summary={summary}
            range={range}
            query={query}
            healthFilter={healthFilter}
            onRangeChange={setRange}
            onQueryChange={setQuery}
            onHealthFilterChange={setHealthFilter}
            onOpenAsset={openAssetDetail}
          />
        )}
      </ViewFrame>
    </TooltipProvider>
  );
}

function AssetFleetScan({
  assets,
  summary,
  range,
  query,
  healthFilter,
  onRangeChange,
  onQueryChange,
  onHealthFilterChange,
  onOpenAsset
}: {
  assets: AssetMetricSummary[];
  summary: AssetMetricsOverview['summary'];
  range: RangeOption;
  query: string;
  healthFilter: HealthFilter;
  onRangeChange: (value: RangeOption) => void;
  onQueryChange: (value: string) => void;
  onHealthFilterChange: (value: HealthFilter) => void;
  onOpenAsset: (assetUid: string) => void;
}) {
  return (
    <section className="asset-fleet-scan">
      <div className="asset-fleet-summary-grid">
        <AssetFleetKpi icon={<Server size={17} />} label="전체 자산" value={summary.totalAssets} meta={`${summary.observedAssets} observed`} />
        <AssetFleetKpi icon={<ShieldAlert size={17} />} label="Critical" value={summary.criticalAssets} tone={summary.criticalAssets > 0 ? 'critical' : 'neutral'} meta={`${summary.warningAssets} warning`} />
        <AssetFleetKpi icon={<RefreshCw size={17} />} label="Stale" value={summary.staleAssets} tone={summary.staleAssets > 0 ? 'warning' : 'neutral'} meta="수집 지연" />
        <AssetFleetKpi icon={<Network size={17} />} label="Network" value={formatBps((summary.totalNetworkInBps ?? 0) + (summary.totalNetworkOutBps ?? 0))} meta="RX + TX" />
      </div>

      <Card className="asset-scan-card">
        <CardHeader className="asset-scan-header">
          <div className="asset-scan-heading">
            <div>
              <CardTitle>자산 관제 스캔</CardTitle>
              <CardDescription>상태가 나쁜 자산을 먼저 보고, 필요한 자산만 상세 분석으로 들어갑니다.</CardDescription>
            </div>
            <Badge variant={summary.criticalAssets > 0 ? 'critical' : 'secondary'}>{assets.length} 표시</Badge>
          </div>
          <div className="asset-scan-toolbar">
            <label className="asset-search-field">
              <Search size={16} aria-hidden="true" />
              <input
                aria-label="자산 검색"
                placeholder="이름, UID, IP, 위치 검색"
                value={query}
                onChange={(event) => onQueryChange(event.target.value)}
              />
            </label>
            <NativeSelect aria-label="조회 범위" value={range} onChange={(event) => onRangeChange(event.target.value as RangeOption)}>
              {rangeOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </NativeSelect>
            <NativeSelect aria-label="상태 필터" value={healthFilter} onChange={(event) => onHealthFilterChange(event.target.value as HealthFilter)}>
              {healthFilters.map((filter) => <option key={filter.value} value={filter.value}>{filter.label}</option>)}
            </NativeSelect>
          </div>
        </CardHeader>
        <CardContent className="asset-scan-content">
          {assets.length === 0 ? (
            <div className="asset-empty-state">
              <Server size={28} />
              <strong>조건에 맞는 자산이 없습니다.</strong>
              <span>검색어나 상태 필터를 조정해 보세요.</span>
            </div>
          ) : (
            <div className="asset-scan-table-wrap">
              <Table className="asset-scan-table">
                <TableHeader>
                  <TableRow>
                    <TableHead>상태</TableHead>
                    <TableHead>자산</TableHead>
                    <TableHead>위치 / IP</TableHead>
                    <TableHead>리소스</TableHead>
                    <TableHead>네트워크</TableHead>
                    <TableHead>보안</TableHead>
                    <TableHead>수집</TableHead>
                    <TableHead>상세</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {assets.map((asset) => (
                    <TableRow key={asset.assetUid} className={cn(asset.health === 'critical' && 'asset-row-critical')}>
                      <TableCell><HealthBadge health={asset.health} /></TableCell>
                      <TableCell>
                        <button className="asset-scan-name-button" type="button" onClick={() => onOpenAsset(asset.assetUid)}>
                          <strong>{asset.name}</strong>
                          <span>{asset.assetUid}</span>
                        </button>
                        <div className="asset-scan-meta-row">
                          <Badge variant="secondary">{asset.assetType || 'UNKNOWN'}</Badge>
                          <SourceBadges asset={asset} />
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="asset-scan-stack">
                          <strong>{asset.location || '미지정 위치'}</strong>
                          <span>{asset.managementIp || '-'}</span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="asset-scan-metric-strip">
                          <MetricPill label="CPU" value={formatPercent(asset.metrics.cpuUsagePct)} tone={metricTone(asset.metrics.cpuUsagePct)} />
                          <MetricPill label="MEM" value={formatPercent(asset.metrics.memoryUsagePct)} tone={metricTone(asset.metrics.memoryUsagePct)} />
                          <MetricPill label="DISK" value={formatPercent(asset.metrics.diskUsagePct)} tone={metricTone(asset.metrics.diskUsagePct)} />
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="asset-scan-stack">
                          <strong>{formatBps(asset.metrics.networkInBps ?? 0)} RX</strong>
                          <span>{formatBps(asset.metrics.networkOutBps ?? 0)} TX</span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="asset-scan-stack">
                          <strong>{securitySignalLabel(asset.security)}</strong>
                          <span>{asset.security?.openPorts ?? 0} ports · {asset.security?.failedServices ?? 0} failed</span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <div className="asset-scan-stack">
                          <strong>{asset.stale ? 'Stale' : asset.sources.observed ? 'Observed' : 'Registered'}</strong>
                          <span>{formatDate(asset.lastSeenAt)}</span>
                        </div>
                      </TableCell>
                      <TableCell>
                        <Button type="button" variant="outline" size="sm" onClick={() => onOpenAsset(asset.assetUid)}>
                          상세
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </CardContent>
      </Card>
    </section>
  );
}

function AssetFleetKpi({
  icon,
  label,
  value,
  meta,
  tone = 'neutral'
}: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  meta: string;
  tone?: 'neutral' | 'warning' | 'critical';
}) {
  return (
    <section className={cn('asset-fleet-kpi', tone)}>
      <div aria-hidden="true">{icon}</div>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{meta}</small>
    </section>
  );
}

function MetricPill({ label, value, tone }: { label: string; value: string; tone: 'neutral' | 'warning' | 'critical' }) {
  return (
    <span className={cn('asset-metric-pill', tone)}>
      <em>{label}</em>
      <strong>{value}</strong>
    </span>
  );
}

function AssetDetailSnapshotTile({
  icon,
  label,
  value,
  meta,
  tone = 'neutral'
}: {
  icon: ReactNode;
  label: string;
  value: string;
  meta: string;
  tone?: 'neutral' | 'warning' | 'critical';
}) {
  return (
    <section className={cn('asset-detail-snapshot-tile', tone)}>
      <div aria-hidden="true">{icon}</div>
      <span>{label}</span>
      <strong>{value}</strong>
      <small>{meta}</small>
    </section>
  );
}

function AssetDetailPanel({
  asset,
  detail,
  loading,
  canManage,
  editing,
  editName,
  editLocation,
  editDescription,
  onEditNameChange,
  onEditLocationChange,
  onEditDescriptionChange,
  onStartEdit,
  onCancelEdit,
  onSubmitEdit,
  onDelete,
  onBack
}: {
  asset?: AssetMetricSummary;
  detail: AssetMetricDetail | null;
  loading: boolean;
  canManage: boolean;
  editing: boolean;
  editName: string;
  editLocation: string;
  editDescription: string;
  onEditNameChange: (value: string) => void;
  onEditLocationChange: (value: string) => void;
  onEditDescriptionChange: (value: string) => void;
  onStartEdit: () => void;
  onCancelEdit: () => void;
  onSubmitEdit: (event: FormEvent<HTMLFormElement>) => void;
  onDelete: () => void;
  onBack: () => void;
}) {
  if (!asset) {
    return (
      <Card className="asset-detail-card">
        <CardContent className="grid min-h-72 place-items-center text-center">
          <div className="grid gap-1">
            <strong>자산 없음</strong>
            <span className="text-sm text-muted-foreground">등록되었거나 수집된 자산이 아직 없습니다.</span>
          </div>
        </CardContent>
      </Card>
    );
  }

  const metrics = detail?.asset.metrics ?? asset.metrics;
  const security = detail?.security ?? asset.security;
  const interfaces = detail?.interfaces ?? [];
  const disks = detail?.disks ?? [];
  const diskIoRows = detail?.diskIo ?? disks.filter((disk) => disk.device);
  const processes = detail?.processes ?? [];
  const sockets = detail?.sockets ?? [];
  const primaryMounts = ['/', '/tmp'].map((mountPoint) => ({
    mountPoint,
    disk: findDiskByMount(disks, mountPoint)
  }));
  const canMutateSelected = canManage && asset.id != null;
  const managementIp = asset.managementIp?.trim() || '-';
  const description = asset.description?.trim() || '설명 없음';
  const hasDiskIops = isFiniteNumber(metrics.diskReadIops) || isFiniteNumber(metrics.diskWriteIops);
  const totalDiskIops = hasDiskIops ? finiteNumber(metrics.diskReadIops) + finiteNumber(metrics.diskWriteIops) : null;
  const maxDiskIoUtilization = metrics.diskIoUtilizationPct
    ?? diskIoRows.map((disk) => disk.ioUtilizationPct).filter(isFiniteNumber).sort((left, right) => right - left)[0];
  const processSocketCount = sockets.length > 0 ? sockets.length : processes.reduce(
    (total, process) => total + (process.listeningSocketCount ?? 0) + (process.connectedSocketCount ?? 0),
    0
  );
  const listeningSockets = sockets.filter(isListeningSocketRow);
  const connectedSockets = sockets.filter(isConnectedSocketRow);
  const publicListeningSockets = listeningSockets.filter(isPublicListeningSocket);
  const openPortCount = security?.openPorts ?? listeningSockets.length;
  const signalTone = (security?.firewallDisabled ?? 0) > 0 || (security?.failedServices ?? 0) > 0
    ? 'critical'
    : openPortCount > 0
      ? 'warning'
      : 'neutral';

  return (
    <Card className="asset-detail-card asset-detail-card-elevated">
      <CardHeader className="asset-detail-hero">
        <div className="asset-detail-hero-row">
          <div className={cn('asset-detail-status-icon', asset.health)} aria-hidden="true">
            <Server size={20} />
          </div>
          <div className="asset-detail-title-group">
            <CardDescription>선택 자산</CardDescription>
            <CardTitle>{asset.name}</CardTitle>
            <div className="asset-detail-meta-row">
              <Badge variant="secondary">{asset.assetType || 'UNKNOWN'}</Badge>
              <span>관리 IP {managementIp}</span>
              <span>수집 {formatDate(asset.lastSeenAt)}</span>
              <SourceBadges asset={asset} />
            </div>
          </div>
          <div className="asset-detail-actions">
            <Button type="button" variant="outline" size="sm" onClick={onBack}>
              <ArrowLeft size={16} />목록
            </Button>
            <HealthBadge health={asset.health} />
            {canManage && (
              <>
                <Button size="icon" variant="outline" aria-label="자산 정보 수정" disabled={!canMutateSelected || editing} onClick={onStartEdit} type="button">
                  <Edit3 size={16} />
                </Button>
                <Button size="icon" variant="destructive" aria-label="자산 삭제" disabled={!canMutateSelected} onClick={onDelete} type="button">
                  <Trash2 size={16} />
                </Button>
              </>
            )}
          </div>
        </div>
      </CardHeader>
      <CardContent className="asset-detail-content">
        {loading && (
          <div className="grid gap-2">
            <Skeleton className="h-4 w-44" />
            <Skeleton className="h-24 w-full" />
          </div>
        )}

        {editing ? (
          <form className="asset-edit-form asset-edit-panel" onSubmit={onSubmitEdit}>
            <label>
              <span>자산명</span>
              <Input aria-label="수정 자산명" value={editName} onChange={(event) => onEditNameChange(event.target.value)} required />
            </label>
            <label>
              <span>위치</span>
              <Input aria-label="수정 위치" value={editLocation} onChange={(event) => onEditLocationChange(event.target.value)} />
            </label>
            <label className="asset-form-wide">
              <span>설명</span>
              <textarea aria-label="수정 설명" value={editDescription} onChange={(event) => onEditDescriptionChange(event.target.value)} />
            </label>
            <div className="asset-form-actions">
              <Button type="button" variant="outline" onClick={onCancelEdit}>
                <X size={16} />취소
              </Button>
              <Button type="submit">
                <Save size={16} />저장
              </Button>
            </div>
          </form>
        ) : (
          <section className="asset-detail-info-panel">
            <div className="panel-heading">
              <h4>기본 정보</h4>
              <span>{canMutateSelected ? '수정 가능' : '읽기 전용'}</span>
            </div>
            <dl className="asset-detail-facts">
              <div>
                <dt>UID</dt>
                <dd>{asset.assetUid}</dd>
              </div>
              <div>
                <dt>위치</dt>
                <dd>{asset.location || '미지정 위치'}</dd>
              </div>
              <div>
                <dt>설명</dt>
                <dd>{description}</dd>
              </div>
              <div>
                <dt>상태</dt>
                <dd>{asset.status || 'observed'} · {healthLabel(asset.health)}</dd>
              </div>
            </dl>
          </section>
        )}

        <div className="asset-detail-snapshot-grid">
          <AssetDetailSnapshotTile icon={<Cpu size={17} />} label="CPU" value={formatPercent(metrics.cpuUsagePct)} meta={`load ${formatPercent(metrics.normalizedLoadPct)}`} tone={metricTone(metrics.cpuUsagePct)} />
          <AssetDetailSnapshotTile icon={<Database size={17} />} label="Memory" value={formatPercent(metrics.memoryUsagePct)} meta={formatBytes(metrics.memoryAvailableBytes)} tone={metricTone(metrics.memoryUsagePct)} />
          <AssetDetailSnapshotTile icon={<HardDrive size={17} />} label="Disk" value={formatPercent(metrics.diskUsagePct)} meta={`${disks.length} mounts`} tone={metricTone(metrics.diskUsagePct)} />
          <AssetDetailSnapshotTile icon={<Network size={17} />} label="Network" value={formatBps((metrics.networkInBps ?? 0) + (metrics.networkOutBps ?? 0))} meta="RX + TX" />
          <AssetDetailSnapshotTile icon={<ShieldAlert size={17} />} label="Signals" value={securitySignalLabel(security)} meta={`${openPortCount} open · ${processes.length} proc`} tone={signalTone} />
        </div>

        <Tabs defaultValue="performance" className="asset-detail-tabs">
          <TabsList className="asset-detail-tab-list">
            <TabsTrigger value="performance">성능</TabsTrigger>
            <TabsTrigger value="storage">스토리지</TabsTrigger>
            <TabsTrigger value="network">네트워크</TabsTrigger>
            <TabsTrigger value="signals">신호</TabsTrigger>
            <TabsTrigger value="processes">프로세스</TabsTrigger>
          </TabsList>

          <TabsContent value="performance">
            <div className="asset-chart-grid">
              <ChartBlock title="CPU Usage" points={detail?.series.cpu?.length ?? 0} meta={formatPercent(metrics.cpuUsagePct)} empty="CPU 사용률 시계열이 없습니다.">
                <PercentAreaChart data={detail?.series.cpu ?? []} color="var(--chart-1)" name="CPU" />
              </ChartBlock>
              <ChartBlock title="Memory Usage" points={detail?.series.memory?.length ?? 0} meta={formatPercent(metrics.memoryUsagePct)} empty="메모리 사용률 시계열이 없습니다.">
                <PercentAreaChart data={detail?.series.memory ?? []} color="var(--chart-2)" name="Memory" />
              </ChartBlock>
              <ChartBlock title="Network RX/TX" points={detail?.series.network?.length ?? 0} meta={`${formatBps(metrics.networkInBps ?? 0)} RX / ${formatBps(metrics.networkOutBps ?? 0)} TX`} empty="인터페이스 RX/TX 시계열이 없습니다.">
                <NetworkLineChart data={detail?.series.network ?? []} />
              </ChartBlock>
            </div>
          </TabsContent>

          <TabsContent value="storage">
            <div className="asset-tab-grid">
              <DiskCapacityPanel mounts={primaryMounts} totalMounts={disks.length} diskIoDeviceCount={diskIoRows.length} />
              <ChartBlock title="Disk Usage" points={detail?.series.disk?.length ?? 0} meta={formatPercent(metrics.diskUsagePct)} empty="디스크 사용률 시계열이 없습니다.">
                <PercentAreaChart data={detail?.series.disk ?? []} color="var(--chart-3)" name="Disk" />
              </ChartBlock>
              <ChartBlock
                title="Disk I/O"
                points={detail?.series.diskIo?.length ?? 0}
                meta={`${formatBytesPerSecond(metrics.diskReadBps)} read / ${formatBytesPerSecond(metrics.diskWriteBps)} write · ${formatIops(totalDiskIops)} · ${formatPercent(maxDiskIoUtilization)}`}
                empty="Disk I/O 시계열이 없습니다."
              >
                <DiskIoLineChart data={detail?.series.diskIo ?? []} />
              </ChartBlock>
              <DetailList title="Disk I/O devices" meta={`${diskIoRows.length} devices`} empty="수집된 Disk I/O 장치 정보가 없습니다.">
                {diskIoRows.map((disk) => (
                  <li key={disk.device ?? 'unknown'}>
                    <div>
                      <strong>{disk.device ?? 'unknown'}</strong>
                      <span>{formatBytesPerSecond(disk.readBps)} read · {formatBytesPerSecond(disk.writeBps)} write</span>
                    </div>
                    <em>{formatIops((disk.readIops ?? 0) + (disk.writeIops ?? 0))}</em>
                  </li>
                ))}
              </DetailList>
              <DetailList title="Disk by mount" meta={`${disks.length} rows`} empty="수집된 디스크 mount/device 정보가 없습니다.">
                {disks.map((disk) => (
                  <li key={`${disk.mountPoint ?? disk.device ?? 'unknown'}-${disk.filesystem ?? ''}`}>
                    <div>
                      <strong>{disk.mountPoint ?? disk.device ?? 'unknown'}</strong>
                      <span>{[disk.filesystem, disk.device].filter(Boolean).join(' · ') || 'filesystem unknown'}</span>
                    </div>
                    <em>{disk.usedPct == null ? formatPercent(disk.ioUtilizationPct) : formatPercent(disk.usedPct)}</em>
                  </li>
                ))}
              </DetailList>
            </div>
          </TabsContent>

          <TabsContent value="network">
            <div className="asset-tab-grid">
              <ChartBlock title="Network RX/TX" points={detail?.series.network?.length ?? 0} meta={`${formatBps(metrics.networkInBps ?? 0)} RX / ${formatBps(metrics.networkOutBps ?? 0)} TX`} empty="인터페이스 RX/TX 시계열이 없습니다.">
                <NetworkLineChart data={detail?.series.network ?? []} />
              </ChartBlock>
              <DetailList title="Interfaces" meta={`${interfaces.length} interfaces`} empty="수집된 인터페이스 정보가 없습니다.">
                {interfaces.map((row) => (
                  <li key={`${row.assetUid}-${row.interfaceName}`}>
                    <div>
                      <strong>{row.interfaceName}</strong>
                      <span>errors/drops {row.errors + row.discards} · status {row.status}</span>
                    </div>
                    <em>{formatBps(row.inBps)} / {formatBps(row.outBps)}</em>
                  </li>
                ))}
              </DetailList>
            </div>
          </TabsContent>

          <TabsContent value="signals">
            <div className="asset-signals-layout">
              <SignalPosturePanel
                security={security}
                interfacesCount={interfaces.length}
                processesCount={processes.length}
                processSocketCount={processSocketCount}
                listeningCount={listeningSockets.length}
                connectedCount={connectedSockets.length}
                publicListeningCount={publicListeningSockets.length}
              />
              <OpenPortsPanel sockets={listeningSockets} declaredOpenPorts={openPortCount} />
              <ProcessNetstatPanel processes={processes} sockets={sockets} />
            </div>
          </TabsContent>

          <TabsContent value="processes">
            <div className="asset-tab-grid">
              <DetailList title="Processes" meta={`${processes.length} rows`} empty="수집된 process 정보가 없습니다.">
                {processes.map((process) => (
                  <li key={`${process.pid}-${process.name}`}>
                    <div>
                      <strong>{process.name ?? `pid ${process.pid}`}</strong>
                      <span>{process.user ?? 'user unknown'} · sockets {(process.listeningSocketCount ?? 0) + (process.connectedSocketCount ?? 0)}</span>
                    </div>
                    <em>{formatBytes(process.memoryBytes)}</em>
                  </li>
                ))}
              </DetailList>
              <ProcessNetstatPanel processes={processes} sockets={sockets} />
            </div>
          </TabsContent>
        </Tabs>
      </CardContent>
    </Card>
  );
}

function DiskCapacityPanel({
  mounts,
  totalMounts,
  diskIoDeviceCount
}: {
  mounts: Array<{ mountPoint: string; disk?: AssetDiskRow }>;
  totalMounts: number;
  diskIoDeviceCount: number;
}) {
  return (
    <section className="asset-disk-capacity-panel">
      <div className="panel-heading">
        <h4>Mount capacity</h4>
        <span>{totalMounts} mounts</span>
      </div>
      <div className="asset-disk-capacity-grid">
        {mounts.map(({ mountPoint, disk }) => (
          <DiskCapacityTile key={mountPoint} mountPoint={mountPoint} disk={disk} />
        ))}
      </div>
      <p className="asset-panel-footnote">전체 {totalMounts} mount · {diskIoDeviceCount} I/O devices</p>
    </section>
  );
}

function DiskCapacityTile({ mountPoint, disk }: { mountPoint: string; disk?: AssetDiskRow }) {
  return (
    <div className={cn('asset-disk-capacity-tile', metricTone(disk?.usedPct))}>
      <span>{mountPoint}</span>
      <strong>{formatPercent(disk?.usedPct)}</strong>
      <small>{disk ? `${formatBytes(disk.availableBytes)} free · ${disk.device ?? disk.filesystem ?? 'device unknown'}` : '수집 없음'}</small>
    </div>
  );
}

function SignalPosturePanel({
  security,
  interfacesCount,
  processesCount,
  processSocketCount,
  listeningCount,
  connectedCount,
  publicListeningCount
}: {
  security?: AssetMetricSummary['security'];
  interfacesCount: number;
  processesCount: number;
  processSocketCount: number;
  listeningCount: number;
  connectedCount: number;
  publicListeningCount: number;
}) {
  const openPorts = security?.openPorts ?? listeningCount;
  const failedServices = security?.failedServices ?? 0;
  const firewallDisabled = security?.firewallDisabled ?? 0;
  return (
    <section className="asset-signal-posture-panel asset-signal-main-panel">
      <div className="panel-heading">
        <h4>Signal summary</h4>
        <span>{securitySignalLabel(security)}</span>
      </div>
      <div className={cn('asset-signal-hero', openPorts > 0 && 'warning', (failedServices > 0 || firewallDisabled > 0) && 'critical')}>
        <div>
          <strong>{openPorts}</strong>
          <span>open ports</span>
        </div>
        <p>{publicListeningCount} public listen · {connectedCount} connected · {processesCount} processes</p>
      </div>
      <ul className="asset-signal-check-list">
        <li className={failedServices > 0 ? 'warning' : 'healthy'}>
          <span>Failed services</span>
          <strong>{failedServices}</strong>
          <em>service state</em>
        </li>
        <li className={firewallDisabled > 0 ? 'critical' : 'healthy'}>
          <span>Firewall disabled</span>
          <strong>{firewallDisabled}</strong>
          <em>host firewall</em>
        </li>
        <li className={processSocketCount > 0 ? 'neutral' : 'healthy'}>
          <span>Socket coverage</span>
          <strong>{processSocketCount}</strong>
          <em>process-linked sockets</em>
        </li>
      </ul>
      <p className="asset-panel-footnote">{interfacesCount} interfaces · {processesCount} processes · {processSocketCount} sockets</p>
    </section>
  );
}

function OpenPortsPanel({
  sockets,
  declaredOpenPorts
}: {
  sockets: AgentSocketState[];
  declaredOpenPorts: number;
}) {
  return (
    <section className="asset-open-ports-panel">
      <div className="panel-heading">
        <h4>Open ports</h4>
        <span>{declaredOpenPorts} listening</span>
      </div>
      {sockets.length === 0 ? (
        <p className="asset-empty-detail">
          {declaredOpenPorts > 0
            ? 'open port count는 있지만 socket 상세 row가 응답에 없습니다.'
            : '수집된 listening socket이 없습니다.'}
        </p>
      ) : (
        <div className="asset-open-port-grid">
          {sockets.map((socket, index) => (
            <article
              className={cn('asset-open-port-card', isPublicListeningSocket(socket) && 'public')}
              key={`${socket.stateKey ?? socket.processId ?? 'socket'}-${socket.protocol ?? 'socket'}-${socket.localAddress ?? ''}-${socket.localPort ?? ''}-${index}`}
            >
              <div className="asset-open-port-title">
                <strong>{socketEndpoint(socket.localAddress, socket.localPort)}</strong>
                <Badge variant="secondary">{socket.protocol ?? 'socket'}</Badge>
              </div>
              <span>{socket.processName ?? 'process unknown'}{socket.processId != null ? ` · pid ${socket.processId}` : ''}</span>
              <em>{isPublicListeningSocket(socket) ? 'public listen' : 'local listen'} · {formatDate(socket.observedAt)}</em>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function ProcessNetstatPanel({ processes, sockets }: { processes: AgentProcessState[]; sockets: AgentSocketState[] }) {
  const groups = buildProcessSocketGroups(processes, sockets);
  return (
    <section className="asset-detail-list-panel asset-netstat-panel">
      <div className="panel-heading">
        <h4>Process/socket map</h4>
        <span>{sockets.length} sockets · {groups.length} processes</span>
      </div>
      {groups.length === 0 ? (
        <p className="asset-empty-detail">수집된 process/socket 연결 정보가 없습니다.</p>
      ) : (
        <div className="asset-netstat-table">
          <table>
            <thead>
              <tr>
                <th>Socket</th>
                <th>Local</th>
                <th>Remote</th>
                <th>State</th>
                <th>Direction</th>
              </tr>
            </thead>
            {groups.map((group) => (
              <tbody key={group.key}>
                <tr className="asset-netstat-group-row">
                  <td colSpan={5}>
                    <div>
                      <strong>{group.processName}</strong>
                      <span>{processGroupMeta(group)}</span>
                    </div>
                    {group.executablePath && <em>{group.executablePath}</em>}
                  </td>
                </tr>
                {group.sockets.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="asset-netstat-empty-row">
                      socket 상세 row 없음 · process count {processSocketTotal(group.process)} sockets
                    </td>
                  </tr>
                ) : group.sockets.map((socket, index) => (
                  <tr key={`${socket.stateKey ?? socket.processId ?? group.key}-${socket.protocol ?? 'socket'}-${socket.localAddress ?? ''}-${socket.localPort ?? ''}-${socket.remoteAddress ?? ''}-${socket.remotePort ?? ''}-${index}`}>
                    <td>{socket.protocol ?? '-'}</td>
                    <td className={cn('asset-netstat-endpoint', isPublicListeningSocket(socket) && 'public')}>{socketEndpoint(socket.localAddress, socket.localPort)}</td>
                    <td className="asset-netstat-endpoint">{remoteSocketEndpoint(socket)}</td>
                    <td><span className={cn('asset-netstat-state', socketStateClass(socket))}>{socket.state ?? '-'}</span></td>
                    <td>{socket.direction ?? socketDirection(socket)}</td>
                  </tr>
                ))}
              </tbody>
            ))}
          </table>
        </div>
      )}
    </section>
  );
}

function ChartBlock({ title, points, meta, empty, children }: { title: string; points: number; meta?: ReactNode; empty: string; children: ReactNode }) {
  return (
    <section className="asset-chart-panel">
      <div className="panel-heading">
        <h4>{title}</h4>
        <span>{meta ?? `${points} points`}</span>
      </div>
      {points === 0 ? <p className="asset-empty-detail">{empty}</p> : children}
    </section>
  );
}

function PercentAreaChart({ data, color, name }: { data: MetricPoint[]; color: string; name: string }) {
  const config = { value: { label: name, color } } satisfies ChartConfig;
  return (
    <ChartContainer config={config} className="asset-chart-box">
      <AreaChart data={chartData(data)}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="label" tickLine={false} axisLine={false} />
        <YAxis tickLine={false} axisLine={false} domain={[0, 100]} width={34} />
        <ChartTooltip content={<ChartTooltipContent formatter={(value) => formatPercent(Number(value))} />} />
        <Area type="monotone" dataKey="value" name={name} stroke="var(--color-value)" fill="var(--color-value)" fillOpacity={0.16} strokeWidth={2} isAnimationActive={false} />
      </AreaChart>
    </ChartContainer>
  );
}

function NetworkLineChart({ data }: { data: MetricPoint[] }) {
  const config = {
    inBps: { label: 'RX', color: 'var(--chart-1)' },
    outBps: { label: 'TX', color: 'var(--chart-2)' }
  } satisfies ChartConfig;
  return (
    <ChartContainer config={config} className="asset-chart-box">
      <LineChart data={chartData(data)}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="label" tickLine={false} axisLine={false} />
        <YAxis tickLine={false} axisLine={false} width={52} tickFormatter={(value) => formatBps(Number(value)).replace(' ', '')} />
        <ChartTooltip content={<ChartTooltipContent formatter={(value) => formatBps(Number(value))} />} />
        <ChartLegend content={<ChartLegendContent />} />
        <Line type="monotone" dataKey="inBps" name="RX" stroke="var(--color-inBps)" strokeWidth={2} dot={false} isAnimationActive={false} />
        <Line type="monotone" dataKey="outBps" name="TX" stroke="var(--color-outBps)" strokeWidth={2} dot={false} isAnimationActive={false} />
      </LineChart>
    </ChartContainer>
  );
}

function DiskIoLineChart({ data }: { data: MetricPoint[] }) {
  const config = {
    readBps: { label: 'Read', color: 'var(--chart-1)' },
    writeBps: { label: 'Write', color: 'var(--chart-3)' }
  } satisfies ChartConfig;
  return (
    <ChartContainer config={config} className="asset-chart-box">
      <LineChart data={chartData(data)}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="label" tickLine={false} axisLine={false} />
        <YAxis tickLine={false} axisLine={false} width={62} tickFormatter={(value) => formatBytesPerSecond(Number(value)).replace(' ', '')} />
        <ChartTooltip content={<ChartTooltipContent formatter={(value) => formatBytesPerSecond(Number(value))} />} />
        <ChartLegend content={<ChartLegendContent />} />
        <Line type="monotone" dataKey="readBps" name="Read" stroke="var(--color-readBps)" strokeWidth={2} dot={false} isAnimationActive={false} />
        <Line type="monotone" dataKey="writeBps" name="Write" stroke="var(--color-writeBps)" strokeWidth={2} dot={false} isAnimationActive={false} />
      </LineChart>
    </ChartContainer>
  );
}

function DetailList({
  title,
  meta,
  empty,
  className,
  children
}: {
  title: string;
  meta: string;
  empty: string;
  className?: string;
  children: ReactNode;
}) {
  const items = Array.isArray(children) ? children.filter(Boolean) : children ? [children] : [];
  return (
    <section className={cn('asset-detail-list-panel', className)}>
      <div className="panel-heading">
        <h4>{title}</h4>
        <span>{meta}</span>
      </div>
      {items.length === 0 ? <p className="asset-empty-detail">{empty}</p> : <ul>{children}</ul>}
    </section>
  );
}

function HealthBadge({ health }: { health: AssetMetricSummary['health'] }) {
  const variant = health === 'critical' ? 'critical' : health === 'warning' ? 'warning' : health === 'healthy' ? 'success' : 'muted';
  return <Badge variant={variant}>{healthLabel(health)}</Badge>;
}

function SourceBadges({ asset }: { asset: AssetMetricSummary }) {
  const sources = [
    ['REG', asset.sources.registered],
    ['AGENT', asset.sources.agent],
    ['SNMP', asset.sources.snmp],
    ['TRAFFIC', asset.sources.traffic],
    ['DISKIO', asset.sources.diskIo],
    ['SIG', asset.sources.security]
  ] as const;
  const visibleSources = sources.filter(([, enabled]) => enabled);
  if (visibleSources.length === 0) {
    return null;
  }
  return (
    <div className="asset-source-badges">
      {visibleSources.map(([label]) => (
        <Badge key={label} variant="secondary" className="px-2 py-0 text-[10px]">{label}</Badge>
      ))}
    </div>
  );
}

function sortAssetsForScan(assets: AssetMetricSummary[]) {
  return [...assets].sort((left, right) => {
    const rankDelta = assetScanRank(left) - assetScanRank(right);
    if (rankDelta !== 0) {
      return rankDelta;
    }
    const leftSeen = Date.parse(left.lastSeenAt ?? '');
    const rightSeen = Date.parse(right.lastSeenAt ?? '');
    const seenDelta = (Number.isNaN(rightSeen) ? 0 : rightSeen) - (Number.isNaN(leftSeen) ? 0 : leftSeen);
    return seenDelta || left.name.localeCompare(right.name, 'ko-KR');
  });
}

function assetScanRank(asset: AssetMetricSummary) {
  if (asset.health === 'critical') {
    return 0;
  }
  if (asset.health === 'warning') {
    return 1;
  }
  if (asset.stale) {
    return 2;
  }
  if (asset.health === 'unknown') {
    return 3;
  }
  return 4;
}

function securitySignalLabel(security?: AssetMetricSummary['security']) {
  const ports = security?.openPorts ?? 0;
  const failed = security?.failedServices ?? 0;
  const firewall = security?.firewallDisabled ?? 0;
  const total = ports + failed + firewall;
  if (total === 0) {
    return '신호 없음';
  }
  return `${total} signals`;
}

function chartData(data: MetricPoint[]) {
  return data.map((point) => ({
    ...point,
    label: formatTime(point.timestamp),
    value: normalizeNumber(point.value),
    inBps: normalizeNumber(point.inBps),
    outBps: normalizeNumber(point.outBps),
    readBps: normalizeNumber(point.readBps),
    writeBps: normalizeNumber(point.writeBps),
    readIops: normalizeNumber(point.readIops),
    writeIops: normalizeNumber(point.writeIops),
    utilizationPct: normalizeNumber(point.utilizationPct)
  }));
}

function fallbackOverview(assets: Asset[], range: string): AssetMetricsOverview {
  return {
    range,
    summary: {
      totalAssets: assets.length,
      observedAssets: 0,
      staleAssets: 0,
      criticalAssets: 0,
      warningAssets: 0,
      totalNetworkInBps: 0,
      totalNetworkOutBps: 0
    },
    assets: assets.map((asset) => ({
      id: asset.id,
      assetUid: asset.assetUid,
      name: asset.name,
      assetType: asset.assetType,
      managementIp: asset.managementIp,
      location: asset.location,
      description: asset.description,
      status: asset.status,
      lastSeenAt: asset.lastSeenAt,
      stale: false,
      health: 'unknown',
      sources: { registered: true, observed: false },
      metrics: {}
    }))
  };
}

function findDiskByMount(disks: AssetDiskRow[], mountPoint: string) {
  return disks.find((disk) => disk.mountPoint?.trim() === mountPoint);
}

function metricTone(value?: number | null): 'neutral' | 'warning' | 'critical' {
  if (value == null || !Number.isFinite(value)) {
    return 'neutral';
  }
  if (value >= 90) {
    return 'critical';
  }
  if (value >= 80) {
    return 'warning';
  }
  return 'neutral';
}

function healthLabel(health: AssetMetricSummary['health']) {
  if (health === 'critical') {
    return 'CRITICAL';
  }
  if (health === 'warning') {
    return 'WARNING';
  }
  if (health === 'healthy') {
    return 'HEALTHY';
  }
  return '미수집';
}

function buildProcessSocketGroups(processes: AgentProcessState[], sockets: AgentSocketState[]): ProcessSocketGroup[] {
  const processByPid = new Map<number, AgentProcessState>();
  const processBySocketKey = new Map<string, AgentProcessState>();
  const processByName = new Map<string, AgentProcessState[]>();
  for (const process of processes) {
    if (process.pid != null) {
      processByPid.set(process.pid, process);
    }
    for (const socketKey of process.socketKeys ?? []) {
      processBySocketKey.set(socketKey, process);
    }
    if (process.name) {
      processByName.set(process.name, [...(processByName.get(process.name) ?? []), process]);
    }
  }

  const groups = new Map<string, ProcessSocketGroup>();
  const ensureGroup = (socket?: AgentSocketState, process?: AgentProcessState) => {
    const pid = process?.pid ?? socket?.processId;
    const processName = process?.name ?? socket?.processName ?? 'unknown process';
    const key = pid != null ? `pid:${pid}` : `name:${processName}`;
    const existing = groups.get(key);
    if (existing) {
      return existing;
    }
    const created: ProcessSocketGroup = {
      key,
      process,
      processName,
      pid,
      user: process?.user,
      executablePath: process?.executablePath,
      sockets: []
    };
    groups.set(key, created);
    return created;
  };

  for (const socket of sockets) {
    const process = processForSocket(socket, processByPid, processBySocketKey, processByName);
    ensureGroup(socket, process).sockets.push(socket);
  }
  for (const process of processes) {
    if (processSocketTotal(process) > 0) {
      ensureGroup(undefined, process);
    }
  }

  return [...groups.values()]
    .map((group) => ({
      ...group,
      sockets: [...group.sockets].sort(socketComparator)
    }))
    .sort((left, right) => {
      const leftSockets = Math.max(left.sockets.length, processSocketTotal(left.process));
      const rightSockets = Math.max(right.sockets.length, processSocketTotal(right.process));
      return rightSockets - leftSockets || left.processName.localeCompare(right.processName, 'ko-KR');
    });
}

function processForSocket(
  socket: AgentSocketState,
  processByPid: Map<number, AgentProcessState>,
  processBySocketKey: Map<string, AgentProcessState>,
  processByName: Map<string, AgentProcessState[]>
) {
  if (socket.processId != null) {
    const process = processByPid.get(socket.processId);
    if (process) {
      return process;
    }
  }
  if (socket.stateKey) {
    const process = processBySocketKey.get(socket.stateKey);
    if (process) {
      return process;
    }
  }
  if (socket.processName) {
    const candidates = processByName.get(socket.processName);
    if (candidates?.length === 1) {
      return candidates[0];
    }
  }
  return undefined;
}

function socketComparator(left: AgentSocketState, right: AgentSocketState) {
  const leftListening = isListeningSocketRow(left) ? 0 : 1;
  const rightListening = isListeningSocketRow(right) ? 0 : 1;
  return leftListening - rightListening
    || (left.localPort ?? 0) - (right.localPort ?? 0)
    || socketEndpoint(left.localAddress, left.localPort).localeCompare(socketEndpoint(right.localAddress, right.localPort));
}

function processGroupMeta(group: ProcessSocketGroup) {
  const listeningCount = group.sockets.filter(isListeningSocketRow).length || (group.process?.listeningSocketCount ?? 0);
  const connectedCount = group.sockets.filter(isConnectedSocketRow).length || (group.process?.connectedSocketCount ?? 0);
  return [
    group.pid == null ? 'pid unknown' : `pid ${group.pid}`,
    group.user ?? 'user unknown',
    formatBytes(group.process?.memoryBytes),
    `${listeningCount} listening`,
    `${connectedCount} connected`
  ].join(' · ');
}

function processSocketTotal(process?: AgentProcessState) {
  return (process?.listeningSocketCount ?? 0) + (process?.connectedSocketCount ?? 0);
}

function socketEndpoint(address?: string, port?: number) {
  if (!address && port == null) {
    return '-';
  }
  return `${address || '*'}:${port ?? '-'}`;
}

function remoteSocketEndpoint(socket: AgentSocketState) {
  if (!socket.remoteAddress || socket.remotePort == null || socket.remotePort <= 0 || isAnyAddress(socket.remoteAddress)) {
    return '-';
  }
  return socketEndpoint(socket.remoteAddress, socket.remotePort);
}

function socketDirection(socket: AgentSocketState) {
  if (isListeningSocketRow(socket)) {
    return 'listening';
  }
  if (isConnectedSocketRow(socket)) {
    return 'connected';
  }
  return 'bound';
}

function socketStateClass(socket: AgentSocketState) {
  if (isPublicListeningSocket(socket)) {
    return 'warning';
  }
  if (isConnectedSocketRow(socket)) {
    return 'info';
  }
  return 'neutral';
}

function isListeningSocketRow(socket: AgentSocketState) {
  return socket.direction === 'listening' || socket.state === 'listen';
}

function isConnectedSocketRow(socket: AgentSocketState) {
  return socket.direction === 'connected' || socket.state === 'established';
}

function isPublicListeningSocket(socket: AgentSocketState) {
  return isListeningSocketRow(socket) && isAnyAddress(socket.localAddress);
}

function isAnyAddress(address?: string) {
  return !address || address === '0.0.0.0' || address === '::' || address === '::0' || address === '[::]';
}

function formatPercent(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '미수집';
  }
  return `${value.toFixed(1)}%`;
}

function formatBytes(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let next = value;
  let index = 0;
  while (next >= 1024 && index < units.length - 1) {
    next /= 1024;
    index++;
  }
  return `${next.toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}

function formatBytesPerSecond(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  return `${formatBytes(value)}/s`;
}

function formatIops(value?: number | null) {
  if (value == null || !Number.isFinite(value)) {
    return '-';
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} IOPS`;
}

function formatDate(value?: string | null) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('ko-KR');
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
}

function normalizeNumber(value?: number | null) {
  return value == null || !Number.isFinite(value) ? null : Number(value.toFixed(2));
}

function finiteNumber(value?: number | null) {
  return value == null || !Number.isFinite(value) ? 0 : value;
}

function isFiniteNumber(value?: number | null): value is number {
  return value != null && Number.isFinite(value);
}

function optionalText(value: string) {
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : undefined;
}
