import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import LoginForm from './LoginForm';
import { type LoginOutcome } from './authApi';

function fillAndSubmit(email: string, password: string): Promise<void> {
  return (async () => {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/email/i), email);
    await user.type(screen.getByLabelText(/password/i), password);
    await user.click(screen.getByRole('button', { name: /log in/i }));
  })();
}

describe('LoginForm', () => {
  it('reports the sticky media type to its parent when login succeeds', async () => {
    const onAuthenticated = vi.fn();
    const onUnverified = vi.fn();
    const submit = vi.fn(
      (): Promise<LoginOutcome> =>
        Promise.resolve({ status: 'authenticated', lastMediaType: 'games' }),
    );
    render(
      <LoginForm
        onAuthenticated={onAuthenticated}
        onUnverified={onUnverified}
        submit={submit}
      />,
    );

    await fillAndSubmit('reader@example.com', 'a-good-long-password');

    await waitFor(() => {
      expect(onAuthenticated).toHaveBeenCalledWith('games');
    });
    expect(onUnverified).not.toHaveBeenCalled();
    expect(submit).toHaveBeenCalledWith(
      'reader@example.com',
      'a-good-long-password',
    );
  });

  it('reports the submitted email to its parent when the account is unverified', async () => {
    const onAuthenticated = vi.fn();
    const onUnverified = vi.fn();
    const submit = vi.fn(
      (): Promise<LoginOutcome> => Promise.resolve({ status: 'unverified' }),
    );
    render(
      <LoginForm
        onAuthenticated={onAuthenticated}
        onUnverified={onUnverified}
        submit={submit}
      />,
    );

    await fillAndSubmit('pending@example.com', 'a-good-long-password');

    await waitFor(() => {
      expect(onUnverified).toHaveBeenCalledWith('pending@example.com');
    });
    expect(onAuthenticated).not.toHaveBeenCalled();
  });

  it('shows the generic rejection and does not advance for invalid credentials', async () => {
    const onAuthenticated = vi.fn();
    const submit = vi.fn(
      (): Promise<LoginOutcome> =>
        Promise.resolve({ status: 'invalid-credentials' }),
    );
    render(
      <LoginForm
        onAuthenticated={onAuthenticated}
        onUnverified={vi.fn()}
        submit={submit}
      />,
    );

    await fillAndSubmit('nobody@example.com', 'wrong-password');

    expect(
      await screen.findByText(/email or password is incorrect/i),
    ).toBeInTheDocument();
    expect(onAuthenticated).not.toHaveBeenCalled();
  });

  it('shows a generic error when the request fails', async () => {
    const submit = vi.fn(
      (): Promise<LoginOutcome> => Promise.resolve({ status: 'error' }),
    );
    render(
      <LoginForm
        onAuthenticated={vi.fn()}
        onUnverified={vi.fn()}
        submit={submit}
      />,
    );

    await fillAndSubmit('reader@example.com', 'a-good-long-password');

    expect(
      await screen.findByText(/something went wrong/i),
    ).toBeInTheDocument();
  });
});
