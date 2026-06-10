import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import App from './App';

describe('App shell', () => {
  it('renders the NMS console as the first authenticated surface', async () => {
    render(<App bootstrap={{ setupRequired: false, authenticated: true, user: { role: 'ADMIN', username: 'admin' } }} />);

    expect(await screen.findByRole('heading', { name: 'Castrelyx Manager' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '자산' })).toBeInTheDocument();
    expect(screen.getByText('Traffic')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'CastrelSign' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'LogParser' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '연동' })).not.toBeInTheDocument();
  });
});
