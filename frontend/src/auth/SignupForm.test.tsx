import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import SignupForm from './SignupForm';
import { type SignupOutcome } from './authApi';

function fillAndSubmit(email: string, password: string): Promise<void> {
  return (async () => {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), email);
    await user.type(screen.getByLabelText(/password/i), password);
    await user.click(screen.getByRole('button', { name: /sign up/i }));
  })();
}

describe('SignupForm', () => {
  it('reports the submitted email to its parent when signup is accepted', async () => {
    const onAccepted = vi.fn();
    const submit = vi.fn(
      (): Promise<SignupOutcome> => Promise.resolve({ status: 'accepted' }),
    );
    render(<SignupForm onAccepted={onAccepted} submit={submit} />);

    await fillAndSubmit('newcomer@example.com', 'a-good-long-password');

    await waitFor(() => {
      expect(onAccepted).toHaveBeenCalledWith('newcomer@example.com');
    });
    expect(submit).toHaveBeenCalledWith(
      'newcomer@example.com',
      'a-good-long-password',
    );
  });

  it('shows a length error and does not advance when the password is rejected', async () => {
    const onAccepted = vi.fn();
    const submit = vi.fn(
      (): Promise<SignupOutcome> =>
        Promise.resolve({ status: 'invalid-password' }),
    );
    render(<SignupForm onAccepted={onAccepted} submit={submit} />);

    await fillAndSubmit('short@example.com', 'tooshort');

    expect(
      await screen.findByText(/between 12 and 128 characters/i),
    ).toBeInTheDocument();
    expect(onAccepted).not.toHaveBeenCalled();
  });

  it('shows a generic error when the request fails', async () => {
    const submit = vi.fn(
      (): Promise<SignupOutcome> => Promise.resolve({ status: 'error' }),
    );
    render(<SignupForm onAccepted={vi.fn()} submit={submit} />);

    await fillAndSubmit('newcomer@example.com', 'a-good-long-password');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
  });
});
