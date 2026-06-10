import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('admin can reach asset list and see create action', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();

  await expect(page.getByText('edge-router')).toBeVisible();
  await expect(page.getByLabel('자산 추가')).toBeVisible();
});

test('admin can create a manual asset from the asset view', async ({ page }) => {
  await mockApi(page, 'ADMIN');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();
  await page.getByLabel('자산 추가').click();
  await page.getByLabel('자산 이름').fill('branch-fw');
  await page.getByLabel('관리 IP').fill('10.0.0.2');
  await page.getByLabel('자산 유형').selectOption('FIREWALL');
  await page.getByRole('button', { name: '저장' }).click();

  await expect(page.getByText('branch-fw')).toBeVisible();
});

test('viewer can inspect assets but does not see mutation actions or settings', async ({ page }) => {
  await mockApi(page, 'VIEWER');

  await page.goto('/');
  await page.getByRole('button', { name: '자산' }).click();

  await expect(page.getByText('edge-router')).toBeVisible();
  await expect(page.getByLabel('자산 추가')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '설정' })).toHaveCount(0);
});
