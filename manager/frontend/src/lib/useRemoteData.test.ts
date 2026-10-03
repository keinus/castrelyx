import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useRemoteData } from './useRemoteData';

describe('useRemoteData', () => {
  it('does not replace a newer filter response with a late old response', async () => {
    let resolveOld!: (value: string) => void;
    const oldRequest = () => new Promise<string>((resolve) => { resolveOld = resolve; });
    const newRequest = vi.fn(async () => 'new-filter');
    const { result, rerender } = renderHook(({ fetcher }) => useRemoteData(fetcher, '조회 실패'), { initialProps: { fetcher: oldRequest } });
    rerender({ fetcher: newRequest });
    await waitFor(() => expect(result.current.data).toBe('new-filter'));
    await act(async () => resolveOld('old-filter'));
    expect(result.current.data).toBe('new-filter');
    expect(result.current.loading).toBe(false);
  });

  it('keeps the last successful data and reports a failed refresh', async () => {
    const fetcher = vi.fn<() => Promise<string>>().mockResolvedValueOnce('last-success').mockRejectedValueOnce(new Error('503'));
    const { result } = renderHook(() => useRemoteData(fetcher, '조회 실패'));
    await waitFor(() => expect(result.current.data).toBe('last-success'));
    await act(() => result.current.reload());
    expect(result.current.data).toBe('last-success');
    expect(result.current.error).toBe('조회 실패 503');
    expect(result.current.loading).toBe(false);
  });
});
