import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import ResetPasswordForm from './ResetPasswordForm';
import { type ResetPasswordOutcome } from './authApi';

function fillAndSubmit(newPassword: string): Promise<void> {
  return (async () => {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/new password/i), newPassword);
    await user.click(screen.getByRole('button', { name: /update password/i }));
  })();
}

describe('ResetPasswordForm', () => {
  it('notifies its parent with the token and password when the reset succeeds', async () => {
    const onReset = vi.fn();
    const onRejected = vi.fn();
    const submit = vi.fn(
      (): Promise<ResetPasswordOutcome> => Promise.resolve({ status: 'reset' }),
    );
    render(
      <ResetPasswordForm
        token="good-token"
        onReset={onReset}
        onRejected={onRejected}
        submit={submit}
      />,
    );

    await fillAndSubmit('a-brand-new-password');

    await waitFor(() => {
      expect(onReset).toHaveBeenCalled();
    });
    expect(onRejected).not.toHaveBeenCalled();
    expect(submit).toHaveBeenCalledWith('good-token', 'a-brand-new-password');
  });

  it('notifies its parent when the link is expired or already used', async () => {
    const onReset = vi.fn();
    const onRejected = vi.fn();
    const submit = vi.fn(
      (): Promise<ResetPasswordOutcome> =>
        Promise.resolve({ status: 'rejected' }),
    );
    render(
      <ResetPasswordForm
        token="stale-token"
        onReset={onReset}
        onRejected={onRejected}
        submit={submit}
      />,
    );

    await fillAndSubmit('a-brand-new-password');

    await waitFor(() => {
      expect(onRejected).toHaveBeenCalled();
    });
    expect(onReset).not.toHaveBeenCalled();
  });

  it('shows a length error and does not advance when the new password is rejected', async () => {
    const onReset = vi.fn();
    const submit = vi.fn(
      (): Promise<ResetPasswordOutcome> =>
        Promise.resolve({ status: 'invalid-password' }),
    );
    render(
      <ResetPasswordForm
        token="good-token"
        onReset={onReset}
        onRejected={vi.fn()}
        submit={submit}
      />,
    );

    await fillAndSubmit('tooshort');

    expect(
      await screen.findByText(/between 12 and 128 characters/i),
    ).toBeInTheDocument();
    expect(onReset).not.toHaveBeenCalled();
  });

  it('shows a generic error when the request fails', async () => {
    const submit = vi.fn(
      (): Promise<ResetPasswordOutcome> => Promise.resolve({ status: 'error' }),
    );
    render(
      <ResetPasswordForm
        token="good-token"
        onReset={vi.fn()}
        onRejected={vi.fn()}
        submit={submit}
      />,
    );

    await fillAndSubmit('a-brand-new-password');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
  });

  it('shows a generic error and does not advance when submit rejects', async () => {
    const onReset = vi.fn();
    const onRejected = vi.fn();
    const submit = vi.fn(
      (): Promise<ResetPasswordOutcome> => Promise.reject(new Error('network')),
    );
    render(
      <ResetPasswordForm
        token="good-token"
        onReset={onReset}
        onRejected={onRejected}
        submit={submit}
      />,
    );

    await fillAndSubmit('a-brand-new-password');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
    expect(onReset).not.toHaveBeenCalled();
    expect(onRejected).not.toHaveBeenCalled();
  });
});
