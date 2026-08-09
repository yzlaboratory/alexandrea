import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AccountPage from './AccountPage';
import * as authApi from '../auth/authApi';

vi.mock('../auth/authApi', () => ({
  changePassword: vi.fn(),
}));

const mockedChangePassword = vi.mocked(authApi.changePassword);

async function fillAndSubmit(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(/current password/i), currentPassword);
  await user.type(screen.getByLabelText(/new password/i), newPassword);
  await user.click(screen.getByRole('button', { name: /update password/i }));
}

describe('AccountPage', () => {
  beforeEach(() => {
    mockedChangePassword.mockReset();
  });

  it('confirms the change and clears the form on success', async () => {
    mockedChangePassword.mockResolvedValue({ status: 'changed' });
    render(<AccountPage />);

    await fillAndSubmit('the-old-password', 'a-brand-new-password');

    expect(
      await screen.findByText(/signed out of your other sessions/i),
    ).toBeInTheDocument();
    expect(screen.getByLabelText(/current password/i)).toHaveValue('');
  });

  it('surfaces an incorrect-current-password error inline', async () => {
    mockedChangePassword.mockResolvedValue({
      status: 'incorrect-current-password',
    });
    render(<AccountPage />);

    await fillAndSubmit('wrong-password', 'a-brand-new-password');

    expect(
      await screen.findByText(/current password is incorrect/i),
    ).toBeInTheDocument();
  });

  it('surfaces a password-policy error inline', async () => {
    mockedChangePassword.mockResolvedValue({ status: 'invalid-password' });
    render(<AccountPage />);

    await fillAndSubmit('the-old-password', 'tooshort');

    expect(
      await screen.findByText(/between 12 and 128 characters/i),
    ).toBeInTheDocument();
  });

  it('hosts a change-email placeholder with no link to an external auth service', () => {
    render(<AccountPage />);

    expect(
      screen.getByRole('heading', { name: /change email/i }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});
