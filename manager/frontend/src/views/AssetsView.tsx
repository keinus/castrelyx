import {
  ChevronDown,
  ChevronRight,
  Edit3,
  ListChecks,
  MapPin,
  Plus,
  RefreshCw,
  Save,
  Search,
  Server,
  ShieldAlert,
  Trash2,
  X
} from 'lucide-react';
import { type FormEvent, type ReactNode, useEffect, useMemo, useState } from 'react';
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
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { ViewFrame } from '../components/ViewFrame';
import { api } from '../lib/api';
import type { Asset, AssetMetricDetail, AssetMetricSummary, AssetMetricsOverview, MetricPoint, Role } from '../lib/types';
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

type AssetTypeGroup = {
  assetType: string;
  assets: AssetMetricSummary[];
};

type AssetLocationGroup = {
  location: string;
  assets: AssetMetricSummary[];
  types: AssetTypeGroup[];
};

type AssetDiskRow = NonNullable<AssetMetricDetail['disks']>[number];
type DetailModalKind = 'disk' | 'signals';

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
  const [detailRefreshToken, setDetailRefreshToken] = useState(0);
  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [mutationError, setMutationError] = useState('');

  async function loadMetrics(nextRange = range) {
    setLoading(true);
    setError('');
    try {
      const response = await api.assetMetrics(nextRange);
      setOverview(response);
      const nextSelected = response.assets.find((asset) => asset.assetUid === selectedAssetUid)?.assetUid
        ?? response.assets[0]?.assetUid
        ?? '';
      setSelectedAssetUid(nextSelected);
      setDetailRefreshToken((value) => value + 1);
    } catch {
      setOverview(fallbackOverview(assets, nextRange));
      setDetailRefreshToken((value) => value + 1);
      setError('자산 메트릭 정보를 불러오지 못했습니다. 등록된 자산 기본 정보만 표시합니다.');
    } finally {
      setLoading(false);
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
    () => filteredAssets.find((asset) => asset.assetUid === selectedAssetUid)
      ?? overview.assets.find((asset) => asset.assetUid === selectedAssetUid)
      ?? filteredAssets[0]
      ?? overview.assets[0],
    [filteredAssets, overview.assets, selectedAssetUid]
  );

  const tree = useMemo(() => buildAssetTree(filteredAssets), [filteredAssets]);
  const summary = overview.summary;
  const canManageAssets = canMutate(role, 'asset:update');

  useEffect(() => {
    if (!selectedAsset?.assetUid) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    setDetailLoading(true);
    api.assetMetricDetail(selectedAsset.assetUid, range)
      .then((response) => {
        if (!cancelled) {
          setDetail(response);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setDetail(null);
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
  }, [detailRefreshToken, range, selectedAsset?.assetUid]);

  useEffect(() => {
    setEditing(false);
    setMutationError('');
    setEditName(selectedAsset?.name ?? '');
    setEditLocation(selectedAsset?.location ?? '');
    setEditDescription(selectedAsset?.description ?? '');
  }, [selectedAsset?.assetUid]);

  function selectAsset(assetUid: string) {
    if (assetUid === selectedAssetUid) {
      setDetailRefreshToken((value) => value + 1);
      return;
    }
    setSelectedAssetUid(assetUid);
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
    setSelectedAssetUid('');
    setDetail(null);
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
                <Button variant="outline" size="icon" aria-label="자산 새로고침" onClick={() => void loadMetrics(range)} type="button">
                  <RefreshCw size={18} />
                </Button>
              </TooltipTrigger>
              <TooltipContent>자산 새로고침</TooltipContent>
            </Tooltip>
            {canMutate(role, 'asset:create') && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button variant="outline" size="icon" aria-label="자산 추가" onClick={() => setCreating((value) => !value)} type="button">
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
        {loading && <div className="notice">자산 정보를 갱신하는 중입니다.</div>}
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

        <div className="asset-workspace-grid">
          <Card className="asset-tree-card">
            <CardHeader className="pb-3">
              <div className="asset-tree-heading">
                <div>
                  <CardTitle>자산 현황 트리</CardTitle>
                  <CardDescription>{filteredAssets.length} assets · {summary.observedAssets} observed</CardDescription>
                </div>
                <Badge variant={summary.criticalAssets > 0 ? 'critical' : 'secondary'}>{summary.criticalAssets} Critical</Badge>
              </div>
              <div className="asset-tree-toolbar">
                <label className="asset-search-field">
                  <Search size={16} aria-hidden="true" />
                  <input
                    aria-label="자산 검색"
                    placeholder="이름, UID, IP, 위치 검색"
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                  />
                </label>
                <NativeSelect aria-label="조회 범위" value={range} onChange={(event) => setRange(event.target.value as RangeOption)}>
                  {rangeOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
                </NativeSelect>
                <NativeSelect aria-label="상태 필터" value={healthFilter} onChange={(event) => setHealthFilter(event.target.value as HealthFilter)}>
                  {healthFilters.map((filter) => <option key={filter.value} value={filter.value}>{filter.label}</option>)}
                </NativeSelect>
              </div>
            </CardHeader>
            <CardContent className="pt-0">
              <AssetTree groups={tree} selectedAssetUid={selectedAsset?.assetUid ?? ''} onSelect={selectAsset} />
            </CardContent>
          </Card>

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
          />
        </div>
      </ViewFrame>
    </TooltipProvider>
  );
}

function AssetTree({
  groups,
  selectedAssetUid,
  onSelect
}: {
  groups: AssetLocationGroup[];
  selectedAssetUid: string;
  onSelect: (assetUid: string) => void;
}) {
  if (groups.length === 0) {
    return (
      <div className="asset-empty-state">
        <Server size={28} />
        <strong>조건에 맞는 자산이 없습니다.</strong>
        <span>검색어나 상태 필터를 조정해 보세요.</span>
      </div>
    );
  }

  return (
    <div className="asset-tree-list">
      {groups.map((group) => (
        <details key={group.location} open>
          <summary className="asset-tree-summary">
            <ChevronDown size={15} aria-hidden="true" />
            <MapPin size={15} aria-hidden="true" />
            <span>{group.location}</span>
            <em>{group.assets.length}</em>
          </summary>
          <div className="asset-tree-branch">
            {group.types.map((type) => (
              <details key={`${group.location}-${type.assetType}`} open>
                <summary className="asset-tree-summary asset-tree-type">
                  <ChevronRight size={15} aria-hidden="true" />
                  <span>{type.assetType}</span>
                  <em>{type.assets.length}</em>
                </summary>
                <div className="asset-tree-leaves">
                  {type.assets.map((asset) => (
                    <button
                      aria-pressed={selectedAssetUid === asset.assetUid}
                      className={cn('asset-tree-item', selectedAssetUid === asset.assetUid && 'selected')}
                      key={asset.assetUid}
                      onClick={() => onSelect(asset.assetUid)}
                      type="button"
                    >
                      <span className={cn('asset-tree-dot', asset.health)} aria-hidden="true" />
                      <div>
                        <strong>{asset.name}</strong>
                        <span>{[asset.assetUid, asset.managementIp].filter(Boolean).join(' · ') || asset.assetType}</span>
                      </div>
                      <HealthBadge health={asset.health} />
                    </button>
                  ))}
                </div>
              </details>
            ))}
          </div>
        </details>
      ))}
    </div>
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
  onDelete
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
}) {
  const [detailModal, setDetailModal] = useState<DetailModalKind | null>(null);

  useEffect(() => {
    if (asset?.assetUid) {
      setDetailModal(null);
    }
  }, [asset?.assetUid]);

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
  const processSocketCount = processes.reduce(
    (total, process) => total + (process.listeningSocketCount ?? 0) + (process.connectedSocketCount ?? 0),
    0
  );

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

        <div className="asset-main-dashboard">
          <div className="asset-chart-grid">
            <ChartBlock title="CPU Usage" points={detail?.series.cpu?.length ?? 0} meta={formatPercent(metrics.cpuUsagePct)} empty="CPU 사용률 시계열이 없습니다.">
              <PercentAreaChart data={detail?.series.cpu ?? []} color="var(--chart-1)" name="CPU" />
            </ChartBlock>
            <ChartBlock title="Memory Usage" points={detail?.series.memory?.length ?? 0} meta={formatPercent(metrics.memoryUsagePct)} empty="메모리 사용률 시계열이 없습니다.">
              <PercentAreaChart data={detail?.series.memory ?? []} color="var(--chart-2)" name="Memory" />
            </ChartBlock>
            <ChartBlock title="Disk Usage" points={detail?.series.disk?.length ?? 0} meta={formatPercent(metrics.diskUsagePct)} empty="디스크 사용률 시계열이 없습니다.">
              <PercentAreaChart data={detail?.series.disk ?? []} color="var(--chart-3)" name="Disk" />
            </ChartBlock>
            <ChartBlock title="Network RX/TX" points={detail?.series.network?.length ?? 0} meta={`${formatBps(metrics.networkInBps ?? 0)} RX / ${formatBps(metrics.networkOutBps ?? 0)} TX`} empty="인터페이스 RX/TX 시계열이 없습니다.">
              <NetworkLineChart data={detail?.series.network ?? []} />
            </ChartBlock>
            <ChartBlock
              title="Disk I/O"
              points={detail?.series.diskIo?.length ?? 0}
              meta={`${formatBytesPerSecond(metrics.diskReadBps)} read / ${formatBytesPerSecond(metrics.diskWriteBps)} write · ${formatIops(totalDiskIops)} · ${formatPercent(maxDiskIoUtilization)}`}
              empty="Disk I/O 시계열이 없습니다."
            >
              <DiskIoLineChart data={detail?.series.diskIo ?? []} />
            </ChartBlock>
          </div>

          <div className="asset-detail-lower-grid asset-main-lower-grid">
            <DiskCapacityPanel
              mounts={primaryMounts}
              totalMounts={disks.length}
              diskIoDeviceCount={diskIoRows.length}
              onOpenDetails={() => setDetailModal('disk')}
            />
            <SignalPosturePanel
              security={security}
              interfacesCount={interfaces.length}
              processesCount={processes.length}
              processSocketCount={processSocketCount}
              onOpenDetails={() => setDetailModal('signals')}
            />
          </div>
        </div>

        {detailModal === 'disk' && (
          <DetailModal title="Disk I/O / mount 상세" onClose={() => setDetailModal(null)}>
            <div className="asset-modal-grid">
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
          </DetailModal>
        )}

        {detailModal === 'signals' && (
          <DetailModal title="Signal 상세" onClose={() => setDetailModal(null)}>
            <div className="asset-modal-grid">
              <DetailList title="Interfaces" meta={`${interfaces.length} interfaces`} empty="수집된 인터페이스 정보가 없습니다.">
                {interfaces.map((row) => (
                  <li key={`${row.assetUid}-${row.interfaceName}`}>
                    <div>
                      <strong>{row.interfaceName}</strong>
                      <span>errors/drops {row.errors + row.discards}</span>
                    </div>
                    <em>{formatBps(row.inBps)} / {formatBps(row.outBps)}</em>
                  </li>
                ))}
              </DetailList>
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
            </div>
          </DetailModal>
        )}
      </CardContent>
    </Card>
  );
}

function DiskCapacityPanel({
  mounts,
  totalMounts,
  diskIoDeviceCount,
  onOpenDetails
}: {
  mounts: Array<{ mountPoint: string; disk?: AssetDiskRow }>;
  totalMounts: number;
  diskIoDeviceCount: number;
  onOpenDetails: () => void;
}) {
  return (
    <section className="asset-disk-capacity-panel">
      <div className="panel-heading">
        <h4>Mount capacity</h4>
        <Button type="button" variant="outline" size="sm" aria-label="Disk 상세 보기" onClick={onOpenDetails}>
          <ListChecks size={14} />상세
        </Button>
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
  onOpenDetails
}: {
  security?: AssetMetricSummary['security'];
  interfacesCount: number;
  processesCount: number;
  processSocketCount: number;
  onOpenDetails: () => void;
}) {
  return (
    <section className="asset-signal-posture-panel asset-signal-main-panel">
      <div className="panel-heading">
        <h4>Security Signals</h4>
        <Button type="button" variant="outline" size="sm" aria-label="Signal 상세 보기" onClick={onOpenDetails}>
          <ShieldAlert size={14} />상세
        </Button>
      </div>
      <ul className="asset-signal-check-list">
        <li className={(security?.securityEvents ?? 0) > 0 ? 'warning' : 'healthy'}>
          <span>Security events</span>
          <strong>{security?.securityEvents ?? 0}</strong>
          <em>recent events</em>
        </li>
        <li className={(security?.openPorts ?? 0) > 0 ? 'warning' : 'healthy'}>
          <span>Open ports</span>
          <strong>{security?.openPorts ?? 0}</strong>
          <em>listening sockets</em>
        </li>
        <li className={(security?.failedServices ?? 0) > 0 ? 'warning' : 'healthy'}>
          <span>Failed services</span>
          <strong>{security?.failedServices ?? 0}</strong>
          <em>service state</em>
        </li>
        <li className={(security?.firewallDisabled ?? 0) > 0 ? 'critical' : 'healthy'}>
          <span>Firewall disabled</span>
          <strong>{security?.firewallDisabled ?? 0}</strong>
          <em>host firewall</em>
        </li>
      </ul>
      <p className="asset-panel-footnote">{interfacesCount} interfaces · {processesCount} processes · {processSocketCount} sockets</p>
    </section>
  );
}

function DetailModal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <section className="modal-panel asset-detail-modal" role="dialog" aria-modal="true" aria-label={title} onClick={(event) => event.stopPropagation()}>
        <div className="asset-detail-modal-header">
          <h3>{title}</h3>
          <Button type="button" size="icon" variant="ghost" aria-label={`${title} 닫기`} onClick={onClose}>
            <X size={16} />
          </Button>
        </div>
        <div className="asset-detail-modal-body">{children}</div>
      </section>
    </div>
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

function DetailList({ title, meta, empty, children }: { title: string; meta: string; empty: string; children: ReactNode }) {
  const items = Array.isArray(children) ? children.filter(Boolean) : children ? [children] : [];
  return (
    <section className="asset-detail-list-panel">
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
    ['SEC', asset.sources.security]
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

function buildAssetTree(assets: AssetMetricSummary[]): AssetLocationGroup[] {
  const locations = new Map<string, AssetMetricSummary[]>();
  for (const asset of assets) {
    const location = asset.location?.trim() || '미지정 위치';
    const group = locations.get(location) ?? [];
    group.push(asset);
    locations.set(location, group);
  }
  return [...locations.entries()]
    .sort(([left], [right]) => left.localeCompare(right, 'ko-KR'))
    .map(([location, locationAssets]) => {
      const types = new Map<string, AssetMetricSummary[]>();
      for (const asset of locationAssets) {
        const assetType = asset.assetType || 'UNKNOWN';
        const group = types.get(assetType) ?? [];
        group.push(asset);
        types.set(assetType, group);
      }
      return {
        location,
        assets: locationAssets,
        types: [...types.entries()]
          .sort(([left], [right]) => left.localeCompare(right, 'ko-KR'))
          .map(([assetType, typeAssets]) => ({
            assetType,
            assets: [...typeAssets].sort((left, right) => left.name.localeCompare(right.name, 'ko-KR'))
          }))
      };
    });
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
