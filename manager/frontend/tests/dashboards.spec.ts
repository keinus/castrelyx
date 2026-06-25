import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('renders overview, traffic, agent, and snmp dashboards', async ({ page }) => {
  await mockApi(page, 'OPERATOR');

  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Traffic' })).toBeVisible();
  await expect(page.getByText('CPU threshold exceeded')).toBeVisible();
  await expect(page.getByText('11.66 Kbps', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Traffic' }).click();
  await expect(page.getByRole('heading', { name: 'Traffic', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Interface flows' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Traffic exceed' })).toBeVisible();
  const flowTable = page.locator('.traffic-flow-table');
  await expect(flowTable).toContainText('nas');
  await expect(flowTable).toContainText('enp2s0');
  await expect(flowTable).toContainText('11.66 Kbps');
  await expect(flowTable).toContainText('1.20 Mbps');

  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await expect(page.getByText('0.0.0.0:22')).toBeVisible();
  await expect(page.getByText('ssh.service')).toBeVisible();

  await page.getByRole('button', { name: 'SNMP' }).click();
  await expect(page.getByText('Poll health')).toBeVisible();
});

test('overview dashboard stacks cleanly on a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await mockApi(page, 'OPERATOR');

  await page.goto('/');

  await expect(page.getByRole('button', { name: 'Traffic' })).toBeVisible();
  await expect(page.locator('.overview-panel').filter({ hasText: '상위 트래픽' })).toBeVisible();
  await expect(page.locator('.overview-panel').filter({ hasText: '대응 큐' })).toBeVisible();
  await expect(page.getByText('enp2s0')).toBeVisible();
});
