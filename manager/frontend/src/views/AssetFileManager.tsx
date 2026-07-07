import {
  ArrowDownToLine,
  ArrowLeft,
  ArrowRight,
  ArrowUp,
  Clipboard,
  Copy,
  Edit3,
  FileIcon,
  Folder,
  FolderOpen,
  FolderPlus,
  HardDrive,
  RefreshCw,
  Scissors,
  Trash2,
  Upload
} from 'lucide-react';
import { type ChangeEvent, type MouseEvent, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip';
import { api } from '../lib/api';
import type { AssetFileCommand, AssetFileEntry, AssetFileListResponse } from '../lib/types';
import { cn } from '../lib/utils';

type ClipboardState = {
  mode: 'copy' | 'cut';
  paths: string[];
};

type AssetFileManagerProps = {
  assetUid: string;
  active: boolean;
  canAccess: boolean;
};

const COMMAND_POLL_MS = 700;
const COMMAND_POLL_LIMIT = 140;

export function AssetFileManager({ assetUid, active, canAccess }: AssetFileManagerProps) {
  const uploadInputRef = useRef<HTMLInputElement>(null);
  const [currentPath, setCurrentPath] = useState('');
  const [address, setAddress] = useState('');
  const [fileList, setFileList] = useState<AssetFileListResponse>({ path: '', roots: [], entries: [] });
  const [selectedPaths, setSelectedPaths] = useState<Set<string>>(() => new Set());
  const [clipboard, setClipboard] = useState<ClipboardState | null>(null);
  const [backStack, setBackStack] = useState<string[]>([]);
  const [forwardStack, setForwardStack] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');

  const selectedEntries = useMemo(
    () => fileList.entries.filter((entry) => selectedPaths.has(entry.path)),
    [fileList.entries, selectedPaths]
  );
  const selectedPathList = useMemo(() => Array.from(selectedPaths), [selectedPaths]);
  const canOperate = active && canAccess && !busy;
  const selectedFile = selectedEntries.length === 1 && !selectedEntries[0].directory ? selectedEntries[0] : null;

  const pollCommand = useCallback(
    async (initial: AssetFileCommand) => {
      let command = initial;
      for (let attempt = 0; attempt < COMMAND_POLL_LIMIT; attempt += 1) {
        if (command.status === 'SUCCEEDED') {
          return command;
        }
        if (command.status === 'FAILED') {
          throw new Error(command.errorMessage || '파일 작업이 실패했습니다.');
        }
        await delay(COMMAND_POLL_MS);
        command = await api.getAssetFileCommand(assetUid, command.commandId);
      }
      throw new Error('파일 작업 응답 시간이 초과되었습니다.');
    },
    [assetUid]
  );

  const loadPath = useCallback(
    async (targetPath: string) => {
      if (!active || !canAccess) {
        return false;
      }
      setBusy(true);
      setError('');
      setStatus('목록을 불러오는 중');
      try {
        const command = await api.createAssetFileCommand(
          assetUid,
          targetPath ? 'LIST' : 'ROOTS',
          targetPath ? { path: targetPath } : {}
        );
        const completed = await pollCommand(command);
        const nextList = commandResponse<AssetFileListResponse>(completed);
        if (!nextList) {
          throw new Error('파일 목록 응답이 비어 있습니다.');
        }
        const normalizedPath = nextList.path ?? targetPath;
        setFileList(nextList);
        setCurrentPath(normalizedPath);
        setAddress(normalizedPath);
        setSelectedPaths(new Set());
        setStatus('준비됨');
        return true;
      } catch (loadError) {
        setError(messageOf(loadError));
        setStatus('오류');
        return false;
      } finally {
        setBusy(false);
      }
    },
    [active, assetUid, canAccess, pollCommand]
  );

  useEffect(() => {
    if (!active || !canAccess) {
      return;
    }
    setCurrentPath('');
    setAddress('');
    setBackStack([]);
    setForwardStack([]);
    void loadPath('');
  }, [active, assetUid, canAccess, loadPath]);

  const openPath = useCallback(
    async (targetPath: string) => {
      if (targetPath === currentPath) {
        return;
      }
      const previous = currentPath;
      const ok = await loadPath(targetPath);
      if (ok) {
        setBackStack((stack) => [...stack, previous]);
        setForwardStack([]);
      }
    },
    [currentPath, loadPath]
  );

  const navigateBack = async () => {
    const previous = backStack[backStack.length - 1];
    if (previous === undefined) {
      return;
    }
    const ok = await loadPath(previous);
    if (ok) {
      setBackStack((stack) => stack.slice(0, -1));
      setForwardStack((stack) => [currentPath, ...stack]);
    }
  };

  const navigateForward = async () => {
    const next = forwardStack[0];
    if (next === undefined) {
      return;
    }
    const ok = await loadPath(next);
    if (ok) {
      setForwardStack((stack) => stack.slice(1));
      setBackStack((stack) => [...stack, currentPath]);
    }
  };

  const navigateUp = async () => {
    const parent = parentPath(currentPath);
    if (parent !== null) {
      await openPath(parent);
    }
  };

  const runMutation = async (operation: string, payload: Record<string, unknown>, successMessage: string) => {
    setBusy(true);
    setError('');
    setStatus(successMessage);
    try {
      const command = await api.createAssetFileCommand(assetUid, operation, payload);
      await pollCommand(command);
      setStatus('완료됨');
      await loadPath(currentPath);
      return true;
    } catch (mutationError) {
      setError(messageOf(mutationError));
      setStatus('오류');
      return false;
    } finally {
      setBusy(false);
    }
  };

  const createFolder = async () => {
    if (!currentPath) {
      setError('루트 목록에서는 폴더를 만들 수 없습니다.');
      return;
    }
    const name = window.prompt('새 폴더 이름');
    if (!name) {
      return;
    }
    await runMutation('MKDIR', { path: joinPath(currentPath, name.trim()) }, '폴더를 만드는 중');
  };

  const renameSelected = async () => {
    if (selectedEntries.length !== 1) {
      return;
    }
    const currentName = selectedEntries[0].name;
    const nextName = window.prompt('새 이름', currentName);
    if (!nextName || nextName === currentName) {
      return;
    }
    await runMutation('RENAME', { path: selectedEntries[0].path, new_name: nextName.trim() }, '이름을 바꾸는 중');
  };

  const deleteSelected = async () => {
    if (selectedPathList.length === 0 || !window.confirm('선택한 항목을 삭제할까요?')) {
      return;
    }
    await runMutation('DELETE', { paths: selectedPathList, recursive: true }, '삭제하는 중');
  };

  const copySelected = () => {
    if (selectedPathList.length > 0) {
      setClipboard({ mode: 'copy', paths: selectedPathList });
      setStatus('복사할 항목이 준비됨');
    }
  };

  const cutSelected = () => {
    if (selectedPathList.length > 0) {
      setClipboard({ mode: 'cut', paths: selectedPathList });
      setStatus('이동할 항목이 준비됨');
    }
  };

  const pasteClipboard = async () => {
    if (!clipboard || !currentPath) {
      return;
    }
    const ok = await runMutation(
      clipboard.mode === 'copy' ? 'COPY' : 'MOVE',
      { source_paths: clipboard.paths, destination_dir: currentPath, overwrite: true },
      clipboard.mode === 'copy' ? '붙여넣는 중' : '이동하는 중'
    );
    if (ok && clipboard.mode === 'cut') {
      setClipboard(null);
    }
  };

  const handleUpload = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !currentPath) {
      return;
    }
    setBusy(true);
    setError('');
    setStatus('업로드하는 중');
    try {
      const command = await api.uploadAssetFile(assetUid, joinPath(currentPath, file.name), file, true);
      await pollCommand(command);
      setStatus('업로드 완료');
      await loadPath(currentPath);
    } catch (uploadError) {
      setError(messageOf(uploadError));
      setStatus('오류');
    } finally {
      setBusy(false);
    }
  };

  const downloadSelected = async () => {
    if (!selectedFile) {
      return;
    }
    setBusy(true);
    setError('');
    setStatus('다운로드 준비 중');
    try {
      const command = await api.createAssetFileDownload(assetUid, selectedFile.path);
      const completed = await pollCommand(command);
      const blob = await api.downloadAssetFileCommand(assetUid, completed.commandId);
      saveBlob(blob, selectedFile.name || 'download.bin');
      setStatus('다운로드 완료');
    } catch (downloadError) {
      setError(messageOf(downloadError));
      setStatus('오류');
    } finally {
      setBusy(false);
    }
  };

  const submitAddress = async () => {
    const target = address.trim();
    if (target === currentPath) {
      return;
    }
    await openPath(target);
  };

  const toggleSelection = (entry: AssetFileEntry, event: MouseEvent) => {
    setSelectedPaths((current) => {
      if (!event.ctrlKey && !event.metaKey) {
        return new Set([entry.path]);
      }
      const next = new Set(current);
      if (next.has(entry.path)) {
        next.delete(entry.path);
      } else {
        next.add(entry.path);
      }
      return next;
    });
  };

  if (!canAccess) {
    return (
      <div className="asset-file-manager is-locked">
        <div className="asset-file-empty">
          <FolderOpen size={28} />
          <strong>파일 관리자 접근 권한 없음</strong>
          <span>운영자 이상 권한에서 사용할 수 있습니다.</span>
        </div>
      </div>
    );
  }

  return (
    <TooltipProvider>
      <div className="asset-file-manager">
        <div className="asset-file-toolbar" aria-label="파일 관리자 도구 모음">
          <IconButton label="뒤로" disabled={!canOperate || backStack.length === 0} onClick={navigateBack}>
            <ArrowLeft size={16} />
          </IconButton>
          <IconButton label="앞으로" disabled={!canOperate || forwardStack.length === 0} onClick={navigateForward}>
            <ArrowRight size={16} />
          </IconButton>
          <IconButton label="상위 폴더" disabled={!canOperate || parentPath(currentPath) === null} onClick={navigateUp}>
            <ArrowUp size={16} />
          </IconButton>
          <IconButton label="새로고침" disabled={!canOperate} onClick={() => void loadPath(currentPath)}>
            <RefreshCw size={16} className={busy ? 'asset-refresh-spin' : undefined} />
          </IconButton>
          <div className="asset-file-address">
            <Input
              value={address}
              onChange={(event) => setAddress(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  void submitAddress();
                }
              }}
              disabled={!canOperate}
              aria-label="파일 경로"
              placeholder="루트"
            />
          </div>
          <IconButton label="새 폴더" disabled={!canOperate || !currentPath} onClick={createFolder}>
            <FolderPlus size={16} />
          </IconButton>
          <IconButton label="업로드" disabled={!canOperate || !currentPath} onClick={() => uploadInputRef.current?.click()}>
            <Upload size={16} />
          </IconButton>
          <IconButton label="다운로드" disabled={!canOperate || !selectedFile} onClick={downloadSelected}>
            <ArrowDownToLine size={16} />
          </IconButton>
          <IconButton label="복사" disabled={!canOperate || selectedPathList.length === 0} onClick={copySelected}>
            <Copy size={16} />
          </IconButton>
          <IconButton label="잘라내기" disabled={!canOperate || selectedPathList.length === 0} onClick={cutSelected}>
            <Scissors size={16} />
          </IconButton>
          <IconButton label="붙여넣기" disabled={!canOperate || !clipboard || !currentPath} onClick={pasteClipboard}>
            <Clipboard size={16} />
          </IconButton>
          <IconButton label="이름 변경" disabled={!canOperate || selectedEntries.length !== 1} onClick={renameSelected}>
            <Edit3 size={16} />
          </IconButton>
          <IconButton label="삭제" disabled={!canOperate || selectedPathList.length === 0} onClick={deleteSelected} destructive>
            <Trash2 size={16} />
          </IconButton>
          <input ref={uploadInputRef} type="file" className="asset-file-upload-input" onChange={handleUpload} />
        </div>

        <div className="asset-file-shell">
          <aside className="asset-file-roots" aria-label="루트 경로">
            <button
              type="button"
              className={cn('asset-file-root', currentPath === '' && 'active')}
              onClick={() => void openPath('')}
              disabled={!canOperate}
            >
              <HardDrive size={16} />
              <span>루트</span>
            </button>
            {fileList.roots.map((root) => (
              <button
                key={root.path}
                type="button"
                className={cn('asset-file-root', currentPath === root.path && 'active')}
                onClick={() => void openPath(root.path)}
                disabled={!canOperate}
              >
                <HardDrive size={16} />
                <span>{root.name || root.path}</span>
              </button>
            ))}
          </aside>

          <div className="asset-file-table-wrap">
            <Table className="asset-file-table">
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>수정한 날짜</TableHead>
                  <TableHead>유형</TableHead>
                  <TableHead className="numeric">크기</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {fileList.entries.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4}>
                      <div className="asset-file-empty-row">표시할 항목 없음</div>
                    </TableCell>
                  </TableRow>
                )}
                {fileList.entries.map((entry) => (
                  <TableRow
                    key={entry.path}
                    className={cn(selectedPaths.has(entry.path) && 'selected')}
                    onClick={(event) => toggleSelection(entry, event)}
                    onDoubleClick={() => entry.directory && void openPath(entry.path)}
                  >
                    <TableCell>
                      <button type="button" className="asset-file-name-button">
                        {entry.directory ? <Folder size={16} /> : <FileIcon size={16} />}
                        <span>{entry.name}</span>
                      </button>
                    </TableCell>
                    <TableCell>{formatDate(entry.modifiedAt)}</TableCell>
                    <TableCell>{entry.directory ? '폴더' : entry.extension ? `${entry.extension.toUpperCase()} 파일` : '파일'}</TableCell>
                    <TableCell className="numeric">{entry.directory ? '' : formatBytes(entry.sizeBytes)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>

        <div className="asset-file-status">
          <span>{status}</span>
          <span>{selectedPathList.length > 0 ? `${selectedPathList.length}개 선택됨` : `${fileList.entries.length}개 항목`}</span>
          {clipboard && <span>{clipboard.mode === 'copy' ? '복사' : '이동'} 대기 {clipboard.paths.length}개</span>}
          {error && <strong>{error}</strong>}
        </div>
      </div>
    </TooltipProvider>
  );
}

function IconButton({
  label,
  disabled,
  destructive,
  onClick,
  children
}: {
  label: string;
  disabled?: boolean;
  destructive?: boolean;
  onClick: () => void;
  children: ReactNode;
}) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          type="button"
          size="icon"
          variant={destructive ? 'destructive' : 'outline'}
          aria-label={label}
          disabled={disabled}
          onClick={onClick}
        >
          {children}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}

function commandResponse<T>(command: AssetFileCommand): T | null {
  if (!command.responseJson) {
    return null;
  }
  return camelize(JSON.parse(command.responseJson)) as T;
}

function camelize(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(camelize);
  }
  if (value && typeof value === 'object') {
    const next: Record<string, unknown> = {};
    Object.entries(value as Record<string, unknown>).forEach(([key, entry]) => {
      next[camelKey(key)] = camelize(entry);
    });
    return next;
  }
  return value;
}

