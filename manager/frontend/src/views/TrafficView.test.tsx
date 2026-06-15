import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TrafficView } from './TrafficView';

describe('TrafficView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders collected interface traffic from the manager API', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => [
        {
          assetUid: 'nas',
          interfaceName: 'enp2s0',
          inBps: 4621.02,
          outBps: 11663.97,
          utilizationPct: 0,
          errors: 0,
          discards: 0,
          status: 'up'
        }
      ]
    }));
    vi.stubGlobal('fetch', fetchMock);

    render(<TrafficView />);

    expect(await screen.findByText('nas')).toBeInTheDocument();
    expect(screen.getByText('enp2s0')).toBeInTheDocument();
    expect(screen.getAllByText('4.62 Kbps').length).toBeGreaterThan(0);
    expect(screen.getAllByText('11.66 Kbps').length).toBeGreaterThan(0);
    expect(screen.getByText('UP')).toBeInTheDocument();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/traffic/interfaces?range=1h',
        expect.objectContaining({ credentials: 'include' })
      );
    });
  });

  it('reloads traffic when the range changes', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => []
    }));
    vi.stubGlobal('fetch', fetchMock);

    render(<TrafficView />);
    fireEvent.change(await screen.findByLabelText('조회 범위'), { target: { value: '6h' } });

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/traffic/interfaces?range=6h',
        expect.objectContaining({ credentials: 'include' })
      );
    });
  });

  it('shows an error notice and empty state when the API fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: false,
      status: 500,
      statusText: 'Error',
      json: async () => ({})
    })));

    render(<TrafficView />);

    expect(await screen.findByText('인터페이스 트래픽 정보를 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.getByText('표시할 인터페이스 트래픽 없음')).toBeInTheDocument();
  });
});
