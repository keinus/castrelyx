import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TrafficView } from './TrafficView';

describe('TrafficView', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('renders asset traffic summaries, interface flows, and exceed rows', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => trafficRows()
    }));
    vi.stubGlobal('fetch', fetchMock);

    render(<TrafficView />);

    expect(await screen.findByRole('heading', { name: 'Traffic' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Assets' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Interface flows' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Traffic exceed' })).toBeInTheDocument();
    expect(screen.getAllByText('edge-router').length).toBeGreaterThan(0);
    expect(screen.getAllByText('wan0').length).toBeGreaterThan(0);
    expect(screen.getAllByText('nas').length).toBeGreaterThan(0);
    expect(screen.getAllByText('enp2s0').length).toBeGreaterThan(0);
    expect(screen.getAllByText('15.00 Mbps').length).toBeGreaterThan(0);
    expect(screen.getByText('10.00 Mbps threshold')).toBeInTheDocument();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/traffic/interfaces?range=1h',
        expect.objectContaining({ credentials: 'include' })
      );
    });
  });

  it('filters flows by asset and query', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => trafficRows()
    })));

    render(<TrafficView />);
    await screen.findAllByText('edge-router');

    fireEvent.change(screen.getByLabelText('Asset'), { target: { value: 'nas' } });
    expect(screen.queryByText('wan0')).not.toBeInTheDocument();
    expect(screen.getAllByText('enp2s0').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('Filter asset or interface'), { target: { value: 'enp' } });
    expect(screen.getAllByText('enp2s0').length).toBeGreaterThan(0);
  });

  it('updates exceed rows when the threshold changes', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => trafficRows()
    })));

    render(<TrafficView />);
    await screen.findAllByText('wan0');

    fireEvent.change(screen.getByLabelText('Exceed threshold Mbps'), { target: { value: '30' } });

    const exceedPanel = screen.getByRole('heading', { name: 'Traffic exceed' }).closest('section');
    expect(exceedPanel).not.toBeNull();
    expect(within(exceedPanel as HTMLElement).getByText('No traffic exceed.')).toBeInTheDocument();
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
    fireEvent.change(await screen.findByLabelText('Range'), { target: { value: '6h' } });

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

    expect(await screen.findByText('Unable to load traffic data.')).toBeInTheDocument();
    expect(screen.getByText('No interface traffic.')).toBeInTheDocument();
  });
});

function trafficRows() {
  return [
    {
      assetUid: 'edge-router',
      interfaceName: 'wan0',
      inBps: 9_000_000,
      outBps: 6_000_000,
      utilizationPct: 72.4,
      errors: 0,
      discards: 0,
      status: 'up'
    },
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
  ];
}
