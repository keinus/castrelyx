export type RouterBinding = { enabled: boolean; agentId: string | null; routerId: string | null };
export type RouterCommand = {
  id: string; action: string; status: string; resultJson?: string; errorMessage?: string;
  createdBy: string; createdAt: string;
};
export type RouterState = {
  appliedRevision: number; desiredRevision: number; mode: string; ready: boolean;
  config: Record<string, unknown>; actions?: string[]; restartServices?: string[];
  snapshot: { state: string; observedAt: string; error?: string;
    ports?: { name: string; role: string; admin: boolean; oper: boolean }[];
    services?: { name: string; state: string; detail?: string }[] };
  capabilities?: { id: string; state: string; reason?: string }[];
};
async function request<T>(assetUid: string, path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/assets/${encodeURIComponent(assetUid)}/router-control/${path}`, {
    credentials: 'include', ...init, headers: { 'Content-Type': 'application/json', ...init?.headers }
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(body.error ?? `Router 요청 실패 (${response.status})`);
  }
  return response.json() as Promise<T>;
}
export const routerControl = {
  binding: (uid: string) => request<RouterBinding>(uid, 'binding'),
  bind: (uid: string, agentId: string, routerId: string) => request<RouterBinding>(uid, 'binding', { method: 'PUT', body: JSON.stringify({ agentId, routerId }) }),
  history: (uid: string) => request<RouterCommand[]>(uid, 'commands'),
  get: (uid: string, id: string, signal?: AbortSignal) => request<RouterCommand>(uid, `commands/${encodeURIComponent(id)}`, { signal }),
  create: (uid: string, operation: Record<string, unknown>, signal?: AbortSignal) => request<RouterCommand>(uid,
    ['state', 'jobs', 'audit', 'backups'].includes(String(operation.action)) ? 'queries' : 'commands',
    { method: 'POST', signal, body: JSON.stringify({ requestId: crypto.randomUUID(), request: operation }) }),
  async run(uid: string, operation: Record<string, unknown>, signal: AbortSignal): Promise<Record<string, unknown>> {
    let command = await this.create(uid, operation, signal);
    const deadline = Date.now() + 120_000;
    while (['QUEUED', 'PENDING', 'RUNNING'].includes(command.status)) {
      if (Date.now() > deadline) throw new Error(`응답 대기 시간이 지났습니다. 작업 이력에서 확인하세요. (${command.id})`);
      await new Promise<void>((resolve, reject) => {
        if (signal.aborted) { reject(new DOMException('Aborted', 'AbortError')); return; }
        const abort = () => { clearTimeout(timer); reject(new DOMException('Aborted', 'AbortError')); };
        const timer = window.setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, 1500);
        signal.addEventListener('abort', abort, { once: true });
      });
      command = await this.get(uid, command.id, signal);
    }
    if (command.status !== 'COMPLETED') throw new Error(command.errorMessage ?? `작업 ${command.status}`);
    return JSON.parse(command.resultJson ?? '{}') as Record<string, unknown>;
  }
};
