import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';

test('renders overview, traffic, agent, and snmp dashboards', async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-06-11T13:35:00Z'));
  await mockApi(page, 'OPERATOR');

  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Network', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Action Rail Command Center' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '전체 장비 리소스 상태' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '전체 장비 리소스 매트릭스' })).toBeVisible();
  await expect(page.locator('.ar-inspector')).toBeVisible();
  await expect(page.getByRole('heading', { name: '이벤트 스트림' })).toBeVisible();

  const resourceTable = page.getByRole('table');
  const nasRow = resourceTable.getByRole('row', { name: 'nas 리소스 상태' });
  await expect(nasRow).toBeVisible();
  await expect(nasRow.getByLabel('nas CPU 91.2 퍼센트')).toBeVisible();
  await expect(nasRow.getByLabel('nas RAM 67.8 퍼센트')).toBeVisible();
  await expect(nasRow.getByLabel('nas Disk I/O 읽기 1.00 MB/s, 쓰기 2.00 MB/s')).toBeVisible();
  await expect(nasRow.getByLabel('nas Network I/O 수신 4.62 Kbps, 송신 11.66 Kbps')).toBeVisible();
  const staleRow = resourceTable.getByRole('row', { name: 'x86host 리소스 상태' });
  await expect(staleRow.getByText('수집 지연')).toBeVisible();
  const idleRow = resourceTable.getByRole('row', { name: 'idle-node 리소스 상태' });
  await expect(idleRow.getByLabel('idle-node CPU 0.0 퍼센트')).toBeVisible();
  await expect(idleRow.getByLabel('idle-node Disk I/O 읽기 0 B/s, 쓰기 0 B/s')).toBeVisible();
  const missingRow = resourceTable.getByRole('row', { name: 'inventory-only 리소스 상태' });
  await expect(missingRow.getByLabel('inventory-only CPU 미수집')).toBeVisible();
  await expect(missingRow.getByLabel('inventory-only Network I/O 미수집')).toBeVisible();

  await page.getByRole('button', { name: 'CPU 기준 내림차순 정렬' }).click();
  await expect(resourceTable.locator('tbody .ar-resource-asset-button strong')).toHaveText([
    'nas',
    'edge-router',
    'x86host',
    'idle-node',
    'inventory-only'
  ]);
  const search = page.getByPlaceholder('검색 (자산, IP, 신호)');
  await search.fill('inventory-only');
  await expect(resourceTable.getByRole('row', { name: 'inventory-only 리소스 상태' })).toBeVisible();
  await expect(resourceTable.getByRole('row', { name: 'nas 리소스 상태' })).toHaveCount(0);
  await search.fill('');
  await expect(resourceTable.getByRole('row', { name: 'nas 리소스 상태' })).toBeVisible();

  await page.getByRole('button', { name: 'Network', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Traffic', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Interface flows' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Traffic exceed' })).toBeVisible();
  const flowTable = page.locator('.traffic-flow-table');
  await expect(flowTable).toContainText('nas');
  await expect(flowTable).toContainText('enp2s0');
  await expect(flowTable).toContainText('11.66 Kbps');
  await expect(flowTable).toContainText('1.20 Mbps');

  await page.getByRole('button', { name: 'Collection', exact: true }).click();
  await expect(page.getByText('0.0.0.0:22')).toBeVisible();
  await expect(page.getByText('ssh.service')).toBeVisible();

  await page.getByRole('button', { name: 'SNMP' }).click();
  await expect(page.getByText('Poll health')).toBeVisible();
});

test('overview dashboard stacks cleanly on a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await page.clock.setFixedTime(new Date('2026-06-11T13:35:00Z'));
  await mockApi(page, 'OPERATOR');

  await page.goto('/');

  await expect(page.locator('.ar-mobile-header strong')).toHaveText('Action Rail Command Center');
  await expect(page.getByRole('button', { name: '메뉴 열기' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '전체 장비 리소스 상태' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '전체 장비 리소스 매트릭스' })).toBeVisible();

  const idleCard = page.getByRole('listitem', { name: 'idle-node 리소스 상태' });
  await expect(idleCard).toBeVisible();
  for (const label of ['CPU', 'RAM', 'Disk I/O', 'Network I/O']) {
    await expect(idleCard.getByRole('heading', { level: 4, name: label })).toBeVisible();
  }
  await expect(idleCard.getByLabel('idle-node CPU 0.0 퍼센트')).toBeVisible();
  await expect(idleCard.getByLabel('idle-node RAM 0.0 퍼센트')).toBeVisible();
  await expect(idleCard.getByLabel('idle-node Disk I/O 읽기 0 B/s, 쓰기 0 B/s')).toBeVisible();
  await expect(idleCard.getByLabel('idle-node Network I/O 수신 0 bps, 송신 0 bps')).toBeVisible();

  const staleCard = page.getByRole('listitem', { name: 'x86host 리소스 상태' });
  await expect(staleCard.getByText('수집 지연')).toBeVisible();
  const missingCard = page.getByRole('listitem', { name: 'inventory-only 리소스 상태' });
  await expect(missingCard.getByLabel('inventory-only CPU 미수집')).toBeVisible();
  await expect(missingCard.getByLabel('inventory-only RAM 미수집')).toBeVisible();
  await expect(missingCard.getByLabel('inventory-only Disk I/O 미수집')).toBeVisible();
  await expect(missingCard.getByLabel('inventory-only Network I/O 미수집')).toBeVisible();

  const widths = await page.evaluate(() => ({
    client: document.documentElement.clientWidth,
    scroll: document.documentElement.scrollWidth
  }));
  expect(widths.scroll).toBe(widths.client);
});
