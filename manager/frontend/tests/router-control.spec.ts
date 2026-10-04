import { expect, test, type Page } from '@playwright/test';
import { mockApi } from './fixtures';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

async function setup(page: Page, role: 'ADMIN' | 'VIEWER' = 'ADMIN') {
  await mockApi(page, role);
  let revision = 4;
  let applied = false;
  const operations: Record<string, unknown>[] = [];
  const commands: Record<string, unknown>[] = [];
  await page.route('**/api/assets/edge-router/router-control/**', async route => {
    const req = route.request(); const url = new URL(req.url());
    if (url.pathname.endsWith('/binding')) { await route.fulfill({ json: { enabled: true, agentId: 'agent-25', routerId: 'router-25' } }); return; }
    if (req.method() === 'GET') { await route.fulfill({ json: commands }); return; }
    const body = req.postDataJSON() as { requestId: string; request: Record<string, unknown> };
    operations.push(body.request);
    const result: Record<string, unknown> = body.request.action === 'state' ? {
      appliedRevision: revision, desiredRevision: revision, ready: true, mode: 'automatic',
      config: { device: { id: 'router-25' }, ovs: { ports: [{ name: 'eth2', adminState: applied ? 'down' : 'up' }] } },
      snapshot: { state: 'healthy', observedAt: new Date().toISOString(), ports: [{ name: 'eth2', role: 'access', admin: !applied, oper: !applied }], services: [{ name: 'switch-zeek', state: 'active' }] },
      capabilities: [{ id: 'port.admin', state: 'supported' }], restartServices: ['switch-zeek']
    } : body.request.action === 'validate' ? { valid: true, appliedRevision: revision, diff: ['eth2: up → down'] }
      : body.request.action === 'apply' ? { id: 'router-job-1', status: 'accepted' }
        : body.request.action === 'jobs' ? { items: [{ id: 'router-job-1', status: 'committed', request: { kind: 'port.admin' } }] }
          : { items: [] };
    if (body.request.action === 'apply') { applied = true; revision++; }
    const command = { id: body.requestId, action: body.request.action, status: 'COMPLETED', resultJson: JSON.stringify(result), createdBy: role, createdAt: new Date().toISOString() };
    commands.unshift(command); await route.fulfill({ status: 202, json: command });
  });
  return operations;
}

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`router validates before applying and displays real job status at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
    const operations = await setup(page);
    await page.goto('/');
    if (viewport.width < 768) await page.getByRole('button', { name: '모바일 메뉴 열기' }).click();
    await page.getByRole('button', { name: 'Assets', exact: true }).click();
    await page.getByRole('button', { name: /^edge-router/ }).click();
    await page.getByRole('tab', { name: 'Router 제어', exact: true }).click();
    await expect(page.getByText('설정 버전 4', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: '변경 검증', exact: true }).click();
    await expect(page.getByText('eth2 비활성화 — 적용 전 확인')).toBeVisible();
    expect(operations.some(item => item.action === 'apply')).toBe(false);
    await page.getByRole('button', { name: '확인 후 적용' }).click();
    await expect(page.getByText('설정 버전 5', { exact: false })).toBeVisible();
    expect(operations.find(item => item.action === 'apply')).toMatchObject({ expectedRevision: 4, kind: 'port.admin', parameters: { port: 'eth2', state: 'down' } });
    await page.getByRole('tab', { name: '작업 이력', exact: true }).click();
    await expect(page.getByRole('cell', { name: 'committed', exact: true })).toBeVisible();
    await page.getByRole('tab', { name: '설정·정책', exact: true }).click();
    await page.getByLabel(/설정 JSON/).fill('{invalid json');
    await page.getByRole('button', { name: '설정 검증·차이 확인' }).click();
    await expect(page.getByRole('alert').filter({ hasText: '요청을 처리하지 못했습니다' })).toBeVisible();
    await expect(page.getByRole('button', { name: '확인 후 적용' })).toHaveCount(0);
    await page.getByRole('button', { name: '상태 새로고침' }).click();
    await expect(page.getByLabel(/설정 JSON/)).toHaveValue(/router-25/);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
    expect(page.url()).toContain('127.0.0.1'); expect(await page.title()).not.toEqual('');
    await expect(page.locator('vite-error-overlay')).toHaveCount(0); expect(errors).toEqual([]);
    if (process.env.ROUTER_QA_SCREENSHOT_DIR) {
      await mkdir(process.env.ROUTER_QA_SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: path.join(process.env.ROUTER_QA_SCREENSHOT_DIR, `router-control-${viewport.width}.png`), fullPage: true });
    }
  });
}
test('viewer sees router state without mutation controls', async ({ page }) => {
  const operations = await setup(page, 'VIEWER'); await page.goto('/');
  await page.getByRole('button', { name: 'Assets', exact: true }).click();
  await page.getByRole('button', { name: /^edge-router/ }).click();
  await page.getByRole('tab', { name: 'Router 제어', exact: true }).click();
  await expect(page.getByText('설정 버전 4', { exact: false })).toBeVisible();
  await expect(page.getByRole('button', { name: '변경 검증', exact: true })).toHaveCount(0);
  await page.getByRole('tab', { name: '설정·정책', exact: true }).click();
  await expect(page.getByLabel(/설정 JSON/)).toHaveAttribute('readonly', '');
  await expect(page.getByRole('button', { name: '설정 검증·차이 확인' })).toHaveCount(0);
  expect(operations.every(item => item.action === 'state')).toBe(true);
});
