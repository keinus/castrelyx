import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { AssetLogsPanel } from './AssetsView';

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

it('loads router logs, queries filters and pages on the server, and exposes raw evidence', async () => {
  const fetch = vi.fn(async (input: string) => {
    const url = new URL(input, 'http://localhost');
    const family = url.searchParams.get('family');
    return { ok: true, json: async () => ({
      items: [{ sourceType: 'SECURITY_ROUTER', assetUid: 'router-1', sourceId: 'ssw-1', eventId: 'ev-1',
        eventType: family === 'zeek' ? 'zeek.notice' : 'job.committed', message: 'Router event',
        raw: { hash: 'original-hash', 'id.orig_h': '10.0.0.1' } }], hasMore: true,
      nextOffset: 120, storageAvailable: true, status: {},
      receiver: { available: true, configured: true, pendingRecords: 2, quarantinedRecords: 1 }
    }) };
  });
  vi.stubGlobal('fetch', fetch);
  render(<AssetLogsPanel assetUid="router-1" range="1h" active />);
  expect(await screen.findByText('Router event')).toBeInTheDocument();
  expect(screen.getByText('Security Router')).toBeInTheDocument();
  expect(screen.getByText(/저장 대기 2건/)).toBeInTheDocument();
  fireEvent.click(screen.getByText('원본·상세'));
  expect(screen.getByText(/original-hash/)).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('로그 종류'), { target: { value: 'zeek' } });
  expect(await screen.findByText('zeek.notice')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '다음 로그' }));
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('offset=120'))).toBe(true));
  fireEvent.change(screen.getByLabelText('자산 로그 검색'), { target: { value: 'original-hash' } });
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('query=original-hash&offset=0'))).toBe(true));
  expect(await screen.findByText('Router event')).toBeInTheDocument();
});

it('shows storage failure rather than reporting an empty successful collection', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('storage offline'); }));
  render(<AssetLogsPanel assetUid="router-1" range="1h" active />);
  expect(await screen.findByText(/로그 저장소에 연결할 수 없습니다/)).toBeInTheDocument();
  expect(screen.queryByText('해당 조건의 자산 로그가 없습니다.')).not.toBeInTheDocument();
});
