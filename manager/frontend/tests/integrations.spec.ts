import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('CastrelSign and LogParser have dedicated navigation panels', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await expect(page.getByRole('button', { name: '연동' })).toHaveCount(0);

  await page.getByRole('button', { name: 'CastrelSign' }).click();
  await expect(page.getByRole('heading', { name: 'CastrelSign' })).toBeVisible();
  await expect(
    page.locator('.data-panel').filter({ has: page.getByRole('heading', { name: 'Agent lifecycle' }) })
      .getByText('edge-agent', { exact: true })
  ).toBeVisible();

  await page.context().route('http://logparser:8765/', (route) => route.fulfill({
    status: 200,
    contentType: 'text/html',
    body: '<!doctype html><title>LogParser</title><main>RUNNING</main>'
  }));
  const popupPromise = page.waitForEvent('popup');
  await page.getByRole('button', { name: 'LogParser' }).click();
  const popup = await popupPromise;
  await expect.poll(() => popup.url()).toContain('http://logparser:8765/');
  await expect(popup.getByText('RUNNING')).toBeVisible();
  await popup.close();

  await page.getByRole('button', { name: '알림' }).click();
  await expect(page.getByText('CPU threshold exceeded')).toBeVisible();
});

test('admin can create a CastrelSign enrollment package from the panel', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: 'CastrelSign' }).click();
  await page.getByRole('button', { name: '새 agent 패키지' }).click();
  await expect(page.getByRole('dialog', { name: '새 agent 패키지' })).toBeVisible();
  await page.getByRole('button', { name: '패키지 생성' }).click();

  await expect(page.getByText('hostname auto enrollment package 다운로드를 시작했습니다.')).toBeVisible();
});

test('operator can acknowledge and resolve alerts', async ({ page }) => {
  await mockApi(page, 'OPERATOR');

  await page.goto('/');
  await page.getByRole('button', { name: '알림' }).click();
  await page.getByRole('button', { name: '확인' }).click();
  await page.getByLabel('status').selectOption('ACKNOWLEDGED');
  await expect(page.getByRole('cell', { name: 'ACKNOWLEDGED' })).toBeVisible();

  await page.getByRole('button', { name: '해결' }).click();
  await page.getByLabel('status').selectOption('RESOLVED');
  await expect(page.getByRole('cell', { name: 'RESOLVED' })).toBeVisible();
});
