import { expect, test } from '@playwright/test';
import { mockApi } from './fixtures';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
  test(`router log filtering, evidence, paging and failure at ${viewport.width}px`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    await mockApi(page, 'ADMIN');
    let unavailable = false;
    await page.route('**/api/assets/by-uid/nas/logs?**', async route => {
      if (unavailable) { await route.fulfill({ status: 503, contentType: 'application/json', body: '{"message":"storage unavailable"}' }); return; }
      const params = new URL(route.request().url()).searchParams;
      const empty = Boolean(params.get('query'));
      const kind = params.get('family') === 'zeek' ? 'zeek.notice' : 'job.committed';
      await route.fulfill({ json: {
        items: empty ? [] : [{ sourceType: 'SECURITY_ROUTER', assetUid: 'nas', sourceId: 'ssw-01', eventId: 'event-123',
          eventType: kind, severity: 'WARNING', message: `Router ${kind} 기록`, observedAt: '2026-10-03T00:00:00Z',
          sourceName: 'security-router', raw: { hash: 'original-hash', data: { 'id.orig_h': '10.0.0.2', uid: 'C1' } } }],
        hasMore: !empty, nextOffset: 120, storageAvailable: true,
        status: { lastReceivedAt: '2026-10-03T00:00:01Z', lastStoredAt: '2026-10-03T00:00:02Z' },
        receiver: { available: true, configured: true, pendingRecords: 2, quarantinedRecords: 1 }
      }});
    });
    await page.goto('/');
    if (viewport.width < 768) await page.getByRole('button', { name: '모바일 메뉴 열기' }).click();
    await page.getByRole('button', { name: 'Assets', exact: true }).click();
    await page.getByRole('button', { name: /^nas/ }).click();
    await page.getByRole('tab', { name: '로그' }).click();
    await expect(page.getByText('Security Router', { exact: true })).toBeVisible();
    await page.getByLabel('로그 종류').selectOption('zeek');
    await expect(page.getByText('Router zeek.notice 기록')).toBeVisible();
    await page.getByText('원본·상세').click();
    await expect(page.getByText(/original-hash/)).toBeVisible();
    await expect(page.getByText(/저장 대기 2건/)).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(viewport.width);
    if (process.env.ROUTER_QA_SCREENSHOT_DIR) {
      await mkdir(process.env.ROUTER_QA_SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: path.join(process.env.ROUTER_QA_SCREENSHOT_DIR, `router-logs-${viewport.width}.png`), fullPage: true });
    }
    const paging = page.waitForRequest(request => request.url().includes('/logs?') && request.url().includes('offset=120'));
    await page.getByRole('button', { name: '다음 로그' }).click(); await paging;
    await page.getByLabel('자산 로그 검색').fill('no-matching-event');
    await expect(page.getByText('해당 조건의 자산 로그가 없습니다.')).toBeVisible();
    unavailable = true;
    await page.getByRole('tabpanel', { name: '로그', exact: true }).getByRole('button', { name: '새로고침', exact: true }).click();
    await expect(page.getByText(/로그 저장소에 연결할 수 없습니다/)).toBeVisible();
    await expect(page.getByText('해당 조건의 자산 로그가 없습니다.')).toHaveCount(0);
    expect(errors).toEqual([]);
    await expect(page.locator('vite-error-overlay')).toHaveCount(0);
    expect(page.url()).toContain('127.0.0.1');
    expect(await page.title()).not.toEqual('');
  });
}
