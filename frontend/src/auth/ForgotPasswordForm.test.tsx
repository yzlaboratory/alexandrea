import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import ForgotPasswordForm from './ForgotPasswordForm';

function fillAndSubmit(email: string): Promise<void> {
  return (async () => {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), email);
    await user.click(screen.getByRole('button', { name: /send reset link/i }));
  })();
}

describe('ForgotPasswordForm', () => {
  it('reports the submitted email to its parent when the request succeeds', async () => {
    const onRequested = vi.fn();
    const submit = vi.fn((): Promise<void> => Promise.resolve());
    render(<ForgotPasswordForm onRequested={onRequested} submit={submit} />);

    await fillAndSubmit('reader@example.com');

    await waitFor(() => {
      expect(onRequested).toHaveBeenCalledWith('reader@example.com');
    });
    expect(submit).toHaveBeenCalledWith('reader@example.com');
  });

  it('shows a generic error and does not advance when the request fails', async () => {
    const onRequested = vi.fn();
    const submit = vi.fn(
      (): Promise<void> => Promise.reject(new Error('network')),
    );
    render(<ForgotPasswordForm onRequested={onRequested} submit={submit} />);

    await fillAndSubmit('reader@example.com');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
    expect(onRequested).not.toHaveBeenCalled();
  });
});
