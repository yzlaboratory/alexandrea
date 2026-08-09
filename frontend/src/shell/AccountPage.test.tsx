import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import AccountPage from './AccountPage';
import * as authApi from '../auth/authApi';

vi.mock('../auth/authApi', () => ({
  changePassword: vi.fn(),
  requestEmailChange: vi.fn(),
}));

const mockedChangePassword = vi.mocked(authApi.changePassword);
const mockedRequestEmailChange = vi.mocked(authApi.requestEmailChange);

// AccountPage hosts two forms that each have their own "Current password"
// field, so queries scope to the form under its heading rather than using an
// ambiguous page-wide label lookup.
function formNamed(heading: RegExp): HTMLElement {
  const form = screen.getByRole('heading', { name: heading }).closest('form');
  if (form === null)
    throw new Error(`No <form> ancestor for heading ${heading.toString()}`);
  return form;
}

async function fillAndSubmitPasswordChange(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const user = userEvent.setup();
  const form = within(formNamed(/^change password$/i));
  await user.type(form.getByLabelText(/current password/i), currentPassword);
  await user.type(form.getByLabelText(/new password/i), newPassword);
  await user.click(form.getByRole('button', { name: /update password/i }));
}

async function fillAndSubmitEmailChange(
  currentPassword: string,
  newEmail: string,
): Promise<void> {
  const user = userEvent.setup();
  const form = within(formNamed(/^change email$/i));
  await user.type(form.getByLabelText(/current password/i), currentPassword);
  await user.type(form.getByLabelText(/new email/i), newEmail);
  await user.click(
    form.getByRole('button', { name: /send confirmation link/i }),
  );
}

describe('AccountPage', () => {
  beforeEach(() => {
    mockedChangePassword.mockReset();
    mockedRequestEmailChange.mockReset();
  });

  it('confirms the password change and clears the form on success', async () => {
    mockedChangePassword.mockResolvedValue({ status: 'changed' });
    render(<AccountPage />);

    await fillAndSubmitPasswordChange(
      'the-old-password',
      'a-brand-new-password',
    );

    expect(
      await screen.findByText(/signed out of your other sessions/i),
    ).toBeInTheDocument();
    expect(
      within(formNamed(/^change password$/i)).getByLabelText(
        /current password/i,
      ),
    ).toHaveValue('');
  });

  it('surfaces an incorrect-current-password error on the password form', async () => {
    mockedChangePassword.mockResolvedValue({
      status: 'incorrect-current-password',
    });
    render(<AccountPage />);

    await fillAndSubmitPasswordChange('wrong-password', 'a-brand-new-password');

    expect(
      await screen.findByText(/current password is incorrect/i),
    ).toBeInTheDocument();
  });

  it('surfaces a password-policy error on the password form', async () => {
    mockedChangePassword.mockResolvedValue({ status: 'invalid-password' });
    render(<AccountPage />);

    await fillAndSubmitPasswordChange('the-old-password', 'tooshort');

    expect(
      await screen.findByText(/between 12 and 128 characters/i),
    ).toBeInTheDocument();
  });

  it('confirms an email change request and clears the form', async () => {
    mockedRequestEmailChange.mockResolvedValue({ status: 'requested' });
    render(<AccountPage />);

    await fillAndSubmitEmailChange('the-current-password', 'new@example.com');

    expect(
      await screen.findByText(/sent a confirmation link there/i),
    ).toBeInTheDocument();
    expect(screen.getByText('new@example.com')).toBeInTheDocument();
    expect(
      within(formNamed(/^change email$/i)).getByLabelText(/current password/i),
    ).toHaveValue('');
  });

  it('surfaces an incorrect-current-password error on the email form', async () => {
    mockedRequestEmailChange.mockResolvedValue({
      status: 'incorrect-current-password',
    });
    render(<AccountPage />);

    await fillAndSubmitEmailChange('wrong-password', 'new@example.com');

    expect(
      await screen.findByText(/current password is incorrect/i),
    ).toBeInTheDocument();
  });
});
