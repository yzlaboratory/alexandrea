import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import ChangePasswordForm from './ChangePasswordForm';
import { type ChangePasswordOutcome } from './authApi';

function fillAndSubmit(
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  return (async () => {
    const user = userEvent.setup();
    await user.type(
      screen.getByLabelText(/current password/i),
      currentPassword,
    );
    await user.type(screen.getByLabelText(/new password/i), newPassword);
    await user.click(screen.getByRole('button', { name: /update password/i }));
  })();
}

describe('ChangePasswordForm', () => {
  it('notifies its parent with the current and new password when the change succeeds', async () => {
    const onChanged = vi.fn();
    const submit = vi.fn(
      (): Promise<ChangePasswordOutcome> =>
        Promise.resolve({ status: 'changed' }),
    );
    render(<ChangePasswordForm onChanged={onChanged} submit={submit} />);

    await fillAndSubmit('the-old-password', 'a-brand-new-password');

    await waitFor(() => {
      expect(onChanged).toHaveBeenCalled();
    });
    expect(submit).toHaveBeenCalledWith(
      'the-old-password',
      'a-brand-new-password',
    );
  });

  it('shows an incorrect-current-password error and does not notify the parent', async () => {
    const onChanged = vi.fn();
    const submit = vi.fn(
      (): Promise<ChangePasswordOutcome> =>
        Promise.resolve({ status: 'incorrect-current-password' }),
    );
    render(<ChangePasswordForm onChanged={onChanged} submit={submit} />);

    await fillAndSubmit('wrong-password', 'a-brand-new-password');

    expect(
      await screen.findByText(/current password is incorrect/i),
    ).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('shows a length error and does not notify the parent when the new password is rejected', async () => {
    const onChanged = vi.fn();
    const submit = vi.fn(
      (): Promise<ChangePasswordOutcome> =>
        Promise.resolve({ status: 'invalid-password' }),
    );
    render(<ChangePasswordForm onChanged={onChanged} submit={submit} />);

    await fillAndSubmit('the-old-password', 'tooshort');

    expect(
      await screen.findByText(/between 12 and 128 characters/i),
    ).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('shows a generic error when the request fails', async () => {
    const submit = vi.fn(
      (): Promise<ChangePasswordOutcome> =>
        Promise.resolve({ status: 'error' }),
    );
    render(<ChangePasswordForm onChanged={vi.fn()} submit={submit} />);

    await fillAndSubmit('the-old-password', 'a-brand-new-password');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
  });
});
