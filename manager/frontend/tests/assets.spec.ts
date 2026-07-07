import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('admin can reach asset metric dashboard and see create action', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();

  await expect(page.getByRole('heading', { name: '자산 관리' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '자산 관제 스캔' })).toBeVisible();
  await expect(assetRow(page, 'edge-router')).toBeVisible();
  await expect(assetRow(page, 'nas')).toBeVisible();
  await expect(page.getByRole('heading', { name: 'CPU Usage' })).toHaveCount(0);
  await expect(page.getByText('Disk by mount')).toHaveCount(0);
  await assetRow(page, 'nas').getByRole('button', { name: /nas/ }).click();
  await expect(page.getByRole('heading', { name: 'CPU Usage' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Memory Usage' })).toBeVisible();
  await expect(page.getByText('Disk I/O Top 5')).toHaveCount(0);
  await page.getByRole('tab', { name: '신호' }).click();
  await expect(page.getByRole('heading', { name: 'Open ports' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Process/socket map' })).toBeVisible();
  await expect(page.getByText('Security events')).toHaveCount(0);
  await page.getByRole('tab', { name: '로그' }).click();
  await expect(page.getByRole('heading', { name: '자산 로그' })).toBeVisible();
  await expect(page.getByText('SSH login failed for alice')).toBeVisible();
  await expect(page.getByText('auth.login.failure')).toBeVisible();
  await page.getByRole('tab', { name: '스토리지' }).click();
  await expect(page.getByText('Disk by mount')).toBeVisible();
  await expect(page.getByText('Disk I/O devices')).toBeVisible();
  await expect(page.getByLabel('자산 추가')).toBeVisible();
});

test('admin can create a manual asset from the asset view', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();
  await page.getByLabel('자산 추가').click();
  await page.getByLabel('자산명').fill('branch-fw');
  await page.getByLabel('관리 IP').fill('10.0.0.2');
  await page.getByLabel('위치').fill('Branch Office');
  await page.getByLabel('자산 유형').selectOption('FIREWALL');
  await page.getByRole('button', { name: '저장' }).click();

  await expect(assetRow(page, 'branch-fw')).toBeVisible();
});

test('asset metrics can be queried by host identity and health', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();
  await page.getByLabel('자산 검색').fill('nas');

  await expect(assetRow(page, 'nas')).toBeVisible();
  await expect(assetRow(page, 'edge-router')).toHaveCount(0);

  await page.getByLabel('자산 검색').fill('');
  await page.getByLabel('상태 필터').selectOption('critical');
  await expect(assetRow(page, 'nas')).toBeVisible();
  await expect(assetRow(page, 'edge-router')).toHaveCount(0);
});

test('viewer can inspect assets but does not see mutation actions or settings', async ({ page }) => {
  await mockApi(page, 'VIEWER');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();

  await expect(assetRow(page, 'edge-router')).toBeVisible();
  await expect(page.getByLabel('자산 추가')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '설정' })).toHaveCount(0);
});

function assetRow(page: import('@playwright/test').Page, name: string) {
  return page.locator('.asset-scan-table tbody tr').filter({ hasText: name });
}
