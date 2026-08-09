import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ConfirmEmailChangePage from './ConfirmEmailChangePage';
import * as authApi from './authApi';

vi.mock('./authApi', () => ({
  confirmEmailChange: vi.fn(),
}));

const mockedConfirm = vi.mocked(authApi.confirmEmailChange);

function renderAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <ConfirmEmailChangePage />
    </MemoryRouter>,
  );
}

describe('ConfirmEmailChangePage', () => {
  beforeEach(() => {
    mockedConfirm.mockReset();
  });

  it('confirms the email change for a valid token', async () => {
    mockedConfirm.mockResolvedValue('changed');

    renderAt('/confirm-email-change?token=good-token');

    expect(
      await screen.findByRole('heading', { name: /email updated/i }),
    ).toBeInTheDocument();
    expect(mockedConfirm).toHaveBeenCalledWith('good-token');
    expect(screen.getByRole('link', { name: /go to log in/i })).toHaveAttribute(
      'href',
      '/login',
    );
  });

  it('offers to start over from account settings when the link is rejected', async () => {
    mockedConfirm.mockResolvedValue('rejected');

    renderAt('/confirm-email-change?token=stale-token');

    expect(
      await screen.findByRole('heading', { name: /no longer valid/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /go to account settings/i }),
    ).toHaveAttribute('href', '/account');
  });

  it('shows a generic error when the request fails', async () => {
    mockedConfirm.mockResolvedValue('error');

    renderAt('/confirm-email-change?token=good-token');

    expect(
      await screen.findByText(/something went wrong confirming/i),
    ).toBeInTheDocument();
  });

  it('spends a single-use token once under StrictMode and shows the result', async () => {
    mockedConfirm
      .mockResolvedValueOnce('changed')
      .mockResolvedValue('rejected');

    render(
      <StrictMode>
        <MemoryRouter
          initialEntries={['/confirm-email-change?token=good-token']}
        >
          <ConfirmEmailChangePage />
        </MemoryRouter>
      </StrictMode>,
    );

    expect(
      await screen.findByRole('heading', { name: /email updated/i }),
    ).toBeInTheDocument();
    expect(mockedConfirm).toHaveBeenCalledTimes(1);
  });

  it('treats a missing token as a rejected link', async () => {
    renderAt('/confirm-email-change');

    expect(
      await screen.findByRole('heading', { name: /no longer valid/i }),
    ).toBeInTheDocument();
    expect(mockedConfirm).not.toHaveBeenCalled();
  });
});