function camelKey(key: string) {
  return key.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase());
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function messageOf(error: unknown) {
  return error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.';
}

function parentPath(path: string): string | null {
  if (!path) {
    return null;
  }
  const separator = path.includes('\\') && !path.includes('/') ? '\\' : '/';
  const trimmed = trimTrailingSeparators(path, separator);
  if (!trimmed || trimmed === separator || /^[A-Za-z]:\\?$/.test(trimmed)) {
    return trimmed === separator ? '' : null;
  }
  const index = trimmed.lastIndexOf(separator);
  if (index < 0) {
    return '';
  }
  if (separator === '/' && index === 0) {
    return '/';
  }
  if (separator === '\\' && /^[A-Za-z]:$/.test(trimmed.slice(0, index))) {
    return trimmed.slice(0, index + 1);
  }
  return trimmed.slice(0, index);
}

function trimTrailingSeparators(path: string, separator: string) {
  let next = path;
  while (next.length > 1 && next.endsWith(separator) && !/^[A-Za-z]:\\$/.test(next)) {
    next = next.slice(0, -1);
  }
  return next;
}

function joinPath(directory: string, name: string) {
  const separator = directory.includes('\\') && !directory.includes('/') ? '\\' : '/';
  if (directory.endsWith('/') || directory.endsWith('\\')) {
    return `${directory}${name}`;
  }
  return `${directory}${separator}${name}`;
}

function formatBytes(value?: number) {
  const bytes = Number(value ?? 0);
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ['KB', 'MB', 'GB', 'TB'];
  let size = bytes / 1024;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  return `${size >= 10 ? size.toFixed(1) : size.toFixed(2)} ${units[index]}`;
}

function formatDate(value?: string) {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
