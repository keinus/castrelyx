import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('integration and alert surfaces are reachable', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: '연동' }).click();
  await expect(page.getByText('CastrelSign')).toBeVisible();
  await expect(page.getByText('logparser')).toBeVisible();

  await page.getByRole('button', { name: '알림' }).click();
  await expect(page.getByText('CPU threshold exceeded')).toBeVisible();
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
