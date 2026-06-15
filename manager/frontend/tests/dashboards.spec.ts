import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('renders overview, traffic, agent, and snmp dashboards', async ({ page }) => {
  await mockApi(page, 'OPERATOR');

  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'NMS 보안 통합 관제' })).toBeVisible();
  await expect(page.locator('.command-kpi-card').filter({ hasText: '전체 자산' })).toContainText('3');
  await expect(page.locator('.command-kpi-card').filter({ hasText: 'Critical' })).toContainText('1');
  await expect(page.locator('.command-kpi-card').filter({ hasText: 'SNMP 실패' })).toContainText('1');
  await expect(page.locator('.command-panel').filter({ hasText: 'Network Health' })).toContainText('enp2s0');
  await expect(page.locator('.command-panel').filter({ hasText: 'Security Posture' })).toContainText('노출 포트');
  await expect(page.locator('.command-panel').filter({ hasText: 'Response Queue' })).toContainText('CPU threshold exceeded');
  await expect(page.getByText('11.66 Kbps')).toBeVisible();
  await expect(page.getByText('CPU threshold exceeded')).toBeVisible();

  await page.getByRole('button', { name: 'Traffic' }).click();
  await expect(page.getByRole('heading', { name: '인터페이스 트래픽' })).toBeVisible();
  await expect(page.getByText('nas')).toBeVisible();
  await expect(page.getByText('enp2s0')).toBeVisible();
  await expect(page.getByRole('cell', { name: '11.66 Kbps' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '1.20 Mbps' })).toBeVisible();

  await page.getByRole('button', { name: 'Agent' }).click();
  await expect(page.getByText('보안 관제')).toBeVisible();
  await expect(page.getByText('공격면')).toBeVisible();
  await expect(page.getByText('0.0.0.0:22')).toBeVisible();
  await expect(page.getByText('SSH login failed for alice')).toBeVisible();

  await page.getByRole('button', { name: 'SNMP' }).click();
  await expect(page.getByText('Poll health')).toBeVisible();
});

test('overview dashboard stacks cleanly on a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await mockApi(page, 'OPERATOR');

  await page.goto('/');

  await expect(page.getByRole('heading', { name: 'NMS 보안 통합 관제' })).toBeVisible();
  await expect(page.locator('.command-panel').filter({ hasText: 'Network Health' })).toBeVisible();
  await expect(page.locator('.command-panel').filter({ hasText: 'Response Queue' })).toBeVisible();
  await expect(page.locator('.command-panel').filter({ hasText: 'Security Posture' })).toBeVisible();
  await expect(page.getByText('enp2s0')).toBeVisible();
});
