import { expect, test } from '@playwright/test';
import { routeJson } from './fixtures';

test('setup wizard creates the first admin then shows login', async ({ page }) => {
  await page.route('/api/setup/status', routeJson({ required: true }));
  await page.route('/api/setup/admin', routeJson({ username: 'admin', role: 'ADMIN' }, 201));

  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Castrelyx Manager' })).toBeVisible();
  await page.getByLabel('비밀번호').fill('correct-password');
  await page.getByRole('button', { name: '초기 설정 완료' }).click();

  await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
});

test('login flow enters the NMS console', async ({ page }) => {
  await page.route('/api/setup/status', routeJson({ required: false }));
  await page.route('/api/auth/session', routeJson({ authenticated: false }));
  await page.route('/api/auth/login', routeJson({ authenticated: true, user: { username: 'admin', role: 'ADMIN' } }));
  await page.route('/api/auth/logout', routeJson({}, 204));
  await page.route('/api/dashboards/overview', routeJson({
    activeAssets: 1,
    criticalAlerts: 0,
    agentHealth: { healthy: 1, stale: 0 },
    snmpPollHealth: { success: 1, failure: 0 }
  }));
  await page.route('/api/assets', routeJson([]));
  await page.route('/api/alerts', routeJson([]));

  await page.goto('/');
  await page.getByLabel('비밀번호').fill('correct-password');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page.getByRole('heading', { name: 'Castrelyx' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Assets' })).toBeVisible();
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page.getByRole('button', { name: '로그인' })).toBeVisible();
});
