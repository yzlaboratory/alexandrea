import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import ChangeEmailForm from './ChangeEmailForm';
import { type RequestEmailChangeOutcome } from './authApi';

function fillAndSubmit(
  currentPassword: string,
  newEmail: string,
): Promise<void> {
  return (async () => {
    const user = userEvent.setup();
    await user.type(
      screen.getByLabelText(/current password/i),
      currentPassword,
    );
    await user.type(screen.getByLabelText(/new email/i), newEmail);
    await user.click(
      screen.getByRole('button', { name: /send confirmation link/i }),
    );
  })();
}

describe('ChangeEmailForm', () => {
  it('notifies its parent with the new email when the request succeeds', async () => {
    const onRequested = vi.fn();
    const submit = vi.fn(
      (): Promise<RequestEmailChangeOutcome> =>
        Promise.resolve({ status: 'requested' }),
    );
    render(<ChangeEmailForm onRequested={onRequested} submit={submit} />);

    await fillAndSubmit('the-current-password', 'new@example.com');

    await waitFor(() => {
      expect(onRequested).toHaveBeenCalledWith('new@example.com');
    });
    expect(submit).toHaveBeenCalledWith(
      'the-current-password',
      'new@example.com',
    );
  });

  it('shows an incorrect-current-password error and does not notify the parent', async () => {
    const onRequested = vi.fn();
    const submit = vi.fn(
      (): Promise<RequestEmailChangeOutcome> =>
        Promise.resolve({ status: 'incorrect-current-password' }),
    );
    render(<ChangeEmailForm onRequested={onRequested} submit={submit} />);

    await fillAndSubmit('wrong-password', 'new@example.com');

    expect(
      await screen.findByText(/current password is incorrect/i),
    ).toBeInTheDocument();
    expect(onRequested).not.toHaveBeenCalled();
  });

  it('shows a generic error and does not notify the parent when the request fails', async () => {
    const onRequested = vi.fn();
    const submit = vi.fn(
      (): Promise<RequestEmailChangeOutcome> =>
        Promise.resolve({ status: 'error' }),
    );
    render(<ChangeEmailForm onRequested={onRequested} submit={submit} />);

    await fillAndSubmit('the-current-password', 'new@example.com');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
    expect(onRequested).not.toHaveBeenCalled();
  });
});
