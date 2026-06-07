import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import SignupPage from './SignupPage';
import * as authApi from './authApi';

vi.mock('./authApi', () => ({
  signup: vi.fn(),
  resendVerification: vi.fn(),
}));

const mockedSignup = vi.mocked(authApi.signup);
const mockedResend = vi.mocked(authApi.resendVerification);

describe('SignupPage', () => {
  beforeEach(() => {
    mockedSignup.mockReset();
    mockedResend.mockReset();
  });

  it('advances from the form to the check-email state on a successful signup', async () => {
    mockedSignup.mockResolvedValue({ status: 'accepted' });
    const user = userEvent.setup();
    render(<SignupPage />);

    await user.type(screen.getByLabelText(/email/i), 'newcomer@example.com');
    await user.type(screen.getByLabelText(/password/i), 'a-good-long-password');
    await user.click(screen.getByRole('button', { name: /sign up/i }));

    expect(
      await screen.findByRole('heading', { name: /check your email/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/newcomer@example.com/i)).toBeInTheDocument();
  });

  it('offers a resend on the check-email state that re-requests the link', async () => {
    mockedSignup.mockResolvedValue({ status: 'accepted' });
    mockedResend.mockResolvedValue();
    const user = userEvent.setup();
    render(<SignupPage />);

    await user.type(screen.getByLabelText(/email/i), 'newcomer@example.com');
    await user.type(screen.getByLabelText(/password/i), 'a-good-long-password');
    await user.click(screen.getByRole('button', { name: /sign up/i }));

    await screen.findByRole('heading', { name: /check your email/i });
    await user.click(screen.getByRole('button', { name: /resend/i }));

    expect(mockedResend).toHaveBeenCalledWith('newcomer@example.com');
    expect(await screen.findByText(/sent another link/i)).toBeInTheDocument();
  });
});
