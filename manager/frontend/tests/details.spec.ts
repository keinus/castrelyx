import { expect, test } from '@playwright/test';
import { mockApi, routeJson } from './fixtures';

test('admin saves integration configuration without replacing an unchanged token', async ({ page }) => {
  await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  const form = page.getByRole('region', { name: 'CastrelSign 연동 설정' });
  await form.getByLabel('서비스 URL').fill('https://sign.example.test');
  await expect(form.getByRole('button', { name: '연결 확인' })).toBeDisabled();
  const save = page.waitForRequest((request) => request.url().endsWith('/api/integrations/castrelsign') && request.method() === 'PUT');
  await form.getByRole('button', { name: '설정 저장' }).click();
  expect((await save).postDataJSON()).toEqual({ baseUrl: 'https://sign.example.test', enabled: true });
  await expect(form.getByRole('status')).toContainText('저장했습니다');
  await expect(form.getByRole('button', { name: '연결 확인' })).toBeEnabled();
  await form.getByRole('button', { name: '연결 확인' }).click();
  await expect(form.getByText('저장된 설정으로 연결 확인을 완료했습니다.')).toBeVisible();
});

test('operator can toggle an alert rule but cannot change integration settings', async ({ page }) => {
  await mockApi(page, 'OPERATOR');
  await page.goto('/');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  await expect(page.getByRole('button', { name: '설정 저장' })).toHaveCount(0);
  await expect(page.getByLabel('서비스 URL').first()).toBeDisabled();
  const request = page.waitForRequest((req) => req.url().endsWith('/api/alert-rules/1') && req.method() === 'PUT');
  const toggle = page.getByRole('switch', { name: 'CPU 사용량 활성화' });
  await toggle.click();
  const body = (await request).postDataJSON();
  expect(body).toMatchObject({ enabled: false, ruleType: 'cpu_threshold', expressionJson: '{}' });
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
});

test('SNMP registration uses the backend units and target state endpoint', async ({ page }) => {
  await mockApi(page, 'OPERATOR');
  await page.goto('/');
  await page.getByRole('button', { name: 'SNMP', exact: true }).click();
  await expect(page.getByText('폴링 결과')).toBeVisible();
  await page.getByRole('button', { name: '대상 등록' }).click();
  await page.getByLabel('대상 이름').fill('branch-router');
  await page.getByLabel('호스트 / IP').fill('10.0.1.1');
  await page.getByLabel('수집 주기 (초)').fill('30');
  const request = page.waitForRequest((req) => req.url().endsWith('/api/snmp/targets') && req.method() === 'POST');
  await page.getByRole('button', { name: '대상 저장' }).click();
  expect((await request).postDataJSON()).toEqual({ name: 'branch-router', host: '10.0.1.1', port: 161, credentialId: null, pollIntervalMs: 30000, enabled: true, oids: ['1.3.6.1.2.1.2.2.1'] });
  const toggle = page.getByRole('switch', { name: 'branch-router 활성화' });
  await expect(toggle).toBeVisible();
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-checked', 'false');
});

test('viewer sees telemetry without SNMP or SSH mutation controls', async ({ page }) => {
  await mockApi(page, 'VIEWER');
  await page.goto('/');
  await page.getByRole('button', { name: 'SNMP', exact: true }).click();
  await expect(page.getByRole('cell', { name: '10.0.0.1:161' })).toBeVisible();
  await expect(page.getByRole('button', { name: '대상 등록' })).toHaveCount(0);
  await expect(page.getByRole('switch')).toHaveCount(0);
  await page.getByRole('button', { name: 'Collection', exact: true }).click();
  await expect(page.getByText('읽기 전용')).toBeVisible();
  await expect(page.getByRole('button', { name: /Open SSH/ })).toHaveCount(0);
});

test('failed incident actions are visible and leave the alert actionable', async ({ page }) => {
  await mockApi(page, 'OPERATOR');
  await page.route('/api/alerts/1/acknowledge', routeJson({ error: 'Service unavailable' }, 503));
  await page.goto('/');
  await page.getByRole('button', { name: 'Incidents', exact: true }).click();
  await page.getByRole('button', { name: '확인', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('알림을 처리하지 못했습니다.');
  await expect(page.getByRole('cell', { name: 'ACTIVE', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '확인', exact: true })).toBeEnabled();
});

test('Hunt sends the selected asset to the server before applying its result limit', async ({ page }) => {
  await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: 'Hunt', exact: true }).click();
  await expect(page.getByText('SSH login failed for alice')).toBeVisible();
  const request = page.waitForRequest((req) => req.url().includes('/api/agent/logs?') && req.url().includes('assetUid=edge-router'));
  await page.getByLabel('Log asset').selectOption('edge-router');
  expect(new URL((await request).url()).searchParams.get('limit')).toBe('300');
  await expect(page.getByText('수집된 agent log 없음')).toBeVisible();
});

test('SNMP failures are not presented as zero healthy targets', async ({ page }) => {
  await mockApi(page);
  await page.route('/api/snmp/targets', routeJson({ error: 'Unavailable' }, 503));
  await page.goto('/');
  await page.getByRole('button', { name: 'SNMP', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('SNMP 대상을 불러오지 못했습니다.');
  await expect(page.locator('.metric-card').filter({ hasText: '등록 대상' }).locator('strong')).toHaveText('—');
  await expect(page.getByText('등록된 SNMP 대상이 없습니다.')).toHaveCount(0);
});

test('LogParser fallback renders actual pipeline fields and a failed status request', async ({ page }) => {
  await mockApi(page);
  await page.route('/api/integrations/logparser/deep-links', routeJson([]));
  await page.goto('/');
  await page.getByRole('button', { name: 'LogParser', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Pipeline status' })).toBeVisible();
  await expect(page.getByText('RUNNING', { exact: true })).toBeVisible();
  await expect(page.getByText('248.5', { exact: true })).toBeVisible();
  await page.route('/api/integrations/logparser/status', routeJson({ error: 'Unavailable' }, 503));
  await page.getByRole('button', { name: 'LogParser 새로고침' }).click();
  await expect(page.getByRole('alert')).toContainText('마지막으로 조회한 데이터');
});

test('detail workspaces fit a mobile viewport and keep navigation usable', async ({ page }) => {
  await mockApi(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  for (const name of ['Settings', 'SNMP', 'Network', 'Hunt', 'CastrelSign', 'Assets', 'Incidents', 'Collection']) {
    await page.getByRole('button', { name: '메뉴 열기' }).click();
    await page.getByRole('navigation', { name: '사이드바 메뉴' }).getByRole('button', { name, exact: true }).click();
    await expect(page.locator('.command-view-header h2')).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }
});
