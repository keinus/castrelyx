import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('renders overview, traffic, agent, and snmp dashboards', async ({ page }) => {
  await mockApi(page, 'OPERATOR');

  await page.goto('/');
  await expect(page.getByText('3')).toBeVisible();

  await page.getByRole('button', { name: 'Traffic' }).click();
  await expect(page.getByText('1.20 Mbps')).toBeVisible();

  await page.getByRole('button', { name: 'Agent' }).click();
  await expect(page.getByText('보안 관제')).toBeVisible();
  await expect(page.getByText('공격면')).toBeVisible();
  await expect(page.getByText('0.0.0.0:22')).toBeVisible();
  await expect(page.getByText('SSH login failed for alice')).toBeVisible();

  await page.getByRole('button', { name: 'SNMP' }).click();
  await expect(page.getByText('Poll health')).toBeVisible();
});
