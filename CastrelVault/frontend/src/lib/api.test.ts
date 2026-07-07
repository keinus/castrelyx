import { describe, expect, it, vi } from 'vitest';
import { request } from './api';

describe('request', () => {
  it('attaches csrf token to unsafe same-origin requests', async () => {
    Object.defineProperty(document, 'cookie', {
      value: 'CASTRELVAULT_CSRF=csrf-token',
      configurable: true
    });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ ok: true })
    });
    vi.stubGlobal('fetch', fetchMock);

    await request('/api/secrets', { method: 'POST', json: { path: '/x' } });

    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Headers).get('X-CSRF-Token')).toBe('csrf-token');
    expect(init.credentials).toBe('include');
  });
});
