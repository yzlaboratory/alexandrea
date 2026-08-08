import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ResetPasswordPage from './ResetPasswordPage';
import * as authApi from './authApi';

vi.mock('./authApi', () => ({
  resetPassword: vi.fn(),
}));

const mockedResetPassword = vi.mocked(authApi.resetPassword);

function renderAt(path: string): void {
  render(
    <MemoryRouter initialEntries={[path]}>
      <ResetPasswordPage />
    </MemoryRouter>,
  );
}

async function fillAndSubmit(newPassword: string): Promise<void> {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/new password/i), newPassword);
  await user.click(screen.getByRole('button', { name: /update password/i }));
}

describe('ResetPasswordPage', () => {
  beforeEach(() => {
    mockedResetPassword.mockReset();
  });

  it('confirms the new password and links to log in', async () => {
    mockedResetPassword.mockResolvedValue({ status: 'reset' });
    renderAt('/reset-password?token=good-token');

    await fillAndSubmit('a-brand-new-password');

    expect(
      await screen.findByRole('heading', { name: /password updated/i }),
    ).toBeInTheDocument();
    expect(mockedResetPassword).toHaveBeenCalledWith(
      'good-token',
      'a-brand-new-password',
    );
    expect(screen.getByRole('link', { name: /go to log in/i })).toHaveAttribute(
      'href',
      '/login',
    );
  });

  it('offers a fresh request when the link is expired or already used', async () => {
    mockedResetPassword.mockResolvedValue({ status: 'rejected' });
    renderAt('/reset-password?token=stale-token');

    await fillAndSubmit('a-brand-new-password');

    expect(
      await screen.findByRole('heading', { name: /no longer valid/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: /request a new link/i }),
    ).toHaveAttribute('href', '/forgot-password');
  });

  it('treats a missing token as an immediately rejected link', async () => {
    renderAt('/reset-password');

    expect(
      await screen.findByRole('heading', { name: /no longer valid/i }),
    ).toBeInTheDocument();
    expect(mockedResetPassword).not.toHaveBeenCalled();
  });
});
