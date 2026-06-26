import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import VerifyPage from './VerifyPage';
import * as authApi from './authApi';

vi.mock('./authApi', () => ({
  verify: vi.fn(),
  resendVerification: vi.fn(),
}));

const mockedVerify = vi.mocked(authApi.verify);
const mockedResend = vi.mocked(authApi.resendVerification);

function renderAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <VerifyPage />
    </MemoryRouter>,
  );
}

describe('VerifyPage', () => {
  beforeEach(() => {
    mockedVerify.mockReset();
    mockedResend.mockReset();
  });

  it('confirms a verified account for a valid token', async () => {
    mockedVerify.mockResolvedValue('verified');

    renderAt('/verify?token=good-token');

    expect(
      await screen.findByRole('heading', { name: /email verified/i }),
    ).toBeInTheDocument();
    expect(mockedVerify).toHaveBeenCalledWith('good-token');
  });

  it('offers a resend when the link is rejected (expired or used)', async () => {
    mockedVerify.mockResolvedValue('rejected');
    mockedResend.mockResolvedValue();
    const user = userEvent.setup();

    renderAt('/verify?token=stale-token');

    expect(
      await screen.findByRole('heading', { name: /no longer valid/i }),
    ).toBeInTheDocument();

    await user.type(screen.getByLabelText(/email/i), 'newcomer@example.com');
    await user.click(screen.getByRole('button', { name: /resend link/i }));

    expect(mockedResend).toHaveBeenCalledWith('newcomer@example.com');
    expect(await screen.findByText(/sent a new link/i)).toBeInTheDocument();
  });

  it('surfaces an error when the resend request fails', async () => {
    mockedVerify.mockResolvedValue('rejected');
    mockedResend.mockRejectedValue(new Error('network'));
    const user = userEvent.setup();

    renderAt('/verify?token=stale-token');
    await screen.findByRole('heading', { name: /no longer valid/i });

    await user.type(screen.getByLabelText(/email/i), 'newcomer@example.com');
    await user.click(screen.getByRole('button', { name: /resend link/i }));

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
  });

  it('spends a single-use token once under StrictMode and shows the result', async () => {
    // StrictMode double-invokes the verify Effect; without the dedupe the second
    // call would 410 against the just-consumed token and wrongly show a rejected
    // link. The first (and only) call must win.
    mockedVerify
      .mockResolvedValueOnce('verified')
      .mockResolvedValue('rejected');

    render(
      <StrictMode>
        <MemoryRouter initialEntries={['/verify?token=good-token']}>
          <VerifyPage />
        </MemoryRouter>
      </StrictMode>,
    );

    expect(
      await screen.findByRole('heading', { name: /email verified/i }),
    ).toBeInTheDocument();
    expect(mockedVerify).toHaveBeenCalledTimes(1);
  });

  it('treats a missing token as a rejected link', async () => {
    renderAt('/verify');

    expect(
      await screen.findByRole('heading', { name: /no longer valid/i }),
    ).toBeInTheDocument();
    expect(mockedVerify).not.toHaveBeenCalled();
  });
});
