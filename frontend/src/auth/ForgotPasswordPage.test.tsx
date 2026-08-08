import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ForgotPasswordPage from './ForgotPasswordPage';
import * as authApi from './authApi';

vi.mock('./authApi', () => ({
  requestPasswordReset: vi.fn(),
}));

const mockedRequestPasswordReset = vi.mocked(authApi.requestPasswordReset);

function renderPage(): void {
  render(
    <MemoryRouter>
      <ForgotPasswordPage />
    </MemoryRouter>,
  );
}

describe('ForgotPasswordPage', () => {
  beforeEach(() => {
    mockedRequestPasswordReset.mockReset();
  });

  it('advances from the form to the generic confirmation panel', async () => {
    mockedRequestPasswordReset.mockResolvedValue();
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/email/i), 'reader@example.com');
    await user.click(screen.getByRole('button', { name: /send reset link/i }));

    expect(
      await screen.findByRole('heading', { name: /check your email/i }),
    ).toBeInTheDocument();
    expect(screen.getByText(/reader@example.com/i)).toBeInTheDocument();
  });

  it('links back to log in from the confirmation panel', async () => {
    mockedRequestPasswordReset.mockResolvedValue();
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByLabelText(/email/i), 'reader@example.com');
    await user.click(screen.getByRole('button', { name: /send reset link/i }));

    expect(
      await screen.findByRole('link', { name: /back to log in/i }),
    ).toHaveAttribute('href', '/login');
  });
});
