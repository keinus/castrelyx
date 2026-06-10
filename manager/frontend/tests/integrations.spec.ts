import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('CastrelSign and LogParser have dedicated navigation panels', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await expect(page.getByRole('button', { name: '연동' })).toHaveCount(0);

  await page.getByRole('button', { name: 'CastrelSign' }).click();
  await expect(page.getByRole('heading', { name: 'CastrelSign' })).toBeVisible();
  await expect(
    page.locator('.data-panel').filter({ has: page.getByRole('heading', { name: 'Agents' }) })
      .getByText('edge-agent', { exact: true })
  ).toBeVisible();

  await page.getByRole('button', { name: 'LogParser' }).click();
  await expect(page.getByRole('heading', { name: 'LogParser' })).toBeVisible();
  await expect(page.getByText('RUNNING')).toBeVisible();

  await page.getByRole('button', { name: '알림' }).click();
  await expect(page.getByText('CPU threshold exceeded')).toBeVisible();
});

test('admin can issue a CastrelSign enrollment token from the panel', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: 'CastrelSign' }).click();
  await page.getByRole('button', { name: '토큰 갱신' }).click();

  await expect(page.getByText('enroll-token-123')).toBeVisible();
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
