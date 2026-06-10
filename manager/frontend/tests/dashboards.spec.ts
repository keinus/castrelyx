import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('renders overview, traffic, agent, and snmp dashboards', async ({ page }) => {
  await mockApi(page, 'OPERATOR');

  await page.goto('/');
  await expect(page.getByText('3')).toBeVisible();

  await page.getByRole('button', { name: 'Traffic' }).click();
  await expect(page.getByText('1.20 Mbps')).toBeVisible();

  await page.getByRole('button', { name: 'Agent' }).click();
  await expect(page.getByText('Heartbeat')).toBeVisible();

  await page.getByRole('button', { name: 'SNMP' }).click();
  await expect(page.getByText('Poll health')).toBeVisible();
});
